package io.github.shaonialife.carptocar.bt

/**
 * BT 连接循环的**纯策略**（抽出便于单测，2026-11-24 修复 #1/#2）。
 *
 * ## 为什么抽出来
 *
 * failStreak 判据与 NUDGE 下限都埋在 [Iap2BluetoothClient.connectLoop] 的私有逻辑里，
 * 2026-11-23 审查（docs/code-audit-20261124.md Q1/Q2）发现两个判据互相咬坏：
 * 「活过 3 秒就归零」让退避阶梯在真车从未生效，而 NUDGE 又能把退避截到 10 秒，
 * 两边一起把车机固件的 `mediaDisconnectTimeout=15s` 计时器一次次续上——车机永远停在
 * `keepIAP2Session=1` 的旧会话上。判据改对了但没法单测，下次重构还会改回去，所以抽成纯对象。
 */
object Iap2RetryPolicy {
    /**
     * 连续失败（本轮**没拿到 0x4301/端点**才算失败）时的退避阶梯：16s → 20s → 24s → 30s → 45s → 60s。
     *
     * 起点必须 ≥ 16 秒：车机 `mediaDisconnectTimeout = 15000ms`（4580 固件定值），
     * 蓝牙断开后车机要等满 15 秒才把设备判成「已移除」；更短的重试等于每次都把
     * 那个计时器续上，车机永远走「重开旧会话」分支而不启动 projection。
     */
    val RETRY_LADDER = longArrayOf(16_000L, 20_000L, 24_000L, 30_000L, 45_000L, 60_000L)

    /**
     * 「车机靠近 → 提前重试」的最长等待（NUDGE 把本轮退避截到这里为止）。
     *
     * 10s → 16s（2026-11-24 修复 #2）：截断值一旦低于 [RETRY_LADDER] 起点，车机 ACL 抖动
     * 时的每一次抢占都会把重试间隔压到 15 秒以下，等于替车机续 `mediaDisconnectTimeout`
     * （15s + 1s 余量 = 16s）。NUDGE 下限因此与退避起点取同一个值。
     */
    const val NUDGE_MAX_WAIT_MILLIS = 16_000L

    /**
     * 任意退避睡眠的**硬下限**：`max(NUDGE 抢占窗, 退避阶梯起点)`。
     *
     * 两者如今同为 16 秒，但语义分开写死在这里：将来若有人把 NUDGE 调小、或把阶梯
     * 起点调大，这条下限保证「退避被截断后也不会短过车机的设备移除计时器」。
     */
    fun sleepFloorMillis(): Long = maxOf(NUDGE_MAX_WAIT_MILLIS, RETRY_LADDER.first())

    /** 第 [streak] 次连续失败的退避间隔（1 起；0/负数按首次处理）。 */
    fun backoffMillis(streak: Int): Long = RETRY_LADDER[(streak - 1).coerceIn(0, RETRY_LADDER.size - 1)]

    /**
     * 一轮会话结束后 failStreak 的走向——**修复 #1 的核心判据**。
     *
     * 旧判据「会话活过 3 秒就归零」在 MGU-55-J 上永不生效：该车机的失败形态是
     * 「连上、走完 iAP2 身份、22 秒后被车机拆链、**0x4301 从未到手**」，每一轮都
     * 活过 3 秒，于是阶梯永远停在第一档。新判据：本轮拿到过端点（0x4301）才归零，
     * 否则继续爬梯（实车日志 `-192522.txt`：8 轮 5 分钟等长 → 16/20/24/30/45/60）。
     *
     * @param prevStreak 本轮开始前的连续失败轮数
     * @param gotEndpoint 本轮是否拿到过 0x4301/端点（[Iap2SenderSession.startSessionReceived]）
     */
    fun nextStreak(
        prevStreak: Int,
        gotEndpoint: Boolean,
    ): Int = if (gotEndpoint) 0 else prevStreak + 1
}
