package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.108「已保存目标直达连接」决策内核的单测。
 *
 * 背景（实车证据，见 DirectConnectPolicy 注释）：
 *  - 2026-11-23 晚真车机被 SDP 误判拉黑 3 分钟；
 *  - 2026-11-24 19:31 bondedDevices 快照瞬时残缺导致空转；
 *  - 17PM 配对了多台设备，旧实现每轮全部白查。
 */
class DirectConnectPolicyTest {
    @Test
    fun `有保存目标时走直达模式`() {
        val policy = DirectConnectPolicy()
        assertEquals(
            DirectConnectPolicy.CandidateMode.DIRECT,
            policy.candidateMode(hasSavedTarget = true, softFallbackActive = false, forAddress = null),
        )
    }

    @Test
    fun `无保存目标时保持原有列表遍历行为`() {
        val policy = DirectConnectPolicy()
        assertEquals(
            DirectConnectPolicy.CandidateMode.LEGACY,
            policy.candidateMode(hasSavedTarget = false, softFallbackActive = false, forAddress = null),
        )
    }

    @Test
    fun `软回退激活时保持原有列表遍历行为`() {
        val policy = DirectConnectPolicy()
        assertEquals(
            DirectConnectPolicy.CandidateMode.LEGACY,
            policy.candidateMode(hasSavedTarget = true, softFallbackActive = true, forAddress = null),
        )
    }

    @Test
    fun `恢复补连按地址过滤走原有路径`() {
        val policy = DirectConnectPolicy()
        assertEquals(
            DirectConnectPolicy.CandidateMode.LEGACY,
            policy.candidateMode(hasSavedTarget = true, softFallbackActive = false, forAddress = "AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `directSdpChecked_首轮查_后续不查`() {
        val policy = DirectConnectPolicy()
        val mode = policy.candidateMode(hasSavedTarget = true, softFallbackActive = false, forAddress = null)
        assertTrue(policy.shouldRunSdpGate(mode))
        policy.noteDirectSdpDone()
        assertFalse(policy.shouldRunSdpGate(mode))
        // reset()（即每次 start()）后重新回到首轮状态。
        policy.reset()
        assertTrue(policy.shouldRunSdpGate(mode))
    }

    @Test
    fun `LEGACY 模式不适用首轮 SDP 标志`() {
        val policy = DirectConnectPolicy()
        assertFalse(policy.shouldRunSdpGate(DirectConnectPolicy.CandidateMode.LEGACY))
    }

    @Test
    fun `连续 socket 失败达到阈值才触发 bondedDevices 兜底复查_且一次失败串只查一次`() {
        val policy = DirectConnectPolicy(bondedRecheckAfterRounds = 3)
        policy.noteRoundOutcome(true)
        policy.noteRoundOutcome(true)
        assertFalse(policy.shouldRecheckBonded())
        policy.noteRoundOutcome(true)
        assertTrue(policy.shouldRecheckBonded())
        // 失败串里第二次不再触发（一次串只查一次）。
        policy.noteRoundOutcome(true)
        assertFalse(policy.shouldRecheckBonded())
        // 成功（或其它失败形态）断串后重新计数。
        policy.noteRoundOutcome(false)
        assertFalse(policy.shouldRecheckBonded())
        policy.noteRoundOutcome(true)
        assertFalse(policy.shouldRecheckBonded())
    }

    @Test
    fun `软回退_有保存目标时不启用_无保存目标时允许`() {
        val policy = DirectConnectPolicy()
        assertFalse(policy.shouldEnableSoftFallback(hasSavedTarget = true))
        assertTrue(policy.shouldEnableSoftFallback(hasSavedTarget = false))
    }

    @Test
    fun `reset 清空全部直达状态`() {
        val policy = DirectConnectPolicy(bondedRecheckAfterRounds = 1)
        policy.noteDirectSdpDone()
        policy.noteRoundOutcome(true)
        assertTrue(policy.shouldRecheckBonded())
        policy.reset()
        assertFalse(policy.directSdpChecked)
        val mode = policy.candidateMode(hasSavedTarget = true, softFallbackActive = false, forAddress = null)
        assertTrue(policy.shouldRunSdpGate(mode))
        assertFalse(policy.shouldRecheckBonded())
    }
}
