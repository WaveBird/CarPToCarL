package io.github.shaonialife.carptocar.airplay

/**
 * requestUI 归属争夺（fighting）防护（1.2.107，P2-12）。
 *
 * ## 实车证据（/tmp/andlog/1124b/CarPToCarL-log-20261124-194036.txt）
 *
 * ```
 * 19:35:43.404  车机 changeModes transferType=1 takeConstraint=500（永久 take）→ 主屏=车机/永久=车机
 * 19:35:44.406  requestUI → 我们「申请主屏 + 让出→拿回」跳变（接受=true）
 * 19:35:45.396 / 45.630 / 45.824  requestUI 又 3 次 → 每次都再跳变
 * 19:35:47.504  车机再 take permanent（把屏拿回去）
 * 19:35:50.294  车机拆 BT 链路 + control EOF → 会话死亡，之后 4 分钟车机不再连入
 * ```
 *
 * 「让出→拿回」在车机永久持有期间变成了归属争夺：每次拿回都触发车机立即再拿走并最终
 * 拆会话——这是倒车后进不去 CarPlay 的直接原因。本类把三层防护做成可单测的纯状态机：
 *
 * 1. **URL 去重冷却**：同一 URL 3 秒内只处理第一次（实车 400ms 内 3 次是 HMI 重绘风暴）；
 * 2. **永久归属复核**：永久=车机时不执行让出拿回，改为纯等待；
 * 3. **跳变节流 + 收回禁用**：两次跳变至少间隔 1.5s；连续 2 次「拿回后车机改判收回」
 *    本会话禁用跳变（`surrenderRetakeBanned`）。
 *
 * 线程模型：所有方法都只在调用方已有的串行上下文（CarPlayResourceStreams 工作线程 /
 * SessionController 事件读线程）上调用，实例内部不做加锁。
 */
internal class RequestUiFightingGuard(
    /** 单调时钟，可注入便于单测。 */
    private val nowMs: () -> Long,
    private val log: (String) -> Unit = {},
) {
    enum class RetakeDecision {
        /** 永久归属在车机：不执行让出拿回，纯等待车机复述或 HMI 操作。 */
        SKIP_PERMANENT_CAR,

        /** 距上次跳变不足 [RETAKE_JUMP_MIN_INTERVAL_MS]：节流跳过（只补关键帧）。 */
        SKIP_THROTTLED,

        /** 本会话已被禁用（连续收回达阈值）：纯等待。 */
        SKIP_BANNED,

        /** 允许执行让出拿回（调用方随后必须调 [onRetakePublished]）。 */
        PROCEED,
    }

    // ---- A1：URL 去重 ----
    private val lastHandledByUrl = linkedMapOf<String, Long>()

    // ---- A3：跳变节流与收回计数 ----
    private var lastRetakeJumpAtMs = -1L
    private var retakeOverturnedCount = 0
    private var retakeAwaitingCarEcho = false

    /** 连续 2 次「拿回成功但车机改判收回」后本会话禁用让出拿回。 */
    @Volatile
    var surrenderRetakeBanned = false
        private set

    /**
     * requestUI 去重（修复 A1）：同一 URL [REQUEST_UI_DEDUPE_WINDOW_MS] 内只处理第一次。
     *
     * @return true = 本次应处理（并已记账）；false = 冷却中，忽略并已打日志。
     */
    fun shouldProcessRequestUi(url: String): Boolean {
        val now = nowMs()
        val key = url.trim()
        val last = lastHandledByUrl[key]
        if (last != null && now - last < REQUEST_UI_DEDUPE_WINDOW_MS) {
            log(
                "requestUI 去重：${REQUEST_UI_DEDUPE_WINDOW_MS / 1000}s 内重复 url=${key.ifEmpty { "(-)" }} 已忽略" +
                    "（实车 19:35:44 400ms 内 3 次重绘风暴，逐条处理就是 fighting）",
            )
            return false
        }
        lastHandledByUrl[key] = now
        // 防御：URL 种类有限，超出就清一轮，免得被未知 URL 撑大。
        if (lastHandledByUrl.size > MAX_TRACKED_URLS) {
            lastHandledByUrl.clear()
            lastHandledByUrl[key] = now
        }
        return true
    }

    /**
     * 让出拿回前的复核（修复 A2/A3）。
     *
     * @param screenPermanentOwnerIsCar 当前主屏**永久**归属是否在车机
     *   （entity 可能已被 requestUI 申请翻回手机，但 permanentEntity 只有车机发
     *   transferType=2 才会变——这正是 fighting 的判定输入）。
     */
    fun decideRetake(screenPermanentOwnerIsCar: Boolean): RetakeDecision {
        if (surrenderRetakeBanned) return RetakeDecision.SKIP_BANNED
        if (screenPermanentOwnerIsCar) {
            log("requestUI 时主屏永久归属在车机——不执行让出拿回（避免 fighting），等待车机复述或 HMI 操作")
            return RetakeDecision.SKIP_PERMANENT_CAR
        }
        val now = nowMs()
        val sinceLast = if (lastRetakeJumpAtMs < 0) Long.MAX_VALUE else now - lastRetakeJumpAtMs
        if (sinceLast < RETAKE_JUMP_MIN_INTERVAL_MS) {
            log(
                "requestUI 让出拿回距上次仅 ${sinceLast}ms（<${RETAKE_JUMP_MIN_INTERVAL_MS}ms）——" +
                    "本轮跳变节流跳过，只补关键帧",
            )
            return RetakeDecision.SKIP_THROTTLED
        }
        return RetakeDecision.PROCEED
    }

    /**
     * 让出拿回已发布（无论车机是否接受）后调用：开始等车机下一次 changeModes 复述确认。
     * 确认前 UI/日志应按「暂归手机（车机可能收回）」对待——实车 19:35:44.541 接受=true、
     * 1.9s 后车机就再 take 收回。
     */
    fun onRetakePublished() {
        lastRetakeJumpAtMs = nowMs()
        retakeAwaitingCarEcho = true
    }

    /**
     * 车机 changeModes 复述后的确认（修复 A4）：复述仍是车机就计一次「收回」，
     * 连续 [RETAKE_BAN_THRESHOLD] 次本会话禁用让出拿回；复述确认归手机则清零。
     *
     * @param screenPermanentOwnerIsCar 复述后主屏永久归属是否在车机
     * @return true = 本次计了一次收回（诊断用）
     */
    fun onCarEcho(screenPermanentOwnerIsCar: Boolean): Boolean {
        if (!retakeAwaitingCarEcho) return false
        retakeAwaitingCarEcho = false
        if (screenPermanentOwnerIsCar) {
            retakeOverturnedCount++
            log(
                "requestUI 让出拿回后车机复述仍是车机——第 $retakeOverturnedCount 次收回" +
                    if (retakeOverturnedCount >= RETAKE_BAN_THRESHOLD) {
                        "（达阈值，本会话禁用让出拿回，改为纯等待）"
                    } else {
                        ""
                    },
            )
            if (retakeOverturnedCount >= RETAKE_BAN_THRESHOLD) surrenderRetakeBanned = true
            return true
        }
        retakeOverturnedCount = 0
        log("requestUI 让出拿回后车机复述确认主屏仍归手机——跳变稳定")
        return false
    }

    companion object {
        /** 修复 A1：同一 URL 的 requestUI 去重窗口。 */
        const val REQUEST_UI_DEDUPE_WINDOW_MS = 3_000L

        /** 修复 A2：连续 2 次「拿回后车机收回」即本会话禁用让出拿回。 */
        const val RETAKE_BAN_THRESHOLD = 2

        /** 修复 A3：两次让出拿回跳变之间的最小间隔。 */
        const val RETAKE_JUMP_MIN_INTERVAL_MS = 1_500L

        private const val MAX_TRACKED_URLS = 8
    }
}
