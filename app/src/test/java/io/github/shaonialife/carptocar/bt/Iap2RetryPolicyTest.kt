package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BT 重试策略单测（2026-11-24 修复 #1/#2）。
 *
 * 锁三件事：
 *  1. failStreak 新判据：拿到端点归零 / 未拿到继续爬梯（「活过 3 秒就归零」的病根）；
 *  2. NUDGE 下限 ≥ 退避起点（车机 mediaDisconnectTimeout=15s + 余量）；
 *  3. 阶梯本身 16→20→24→30→45→60。
 */
class Iap2RetryPolicyTest {
    // ---- 修复 #1：failStreak 判据 ----

    @Test
    fun `拿到端点的轮次归零`() {
        // 连败 5 轮后终于拿到 0x4301 → 归零，下一轮回到 16s 基础间隔。
        assertEquals(0, Iap2RetryPolicy.nextStreak(prevStreak = 5, gotEndpoint = true))
        assertEquals(0, Iap2RetryPolicy.nextStreak(prevStreak = 0, gotEndpoint = true))
    }

    @Test
    fun `没拿到端点继续爬梯——活过 3 秒不再归零`() {
        // MGU-55-J 失败形态：每轮连上、活 20+ 秒、被拆链、0x4301 从未到手。
        // 旧判据会把它们全部归零（阶梯永不生效）；新判据逐轮 +1。
        assertEquals(1, Iap2RetryPolicy.nextStreak(prevStreak = 0, gotEndpoint = false))
        assertEquals(2, Iap2RetryPolicy.nextStreak(prevStreak = 1, gotEndpoint = false))
        assertEquals(3, Iap2RetryPolicy.nextStreak(prevStreak = 2, gotEndpoint = false))
    }

    @Test
    fun `阶梯逐档推进且封顶 60 秒`() {
        assertEquals(16_000L, Iap2RetryPolicy.backoffMillis(1))
        assertEquals(20_000L, Iap2RetryPolicy.backoffMillis(2))
        assertEquals(24_000L, Iap2RetryPolicy.backoffMillis(3))
        assertEquals(30_000L, Iap2RetryPolicy.backoffMillis(4))
        assertEquals(45_000L, Iap2RetryPolicy.backoffMillis(5))
        assertEquals(60_000L, Iap2RetryPolicy.backoffMillis(6))
        // 超出阶梯长度后固定 60 秒
        assertEquals(60_000L, Iap2RetryPolicy.backoffMillis(99))
    }

    // ---- 修复 #2：NUDGE 下限 ----

    @Test
    fun `NUDGE 截断窗不低于退避起点`() {
        // 车机 mediaDisconnectTimeout=15s：NUDGE 把退避截到 16s 以下等于替车机续计时器。
        assertTrue(
            "NUDGE 窗必须 ≥ 阶梯起点（16s = 15s + 1s 余量）",
            Iap2RetryPolicy.NUDGE_MAX_WAIT_MILLIS >= Iap2RetryPolicy.RETRY_LADDER.first(),
        )
        assertEquals(16_000L, Iap2RetryPolicy.NUDGE_MAX_WAIT_MILLIS)
    }

    @Test
    fun `睡眠硬下限取 NUDGE 与阶梯起点的较大者`() {
        assertEquals(
            maxOf(Iap2RetryPolicy.NUDGE_MAX_WAIT_MILLIS, Iap2RetryPolicy.RETRY_LADDER.first()),
            Iap2RetryPolicy.sleepFloorMillis(),
        )
        assertTrue(Iap2RetryPolicy.sleepFloorMillis() >= 16_000L)
    }
}
