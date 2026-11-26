package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.video.VideoSender
import java.util.concurrent.Executors

/**
 * 资源归属 → 屏幕/音频流启停，全部串行在一条工作线程上：事件读线程要立即应答车机的
 * `changeModes`，不能等 SETUP/RECORD/TEARDOWN 这些阻塞 RTSP。
 *
 * 车机借走屏幕（倒车影像/雷达 PDC）不是会话断开：默认按 `TEARDOWN` 屏幕流、归还或收到
 * `requestUI` 后重新 `SETUP` + `RECORD` 恢复画面；**实验开关**（[keepScreenStreamOnTake]）
 * 打开时改为保留视频流、只承认归属不是手机，归还后补关键帧即可——车机反复改判时不再付
 * 每次都重建的代价（别克 6369 实测每轮 100~170ms 就被夺，一帧留不住）。
 * 主音频同理：手机持有且有播放意图才起音频流，车机接管/静音借走时退役，归还后按播放意图恢复。
 */
internal class CarPlayResourceStreams(
    private val client: AirPlayClient,
    private val sessionKey: ByteArray?,
    private val displayUuid: String?,
    private val audioIntent: CarPlayAudioIntent,
    initialSender: VideoSender?,
    private val createSender: (AirPlayClient.ScreenStreamInfo) -> VideoSender,
    /** [streamKept] = true 表示实验开关生效、视频流被保留（调用方不要 detach 采集）。 */
    private val onScreenPaused: (Boolean) -> Unit,
    /** [soft] = true 表示沿用现有视频流（没有重新 SETUP），只需补关键帧与重武装 HID。 */
    private val onScreenResumed: (VideoSender, Boolean) -> Boolean,
    /** true = 起音频流并激活，false = 退役；返回此后音频流是否仍在推。 */
    private val onAudioOwnership: (Boolean) -> Boolean,
    private val onFailure: (String) -> Unit,
    private val log: (String) -> Unit,
    /** 单调时钟（只用于日志里的「存活多久 / 等了多久」，可注入便于单测）。 */
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val worker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "carplay-resource-streams").apply { isDaemon = true }
        }

    @Volatile
    private var closed = false

    // 以下状态只在工作线程上读写
    private var sender: VideoSender? = initialSender
    private var screenNegotiated = initialSender != null
    private var screenStreaming = false
    private var screenStarted = false
    private var audioStarted = false
    private var audioActive = false
    private var screenRequested = initialSender == null
    private var screenRequestFromCarUi = false

    /**
     * 实验开关下的「软挂起」：归属已不是手机，但视频流没拆、编码器还在跑。
     *
     * 与 [screenPaused] 的区别：后者 = 流已 TEARDOWN，恢复要重新 SETUP；这里只等归属回来。
     */
    private var softHold = false

    // 诊断计数（只在工作线程上读写）：一轮会话里「让屏 → 等 → 起流 → 又被抢」到底循环了几次、
    // 每次送流实际活了多久。没有这两个数就只能靠人工比对时间戳，且看不出「越抢越快」。
    private var screenLosses = 0
    private var screenResumes = 0
    private var streamStartedAtMs = 0L
    private var waitStartedAtMs = 0L

    // ---- 1.2.107 requestUI fighting 防护（P2-12）----
    // 实车 194036 日志 19:35:43 车机 take permanent 后，19:35:44.406–19:35:45.824
    // 400ms 内 3 次 requestUI，我们每次都「让出→拿回」，19:35:47 车机再 take permanent、
    // 19:35:50 拆 BT 链路 + control EOF——归属争夺（fighting）直接把会话吵死了。
    // 状态与判定抽成 [RequestUiFightingGuard]（可单测），本类只做流启停编排。
    private val fightingGuard = RequestUiFightingGuard(nowMs, log)

    /** 本会话内禁用「让出→拿回」（guard 维护，调试/诊断可见）。 */
    val surrenderRetakeBanned: Boolean get() = fightingGuard.surrenderRetakeBanned

    /** 上次记录的永久归属（修复 B：只在「刚变成车机」那条沿打一次日志）。 */
    private var lastKnownPermanentCar = false

    /** 车机持有主屏、视频暂停中（控制会话仍在）。 */
    @Volatile
    var screenPaused = initialSender == null
        private set

    /** 首次 RECORD 与挂载完成后接管屏幕流。 */
    fun activate(initialScreenAttached: Boolean) =
        post {
            screenStarted = true
            screenStreaming = initialScreenAttached && sender?.isRunning == true
            if (sender == null) onScreenPaused(false) else streamStartedAtMs = nowMs()
        }

    /** 建链阶段的音频处理完成后接管音频流；[initialAudioActive] = 音频已随 RECORD 激活。 */
    fun activateAudio(initialAudioActive: Boolean) =
        post {
            audioStarted = true
            audioActive = initialAudioActive
        }

    /** 申请主屏；[fromRequestUi] = 车机 `requestUI` 主动请回手机画面。 */
    fun requestScreen(fromRequestUi: Boolean = false) =
        post {
            screenRequested = true
            if (fromRequestUi) screenRequestFromCarUi = true
        }

    /**
     * 车机改判资源或源端播放意图变化后对齐屏幕/音频流。
     *
     * 1.2.107 修复 A4：`changeModes` 到达时 AirPlayClient 已先 `applyCarChange` 更新归属，
     * 这里顺带完成「让出拿回后等车机复述」的确认——复述仍是车机就计一次收回，连续
     * 2 次本会话禁用让出拿回（见 [onCarEcho]）。
     */
    fun reconcile(fromCarScreenChange: Boolean = false) =
        post {
            // 修复 B（1.2.107）：车机 take permanent（transferType=1 takeConstraint=500，如
            // 倒车回收/HMI 切原生界面）时明确记录归属迁移——**不触发任何「申请回」动作**，
            // 保持视频热备等车机 requestUI。实车 194036：19:35:43 take permanent 后
            // requestUI 三连跳对抗，19:35:50 车机拆链 control EOF。
            val permanentCarNow = client.screenPermanentOwnerIsCar
            if (permanentCarNow && !lastKnownPermanentCar) {
                log(
                    "MODE: 主屏永久归属已变为车机（车机 HMI 切换/倒车回收，或本机主动「返回原车」）——" +
                        "保持视频热备，等待车机 requestUI 或用户操作" +
                        "（不做申请回，避免归属争夺；实车 19:35:43 take permanent → 19:35:50 拆链）",
                )
            }
            lastKnownPermanentCar = permanentCarNow
            // 仅车机涉及主屏资源的 changeModes 才喂给 onCarEcho，避免纯音频切换或本机播放意图洗白计数。
            if (fromCarScreenChange) {
                fightingGuard.onCarEcho(permanentCarNow)
            }
        }

    fun close() {
        closed = true
        screenPaused = true
        worker.shutdownNow()
    }

    private fun alive(): Boolean = !closed && !client.cancelled && client.controlSessionAlive

    private fun post(update: () -> Unit) {
        if (closed) return
        worker.execute {
            update()
            if (!alive()) return@execute
            try {
                if (screenStarted) applyScreen()
                if (audioStarted && alive()) applyAudio()
            } catch (error: Exception) {
                if (alive()) fail("资源切换失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun fail(reason: String) {
        log("MODE: 资源流故障 - $reason")
        onFailure(reason)
    }

    private fun applyScreen() {
        val fromCarUiNow = screenRequestFromCarUi
        val wasOwnedBeforeApply = client.phoneOwnsScreen
        var published = false
        // 修复 A2/B1（1.2.107）：永久归属复核必须发生在**申请之前**——申请成功会把
        // entity 翻回手机（1.2.106 实车 19:35:44.424 acquired=true），但不代表车机认账：
        // 1.9s 后（19:35:47.504）它就再 take permanent 收回。申请前先取快照。
        // 1.2.115：这份快照只喂「让出再拿回」的判定（下面 decideRetake）；「申请」那条路
        // 不再看它（见下面的说明）。
        val permanentOwnerCarBeforeClaim = client.screenPermanentOwnerIsCar
        var claimThrottled = false
        if (screenRequested && !wasOwnedBeforeApply) {
            // 实验开关打开时「尊重借用」：车机说有人在借（转向影像/倒车）就不去抢。
            // 旧行为 overrideBorrow=true 会在借用期间直接冲掉借用，车机随即把它夺回去，
            // 我们白付一轮 SETUP/RECORD。
            //
            // 1.2.115 修正（倒车后点 Apple CarPlay 进不去、只有声音的根因）：
            // 1.2.107 的「永久归属复核」把 `requestUI + 永久=车机` 一律拦下不申请，
            // 而车机把主屏 take 走（倒车影像/HMI 切原生页）之后**永久归属就是在车机**，
            // 它的 requestUI 恰恰是「请把画面给我」的显式信号——拦下等于把屏永久锁死在
            // 车机侧，车机上只剩音频（音频是独立所有权链路，不受这条影响）。
            // 这台车 take 时给的是 `takeConstraint=500`（2026-11-26 08:53:22 实车
            // /info.modes），语义就是「用户发起的申请允许拿回」——门是车机自己留的。
            // 因此这里改为：requestUI 且屏不在本机 → 按节流退避**直接申请**（不做让出跳变）。
            val requestUiClaim = screenRequestFromCarUi && !client.phoneOwnsScreen
            val claimAllowed =
                if (!requestUiClaim) {
                    true
                } else {
                    when (fightingGuard.decideClaim()) {
                        RequestUiFightingGuard.RetakeDecision.PROCEED -> true
                        else -> {
                            claimThrottled = true
                            false
                        }
                    }
                }
            published =
                if (!claimAllowed) {
                    false
                } else {
                    client.acquireScreenResource(screenRequestFromCarUi, respectBorrow = keepScreenStreamOnTake)
                }
            if (published && requestUiClaim) fightingGuard.onClaimPublished()
            log(
                "MODE: 申请主屏 fromRequestUi=$screenRequestFromCarUi acquired=$published" +
                    when {
                        claimThrottled -> "（申请节流退避中——只补关键帧，稍后再试）"
                        published && requestUiClaim -> "（车机持有中→已按 requestUI 申请取回，不做让出跳变）"
                        else -> ""
                    },
            )
        }
        screenRequested = false
        screenRequestFromCarUi = false
        // requestUI 跳变（我们 11-14 实车验证 + 上游 MGU-50-J P4 同一结论）：
        // 车机发 requestUI 时若主屏已在本机手里，它的 HMI 等的是一次真实的
        // 「让出(entity=2) → 拿回(entity=1)」归属跳变——只补关键帧车机切不进
        // CarPlay 界面（2026-11-22 15:49 实测：合并时误删此分支后 18 次 requestUI
        // 全部无跳变，车机停在原车界面）。顺序刻意不重发 RECORD：这台车对
        // 重复 RECORD 过敏（补发后 1.79~2.03s 拆链）。
        if (fromCarUiNow && wasOwnedBeforeApply && client.phoneOwnsScreen) {
            // requestUI 跳变判定抽到 [RequestUiFightingGuard]（1.2.107，P2-12）：
            // 永久归属复核（A2）/ 跳变节流（A3）/ 收回禁用（A3）都在 guard 里，
            // 这里只按判定结果编排。注意用**申请前**的永久归属快照——申请本身
            // 会把 permanentEntity 翻回手机（状态机语义），但车机并没有认账。
            when (fightingGuard.decideRetake(permanentOwnerCarBeforeClaim)) {
                RequestUiFightingGuard.RetakeDecision.PROCEED -> {
                    // C1：返回值必须赋给 published（1.2.76 就是这么写的）。否则后面
                    // `if (!published) client.republishModes()` 会再发一次，车机收到
                    // 2→1→1 三次归属变化。
                    published = client.retakeScreenForUiRequest()
                    // 修复 A4：跳变发布成功 ≠ 稳定。实车 19:35:44.541 拿回接受=true，
                    // 1.9s 后（19:35:47.504）车机就再 take permanent 收回。确认要等
                    // 车机下一次 changeModes 复述（guard.onCarEcho），确认前按
                    // 「暂归手机（车机可能收回）」对待。
                    fightingGuard.onRetakePublished()
                    log(
                        "MODE: requestUI 且主屏在本机——已执行让出再拿回跳变（ok=$published）+ 补关键帧" +
                            if (published) " · 暂归手机（车机可能收回，等复述确认）" else "",
                    )
                }
                else -> {
                    // SKIP_*：不执行让出拿回（原因已由 guard 打日志），只补关键帧。
                    // 永久归属在车机 / 节流窗口内 / 本会话已禁用——三种情况都纯等待。
                    log("MODE: requestUI 跳变被 fighting 防护拦截——只补关键帧，纯等待")
                }
            }
            sender?.requestKeyFrame()
        }
        val owned = client.phoneOwnsScreen
        val warmAlive = screenStreaming && sender?.isRunning == true
        if (owned && warmAlive) {
            if (screenPaused) {
                screenPaused = false
                // 1.2.115：车机很可能在占屏期间把自己的解码器/avcC 丢了（它切去过倒车影像，
                // 回来只补关键帧的话画面依旧黑着——声音照旧，就是「只有声音」）。
                // rearmDecoderOnScreenReturn 会重发 VideoConfig 再催 IDR。
                sender?.rearmDecoderOnScreenReturn()
                log(
                    "MODE: 主屏归还手机（视频热流保持在线，无需二次 SETUP/RECORD）" +
                        "——已重发 VideoConfig + 补关键帧秒切",
                )
            }
            return
        }
        if (!owned && warmAlive) {
            if (!screenPaused) {
                screenPaused = true
                log("MODE: 主屏暂归车机持有/借用——保持视频流在线热备（不发 TEARDOWN，避免二次 RECORD）")
            }
            if (!published) client.republishModes()
            return
        }
        if (!owned && !screenNegotiated) {
            // 1.2.115：这条以前是静默 return——车机持有主屏 + 本轮屏幕流已 TEARDOWN 时，
            // requestUI 落在这里就什么也不发，事后日志里完全看不出「为什么没画面」。
            // 走到这里说明申请也没成功（车机 takeConstraint 不允许 / 发布被拒），只能等它归还。
            if (fromCarUiNow) {
                log(
                    "MODE: requestUI 到达但主屏不在本机且本会话屏幕流已退役——申请未成功，" +
                        "等待车机归还（此刻车机上只有音频）",
                )
            }
            return
        }
        // 车机改判后复述一次当前归属（已由本机申请发布过的除外）
        if (!published) client.republishModes()
        if (softHoldScreen(owned)) return
        if (screenNegotiated && !retireScreen(if (owned) "屏幕未挂载" else "让出主屏")) return
        if (owned) resumeScreen()
    }

    /**
     * 实验开关 `keepScreenStreamOnTake`：车机占屏/还屏时**不拆流**，只切换归属与计数。
     *
     * @return true = 已按软挂起/软恢复处理完，调用方不要再走 TEARDOWN/SETUP。
     */
    private fun softHoldScreen(owned: Boolean): Boolean {
        if (!keepScreenStreamOnTake) {
            // 开关在会话中途被关掉：软挂起的流要退回常规路径，由调用方拆干净
            if (softHold) {
                log("MODE: 保留视频流开关已关闭——软挂起退回常规让屏")
                softHold = false
            }
            return false
        }
        val current = sender
        if (current == null || !current.isRunning || !screenNegotiated) return false
        if (!owned) {
            if (softHold) return true
            softHold = true
            screenPaused = true
            screenStreaming = false
            waitStartedAtMs = nowMs()
            screenLosses++
            val aliveMs = if (streamStartedAtMs == 0L) -1L else nowMs() - streamStartedAtMs
            log(
                "MODE: 车机占用主屏——保留视频流（实验开关：不 TEARDOWN、不停编码器）" +
                    " · 本轮第 $screenLosses 次让屏" +
                    if (aliveMs >= 0) " · 这次送流已活 ${aliveMs}ms" else "",
            )
            onScreenPaused(true)
            return true
        }
        if (!softHold) return false
        softHold = false
        screenPaused = false
        screenStreaming = true
        val waited = if (waitStartedAtMs == 0L) -1L else nowMs() - waitStartedAtMs
        waitStartedAtMs = 0L
        streamStartedAtMs = nowMs()
        screenResumes++
        log(
            "MODE: 主屏归还——沿用现有视频流（实验开关）· 重发 VideoConfig + 补关键帧 + 重武装 HID" +
                if (waited >= 0) " · 车机占屏 ${waited}ms" else "",
        )
        current.rearmDecoderOnScreenReturn()
        if (!onScreenResumed(current, true)) {
            log("MODE: 沿用现有视频流被拒——退回重申请屏幕流")
            screenStreaming = false
            softHold = false
            if (!retireScreen("沿用视频流被拒")) return true
            resumeScreen()
        }
        return true
    }

    private fun retireScreen(reason: String): Boolean {
        // 本轮流活了多久：车机「给屏→抢屏」的间隔就藏在这个数里。反复在 100~300ms
        // 被杀说明是它一收到我们的帧就出事，而不是用户切走。
        val aliveMs = if (streamStartedAtMs == 0L) -1L else nowMs() - streamStartedAtMs
        streamStartedAtMs = 0L
        waitStartedAtMs = nowMs()
        softHold = false
        screenLosses++
        log(
            "MODE: 停止屏幕流（$reason）· 本轮第 $screenLosses 次让屏" +
                if (aliveMs >= 0) " · 这次送流活了 ${aliveMs}ms" else "",
        )
        screenPaused = true
        screenStreaming = false
        onScreenPaused(false)
        sender?.stop()
        sender = null
        screenNegotiated = false
        if (!client.teardownStream(TYPE_SCREEN, displayUuid)) {
            if (alive()) fail("主屏资源让出时 TEARDOWN 失败")
            return false
        }
        return alive()
    }

    private fun resumeScreen() {
        val screen = client.runScreenStream(sessionKey, displayUuid) ?: return fail("主屏资源归还后 SETUP 失败")
        if (!alive()) return
        val replacement = createSender(screen)
        sender = replacement
        screenNegotiated = true
        if (!(replacement.openDataPort() && client.record() && replacement.startPassthrough())) {
            return fail("主屏资源归还后视频通道未能恢复")
        }
        if (!onScreenResumed(replacement, false)) {
            retireScreen("屏幕恢复挂载被拒")
            return
        }
        val waited = if (waitStartedAtMs == 0L) -1L else nowMs() - waitStartedAtMs
        waitStartedAtMs = 0L
        streamStartedAtMs = nowMs()
        screenResumes++
        log(
            "MODE: 主屏恢复 · 第 $screenResumes 次起流" +
                if (waited >= 0) " · 车机占屏 ${waited}ms" else "",
        )
        screenStreaming = true
        screenPaused = false
    }

    private fun applyAudio() {
        val media = audioIntent.media
        val navigation = audioIntent.navigation
        // 媒体按用户优先级永久取得；导航按后台优先级临时借：可借原车收音机，冲不破车机静音/通话
        if ((media || navigation) && (!client.phoneOwnsAudio || (media && client.phoneBorrowsAudio))) {
            val acquired =
                client.acquireAudioResource(
                    if (media) AirPlayResourceState.PRIORITY_USER else AirPlayResourceState.PRIORITY_BACKGROUND,
                    temporary = !media,
                )
            log("AUDIO-INTENT: media=$media navigation=$navigation acquired=$acquired ${client.resourceDescription}")
        }
        if (!media && !navigation && client.phoneBorrowsAudio) {
            // 导航播报结束：先停音频流，再把临时借用的主音频还给车机
            if (audioActive) audioActive = onAudioOwnership(false)
            if (!audioActive) client.releaseAudioBorrow()
        }
        val wanted = client.phoneOwnsAudio && (audioActive || media || navigation)
        if (wanted != audioActive) audioActive = onAudioOwnership(wanted)
    }

    companion object {
        const val TYPE_SCREEN = 110L

        /**
         * 车机拿走主屏时保留视频流（实验开关，默认关；持久化见 `DisplayPrefs`）。
         *
         * 放静态字段是因为它要能**即时生效**（连线中拨开关就改变下一轮处置），
         * 与 `CarLifeHijack.adaptiveTouchDomain` 同一套做法。
         */
        @Volatile
        var keepScreenStreamOnTake: Boolean = false
    }
}
