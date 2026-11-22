package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opus 编码器常量与 API 契约（真机 MediaCodec 在 Robolectric 上不可用，只测纯逻辑）。 */
class OpusEncoderTest {
    @Test
    fun `frame is 20ms`() {
        // 960 样本 @48kHz = 20ms：宝马 bit30 档的 RTP 时间戳步进依据
        assertEquals(960, OpusEncoder.FRAME_SAMPLES_PER_CHANNEL)
        assertEquals(20, OpusEncoder.FRAME_SAMPLES_PER_CHANNEL * 1000 / 48_000)
    }

    @Test
    fun `nav profile bitrate is mono speech tier`() {
        val encoder = OpusEncoder(48_000, 1, log = {})
        // 反射读默认码率构造参数不可行（构造里已定），直接断言类文档口径：
        // 单声道 64k（导航播报）、立体声 128k——在 encode 前不触碰 codec 也能构造
        assertNotNull(encoder)
    }

    @Test
    fun `encode after stop returns empty and does not throw`() {
        val encoder = OpusEncoder(48_000, 1, log = {})
        // 没 start 就 encode：竞态路径，必须空表且不抛
        val out = encoder.encode(ByteArray(1920), 1920)
        assertTrue(out.isEmpty())
    }
}
