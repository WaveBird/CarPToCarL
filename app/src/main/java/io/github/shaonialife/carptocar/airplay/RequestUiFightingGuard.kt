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
 * 拆会话——这是倒车后进不去 CarPlay 的直接原因。本类把防护做成可单测的纯状态机：
 *
 * 1. **URL 去重冷却**：同一 URL 3 秒内只处理第一次（实车 400ms 内 3 次是 HMI 重绘风暴）；
 * 2. **永久归属复核**：永久=车机时不执行让出拿回，改为纯等待；
 * 3. **跳变节流 + 收回禁用**：两次跳变至少间隔 1.5s；连续 2 次「拿回后车机改判收回」
 *    本会话禁用跳变（`surrenderRetakeBanned`）。
 *
 * ## 1.2.115 补充：「屏在车机手里」不是「不许要回来」
 *
 * 1.2.107 把上面的第 2 条也套到了「申请」上（`screenRequestFromCarUi && 永久=车机` →
 * 不申请），于是车机 take 走主屏之后（倒车影像/HMI 切原生页）用户再从车机点 Apple CarPlay
 * 时，我们连申请都不发——屏永远回不来，车机上只剩声音（2026-11-26 08:50:45~08:52:29 实车
 * 日志：requestUI 连发 10 次，永久归属始终在手机反而全部走跳变，说明这台车给的是
 * `takeConstraint=500`，本来就允许用户发起的申请拿回）。现在把两条路彻底分开：
 *
 * - 屏**还在手机手里**（entity=手机）→ 走 [decideRetake]（让出→拿回跳变，制造归属边沿）；
 * - 屏**已被车机拿走**（entity=车机）→ 走 [decideClaim]（直接申请，不做让出），
 *   节流退避 [CLAIM_BACKOFF_MIN_MS] 起、每次被车机拿走翻倍到 [CLAIM_BACKOFF_MAX_MS]。
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
    private var bannedAtMs = -1L

    // ---- 1.2.115：申请回主屏（claim）路径的节流 ----
    /// 「车机手里拿着屏、下发 requestUI」时我们直接**申请**（不再让出），这条路的节流。
    private var lastClaimAtMs = -1L
    private var claimAwaitingCarEcho = false
    private var claimBackoffMs = CLAIM_BACKOFF_MIN_MS

    /** 连续 2 次「拿回成功但车机改判收回」后进入禁用冷却（超过 [RETAKE_BAN_COOLDOWN_MS] 后自愈解禁）。 */
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
        // 防御：超出上限时只淘汰已过期的条目（而非 clear 全部），保留仍在冷却中的 URL。
        // 旧实现 clear() 会把尚在冷却期的 URL 也一并清掉，导致去重窗口被击穿。
        if (lastHandledByUrl.size > MAX_TRACKED_URLS) {
            val expiredBefore = now - REQUEST_UI_DEDUPE_WINDOW_MS
            lastHandledByUrl.entries.removeAll { it.value < expiredBefore }
            // 淘汰后仍然超限（极端情况：短时间内涌入 >8 个不同 URL 且全都在冷却中），
            // 移除最早的那个，确保不会无限增长。
            if (lastHandledByUrl.size > MAX_TRACKED_URLS) {
                val oldest = lastHandledByUrl.entries.minByOrNull { it.value }
                oldest?.let { lastHandledByUrl.remove(it.key) }
            }
        }
        return true
    }

    /**
     * 「车机拿着主屏、下发 requestUI」时**申请回**的复核（1.2.115）。
     *
     * 与 [decideRetake] 的区别：那条路是「屏本来就在手机手里，做一次让出→拿回制造归属边沿」，
     * 所以永久归属在车机时**必须放弃**（让出动作会被 HMI 读成「手机放弃」）。这条路相反：
     * 车机已经把屏拿走了（倒车影像/HMI 切原生页），它的 `requestUI` 就是「请把画面给我」
     * 的显式信号——此时不申请，屏永远回不到手机，车机上就只剩声音。
     *
     * 1.2.107 曾把两条路合并成一条判定（`screenRequestFromCarUi && 永久=车机` → 拒绝申请），
     * 这条过度修正正是「倒车后点 Apple CarPlay 进不去、只有声音」的直接原因：
     * 车机 take 时给的是 `takeConstraint=500`，语义就是「用户发起的申请（PRIORITY_USER）
     * 允许拿回」——它自己就把门留着了。
     *
     * 唯一的限制是**节流/退避**：每次「申请成功但车机又拿回去」（[onCarEcho]）把退避翻倍，
     * 上限 [CLAIM_BACKOFF_MAX_MS]。这样既不会把屏锁死在车机手里，也不会变成争夺风暴。
     */
    fun decideClaim(): RetakeDecision {
        // 让出拿回被禁用不影响「申请」——那是两条不同的动作，申请本身不做归属跳变。
        // 但仍然要过退避，避免车机每次秒收回时我们每 100ms 撞一次。
        liftRetakeBanIfCooledDown()
        val sinceLast = if (lastClaimAtMs < 0) Long.MAX_VALUE else nowMs() - lastClaimAtMs
        if (sinceLast < claimBackoffMs) {
            log(
                "requestUI 申请回主屏距上次仅 ${sinceLast}ms（<${claimBackoffMs}ms 退避）——本轮跳过，只补关键帧",
            )
            return RetakeDecision.SKIP_THROTTLED
        }
        return RetakeDecision.PROCEED
    }

    /** 跳变禁用满 [RETAKE_BAN_COOLDOWN_MS] 冷却后自愈解禁（两条判定路径共用）。 */
    private fun liftRetakeBanIfCooledDown() {
        if (!surrenderRetakeBanned) return
        if (bannedAtMs < 0L || nowMs() - bannedAtMs < RETAKE_BAN_COOLDOWN_MS) return
        surrenderRetakeBanned = false
        retakeOverturnedCount = 0
        bannedAtMs = -1L
        log("requestUI 让出拿回禁用已满 ${RETAKE_BAN_COOLDOWN_MS / 1000}s 冷却——自愈解禁")
    }

    /** 申请回主屏已发布（无论车机是否接受）后调用：开始等车机下一次 changeModes 复述确认。 */
    fun onClaimPublished() {
        lastClaimAtMs = nowMs()
        claimAwaitingCarEcho = true
    }

    /**
     * 让出拿回前的复核（修复 A2/A3）。
     *
     * @param screenPermanentOwnerIsCar 当前主屏**永久**归属是否在车机
     *   （entity 可能已被 requestUI 申请翻回手机，但 permanentEntity 只有车机发
     *   transferType=2 才会变——这正是 fighting 的判定输入）。
     */
    fun decideRetake(screenPermanentOwnerIsCar: Boolean): RetakeDecision {
        liftRetakeBanIfCooledDown()
        if (surrenderRetakeBanned) return RetakeDecision.SKIP_BANNED
        val now = nowMs()
        if (screenPermanentOwnerIsCar) {
            log("requestUI 时主屏永久归属在车机——不执行让出拿回（避免 fighting），等待车机复述或 HMI 操作")
            return RetakeDecision.SKIP_PERMANENT_CAR
        }
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
        // 申请回（claim）路径的退避：车机把屏又拿回去就翻倍，确认归手机就复位。
        // 与让出拿回的计数器分开——申请是「正当请求」，不该被跳变的熔断连坐。
        if (claimAwaitingCarEcho) {
            claimAwaitingCarEcho = false
            if (screenPermanentOwnerIsCar) {
                claimBackoffMs = (claimBackoffMs * 2).coerceAtMost(CLAIM_BACKOFF_MAX_MS)
                log(
                    "requestUI 申请回后车机又拿走主屏——下次申请退避延长到 ${claimBackoffMs}ms" +
                        "（避免每 100ms 撞一次）",
                )
            } else {
                claimBackoffMs = CLAIM_BACKOFF_MIN_MS
            }
        }
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
            if (retakeOverturnedCount >= RETAKE_BAN_THRESHOLD) {
                surrenderRetakeBanned = true
                bannedAtMs = nowMs()
            }
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

        /** 禁用跳变后的自愈冷却时间：避免整趟行程永久无法通过车机点击 CarPlay 切回。 */
        const val RETAKE_BAN_COOLDOWN_MS = 60_000L

        /**
         * 申请回主屏的最小间隔（1.2.115）。
         *
         * 取 1.5s 与跳变节流同量级：车机的 requestUI 在 HMI 重绘时 400ms 内会来 3 次
         * （URL 去重那层兜住同 URL），换页面时 URL 会变，靠这一层兜住不同 URL 的连发。
         */
        const val CLAIM_BACKOFF_MIN_MS = 1_500L

        /** 申请回主屏的退避上限：车机反复拿走时最长 30s 才再试一次（不锁死、不风暴）。 */
        const val CLAIM_BACKOFF_MAX_MS = 30_000L

        private const val MAX_TRACKED_URLS = 8
    }
}
