package io.github.shaonialife.carptocar.bt

/**
 * 1.2.108「已保存目标直达连接」的**可测决策内核**（纯 Kotlin，不依赖 Android 框架）。
 *
 * 背景（实车证据，见 Iap2BluetoothClient 注释）：
 *  - 2026-11-23 晚：真车机被 SDP 误判拉黑 3 分钟（`SDP 明确不含 iAP2 服务`）——
 *    根因是每轮遍历已配对列表 + 对每台设备做 SDP 查询的组合；
 *  - 2026-11-24 19:36 后：`CX_BTFC4BBCD57C61` 等 WiFi Direct 接口被当拨号候选反复拨；
 *  - 2026-11-24 19:31：`bondedDevices` 快照瞬时残缺导致 App 误判「目标不在已配对列表」空转。
 *
 * 用户结论：App 已选定并保存车机目标时，不应再轮询列表——像 iPhone 一样直奔目标地址。
 * 本类把这条路径里的三个「要不要做」抽成可单测的状态机：
 *  1. [candidateMode]：有保存目标且未软回退 → DIRECT（单元素直达，不查 bondedDevices）；
 *  2. [shouldRunSdpGate]：直达路径首轮做一次 SDP 拿诊断，第 2 轮起跳过直接拨 …caff；
 *  3. [noteRoundOutcome]/[shouldRecheckBonded]：连续 N 轮拨号全是 `read failed/socket closed`
 *     才复查一次 bondedDevices（兜底诊断，只打日志，不影响直达路径）；
 *  4. [shouldEnableSoftFallback]：软回退（放开目标限制去试其它设备）仅当**没有**保存目标
 *     时才允许——有保存目标时永不尝试其它设备（实车 WF-SP700N 教训）。
 */
class DirectConnectPolicy(
    /** 连续多少轮「拨号全部 read failed/socket closed」后做一次 bondedDevices 兜底复查。 */
    val bondedRecheckAfterRounds: Int = 3,
) {
    /** 候选模式：直达（单元素、不查列表）或沿用原有 bondedDevices 遍历。 */
    enum class CandidateMode { DIRECT, LEGACY }

    /**
     * 直达路径「是否已做过首轮 SDP 探测」。每次 [reset]（即每次 start()）清零。
     * 抽成公开可写字段正是为了单测「首轮查、后续不查」（1.2.108 测试点 3）。
     */
    var directSdpChecked: Boolean = false

    /** 连续「拨号失败且原因是 read failed/socket closed」的轮数（直达路径计数）。 */
    private var socketFailStreak: Int = 0

    /** 本段失败串里是否已做过一次 bondedDevices 兜底复查（一次失败串只查一次）。 */
    private var bondedRecheckDone: Boolean = false

    /** 每次 start() 重置全部状态（新配对/换固件/用户手动重启后重新走首轮 SDP）。 */
    fun reset() {
        directSdpChecked = false
        socketFailStreak = 0
        bondedRecheckDone = false
    }

    /**
     * 本轮候选该走直达还是原有列表遍历。
     *
     * @param hasSavedTarget targetProvider() 此刻是否有保存目标
     * @param softFallbackActive 软回退是否已启用（仅在无保存目标场景可能出现）
     * @param forAddress 本次取候选是否是为「指定地址」服务（补连控制链路 recoveryPeers
     *   就走这条）——补连按地址过滤已配对列表，保持 1.2.107 现状，不走直达。
     */
    fun candidateMode(
        hasSavedTarget: Boolean,
        softFallbackActive: Boolean,
        forAddress: String?,
    ): CandidateMode =
        if (hasSavedTarget && !softFallbackActive && forAddress == null) {
            CandidateMode.DIRECT
        } else {
            CandidateMode.LEGACY
        }

    /**
     * 直达路径本轮要不要先过 SDP 闸门：只有**首轮**查（拿诊断信息，实车 2026-11-23
     * 的误拉黑正说明 SDP 结论值得留档），之后一律跳过直接拨 …caff。
     */
    fun shouldRunSdpGate(mode: CandidateMode): Boolean =
        mode == CandidateMode.DIRECT && !directSdpChecked

    /** 首轮 SDP 已做（无论结论 HAS/MISSING/UNKNOWN——显式目标 MISSING 只记日志不跳过）。 */
    fun noteDirectSdpDone() {
        directSdpChecked = true
    }

    /**
     * 记录一轮拨号结果。
     *
     * @param allAttemptsSocketFailed 本轮所有拨号尝试失败、且失败原因是
     *   `read failed` / `socket closed`（链路层失败，不是「被拒」也不是「没服务」）。
     */
    fun noteRoundOutcome(allAttemptsSocketFailed: Boolean) {
        if (allAttemptsSocketFailed) {
            socketFailStreak++
        } else {
            // 成功或失败原因是别的（被拒/SDP 无服务）：失败串断掉，复查机会重开。
            socketFailStreak = 0
            bondedRecheckDone = false
        }
    }

    /**
     * 是否该做一次 bondedDevices 兜底复查：连续 [bondedRecheckAfterRounds] 轮全是
     * `read failed/socket closed`，且这段失败串里还没查过。典型场景：用户在系统
     * 设置里把保存的目标删除配对——直达路径不查列表不会自己发现，靠这里留诊断日志。
     */
    fun shouldRecheckBonded(): Boolean {
        if (socketFailStreak < bondedRecheckAfterRounds || bondedRecheckDone) return false
        bondedRecheckDone = true
        return true
    }

    /**
     * 软回退（目标连续失败后放开限制去试其它设备）此刻是否允许启用。
     * **有保存目标时永远 false**：实车 2026-11-24 教训（索尼 WF-SP700N 被当车机候选
     * 反复拨、还被断音频），用户明确要求「已保存目标就只连它」。
     */
    fun shouldEnableSoftFallback(hasSavedTarget: Boolean): Boolean = !hasSavedTarget
}
