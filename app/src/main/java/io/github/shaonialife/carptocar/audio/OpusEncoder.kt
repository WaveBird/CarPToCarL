package io.github.shaonialife.carptocar.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * PCM → Opus 编码器（宝马 MGU alert/default/telephony 通路用，bit30）。
 *
 * 为什么需要它（2026-11-22 宝马 530Li / MGU-55-J 实测）：该车机 /info 的
 * `type=101 AltAudio / default`（导航播报标准通路）与 `type=100` 的
 * default/telephony/alert 全部只声明 `audioOutputFormats=0x40000000`（bit30）——
 * 按 AirPlay 压缩类型表 ct=32 = **Opus**，没有任何 PCM/AAC 位。高德 TTS 的
 * PCM（16kHz 单声道）全程照收却一条也发不出去（2.3MB 全被丢弃）。
 *
 * 线格式：MediaCodec audio/opus 输出裸 Opus 帧（无 Ogg 封装），RTP 负载即该帧，
 * 一包一帧，与 AAC-LC 同构。
 *
 * 帧长与时间戳：
 *  - CSD 配置帧（codec-config）在首帧前单独发出（部分车机要求带 OpusHead，
 *    见 [configFrame]）。
 *  - 普通帧 20ms（960 样本 @48kHz）——Opus 帧长可变，但 MediaCodec c2.android
 *    opus.encoder 固定 20ms 一帧，RTP 时间戳按 960 步进。
 *  - 输入 PCM 必须 48kHz（MediaCodec Opus 编码器只认 48k）；16kHz 的 TTS 由
 *    AudioSender 的重采样对齐到 48k 后喂入（对齐逻辑与 AAC 通路共用）。
 */
class OpusEncoder(
    /** 编码输入采样率，固定 48kHz（MediaCodec Opus 编码器限制）。 */
    private val sampleRate: Int = 48_000,
    /** 声道数：1 = 单声道（导航 TTS），2 = 立体声。 */
    private val channels: Int = 1,
    /** 编码码率（bps）。导航播报 64k 单声道已足够清亮；立体声媒体 128k。 */
    private val bitRate: Int = if (channels == 1) 64_000 else 128_000,
    private val log: (String) -> Unit,
) {
    companion object {
        /** Opus 20ms 帧长（每声道样本数 @48kHz）。 */
        const val FRAME_SAMPLES_PER_CHANNEL = 960

        /** 编码器输入/输出超时（微秒），与 AacLcEncoder 同一量级。 */
        private const val TIMEOUT_US = 10_000L
    }

    private val lock = Any()
    private var codec: MediaCodec? = null
    private var started = false

    /** CSD（codec-specific data）：OpusHead，首个输出缓冲携带，须单独下发给车机。 */
    @Volatile
    var configFrame: ByteArray? = null
        private set

    /** 已喂入的 PCM 采样数（per channel）——RTP 时间戳步进依据。 */
    var presentedSamples: Long = 0L
        private set

    /** stop 后仍到达的 encode 次数（竞态丢弃，收尾时汇总）。 */
    private var droppedAfterStop = 0L
    private var droppedInputBusy = 0L

    fun start(): Boolean =
        synchronized(lock) {
            try {
                val format =
                    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                        // 复杂度 10（0-10）：车机解码侧无所谓，编码端 CPU 允许就拉满，
                        // 换取同等码率下更好的语音质量（导航播报是纯人声，Opus 语音
                        // 模式在这个复杂度下 MOS 明显高于 5）。
                        setInteger(MediaFormat.KEY_COMPLEXITY, 10)
                        setInteger(MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL, 0)
                    }
                val encoder =
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
                codec = encoder
                started = true
                log(
                    "AUDIO: Opus 编码器就绪（${sampleRate}Hz ${if (channels == 1) "单声道" else "立体声"} " +
                        "${bitRate / 1000}kbps，帧=${FRAME_SAMPLES_PER_CHANNEL}样本/20ms，codec=${encoder.name}）",
                )
                true
            } catch (error: Exception) {
                log("AUDIO: Opus 编码器启动失败 - ${error.message}")
                false
            }
        }

    /**
     * 编码一帧 PCM，返回 0..n 个 Opus 帧（首帧可能空——编码器有 1~2 帧延迟）。
     * 帧内含 CSD 首位的话会先填 [configFrame]。
     */
    fun encode(pcm: ByteArray, size: Int): List<ByteArray> =
        synchronized(lock) {
            val encoder = codec
            if (encoder == null || !started) {
                droppedAfterStop++
                return emptyList()
            }
            val output = mutableListOf<ByteArray>()
            try {
                val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex < 0) {
                    droppedInputBusy++
                    if (droppedInputBusy == 1L || droppedInputBusy % 200L == 0L) {
                        log("AUDIO: Opus 编码器输入缓冲满——已丢 $droppedInputBusy 帧 PCM（不影响会话）")
                    }
                } else {
                    val buffer = encoder.getInputBuffer(inputIndex)
                    val length = size.coerceAtMost(buffer?.capacity() ?: 0).coerceAtMost(pcm.size)
                    if (buffer == null || length <= 0) {
                        encoder.queueInputBuffer(inputIndex, 0, 0, presentedSamples * 1_000_000L / sampleRate, 0)
                        droppedInputBusy++
                    } else {
                        buffer.clear()
                        buffer.put(pcm, 0, length)
                        encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            length,
                            presentedSamples * 1_000_000L / sampleRate,
                            0,
                        )
                        presentedSamples += length / 2 / channels
                    }
                }
                drain(encoder, output)
            } catch (error: IllegalStateException) {
                codec = null
                started = false
                log("AUDIO: Opus 编码器已释放，停止编码（${error.message}）——收尾竞态，已忽略")
            } catch (error: Exception) {
                log("AUDIO: Opus 编码异常 - ${error.message}")
            }
            output
        }

    private fun drain(encoder: MediaCodec, output: MutableList<ByteArray>) {
        while (true) {
            val info = MediaCodec.BufferInfo()
            val index = encoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index < 0 -> return
                else -> {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            configFrame = data
                            log("AUDIO: Opus CSD 就绪（OpusHead ${data.size}B）")
                        } else {
                            output += data
                        }
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (droppedAfterStop > 0) {
                log("AUDIO: Opus 收尾竞态丢弃 $droppedAfterStop 次 encode（无害）")
                droppedAfterStop = 0
            }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            started = false
        }
    }
}
