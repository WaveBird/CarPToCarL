package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * requestUI fighting 防护（1.2.107，P2-12）单测。
 *
 * 实车证据：/tmp/andlog/1124b/CarPToCarL-log-20261124-194036.txt 倒车段——
 * 19:35:43 车机 take permanent → 19:35:44.406/45.396/45.630/45.824 400ms 内
 * 3 次 requestUI，每次都「让出→拿回」→ 19:35:47 车机再 take → 19:35:50
 * 车机拆 BT 链路 + control EOF，会话死亡。
 */
class RequestUiFightingGuardTest {
    private class FakeClock {
        var now = 0L

        fun clock(): Long = now

        fun advance(ms: Long) = let { now += ms }
    }

    private fun guard(clock: FakeClock) = RequestUiFightingGuard(clock::clock)

    // ---- ① requestUI 3s 去重 ----

    @Test
    fun `同一 URL 3 秒内重复 requestUI 只处理第一次`() {
        val clock = FakeClock()
        val g = guard(clock)
        assertTrue("第一次必须处理", g.shouldProcessRequestUi("nowplaying:"))
        clock.advance(400)
        assertFalse("400ms 后的第二次要被去重（实车重绘风暴）", g.shouldProcessRequestUi("nowplaying:"))
        clock.advance(300)
        assertFalse("700ms 后的第三次也要被去重", g.shouldProcessRequestUi("nowplaying:"))
        clock.advance(2_500) // 距第一次共 3.2s
        assertTrue("过了 3s 冷却窗口应重新处理", g.shouldProcessRequestUi("nowplaying:"))
    }

    @Test
    fun `去重按 URL 区分——不同页面互不冷却`() {
        val clock = FakeClock()
        val g = guard(clock)
        assertTrue(g.shouldProcessRequestUi("nowplaying:"))
        clock.advance(100)
        assertTrue("另一页面的 requestUI 不该被上一页冷却误伤", g.shouldProcessRequestUi("music:"))
        assertFalse("但同一页面在窗口内仍要去重", g.shouldProcessRequestUi("nowplaying:"))
    }

    @Test
    fun `URL 为空也按去重窗口处理`() {
        val clock = FakeClock()
        val g = guard(clock)
        assertTrue(g.shouldProcessRequestUi(""))
        assertFalse(g.shouldProcessRequestUi(""))
        clock.advance(3_000)
        assertTrue(g.shouldProcessRequestUi(""))
    }

    // ---- ② 永久归属=车机时不执行让出拿回 ----

    @Test
    fun `永久归属在车机时决定跳过让出拿回`() {
        val clock = FakeClock()
        val g = guard(clock)
        assertEquals(
            RequestUiFightingGuard.RetakeDecision.SKIP_PERMANENT_CAR,
            g.decideRetake(screenPermanentOwnerIsCar = true),
        )
        assertFalse("跳过后不算已发布跳变", g.surrenderRetakeBanned)
    }

    @Test
    fun `永久归属在手机且首次允许让出拿回`() {
        val clock = FakeClock()
        val g = guard(clock)
        assertEquals(
            RequestUiFightingGuard.RetakeDecision.PROCEED,
            g.decideRetake(screenPermanentOwnerIsCar = false),
        )
    }

    // ---- ③ 连续 2 次收回后禁用跳变 + 节流 ----

    @Test
    fun `连续两次拿回后被车机收回则本会话禁用让出拿回`() {
        val clock = FakeClock()
        val g = guard(clock)
        // 第一次跳变
        assertEquals(RequestUiFightingGuard.RetakeDecision.PROCEED, g.decideRetake(false))
        g.onRetakePublished()
        // 车机复述仍是车机 → 第 1 次收回
        assertTrue(g.onCarEcho(screenPermanentOwnerIsCar = true))
        assertFalse("1 次还不禁用", g.surrenderRetakeBanned)
        // 第二次跳变（过了节流窗口）
        clock.advance(2_000)
        assertEquals(RequestUiFightingGuard.RetakeDecision.PROCEED, g.decideRetake(false))
        g.onRetakePublished()
        assertTrue(g.onCarEcho(screenPermanentOwnerIsCar = true))
        assertTrue("连续 2 次收回即禁用（实车 19:35:44→19:35:47 两轮）", g.surrenderRetakeBanned)
        // 第三次 requestUI：即便永久归属已回到手机，也不再跳变
        clock.advance(10_000)
        assertEquals(RequestUiFightingGuard.RetakeDecision.SKIP_BANNED, g.decideRetake(false))
    }

    @Test
    fun `车机复述确认归手机则收回计数清零`() {
        val clock = FakeClock()
        val g = guard(clock)
        g.decideRetake(false)
        g.onRetakePublished()
        assertTrue(g.onCarEcho(true))
        clock.advance(2_000)
        g.decideRetake(false)
        g.onRetakePublished()
        // 复述确认归手机 → 清零，不禁用
        assertFalse(g.onCarEcho(false))
        assertFalse(g.surrenderRetakeBanned)
        clock.advance(2_000)
        g.decideRetake(false)
        g.onRetakePublished()
        assertTrue(g.onCarEcho(true))
        assertFalse("清零后只有 1 次，不应禁用", g.surrenderRetakeBanned)
    }

    @Test
    fun `两次让出拿回跳变之间至少间隔 1_5 秒`() {
        val clock = FakeClock()
        val g = guard(clock)
        assertEquals(RequestUiFightingGuard.RetakeDecision.PROCEED, g.decideRetake(false))
        g.onRetakePublished()
        clock.advance(1_000) // 实车 requestUI 间隔只有 200~990ms
        assertEquals(
            "1s 内的第二次跳变要被节流",
            RequestUiFightingGuard.RetakeDecision.SKIP_THROTTLED,
            g.decideRetake(false),
        )
        clock.advance(1_000) // 距上次跳变共 2s
        assertEquals(RequestUiFightingGuard.RetakeDecision.PROCEED, g.decideRetake(false))
    }

    @Test
    fun `没发布过跳变时车机复述不触发收回计数`() {
        val clock = FakeClock()
        val g = guard(clock)
        assertFalse("未等复述就不该记账", g.onCarEcho(true))
        assertFalse(g.surrenderRetakeBanned)
    }

    // ---- 复刻实车 194036 倒车段时序：防护应把 fighting 整段消掉 ----

    @Test
    fun `复刻实车 193544-193547 时序——只有第一次 requestUI 执行跳变`() {
        val clock = FakeClock()
        val g = guard(clock)
        // 19:35:44.406 第一次 requestUI（车机刚 take permanent）
        assertTrue(g.shouldProcessRequestUi("nowplaying:"))
        // 申请把 entity 抢回手机，但永久归属仍是车机 → A2 拦下，不让出拿回
        assertEquals(
            RequestUiFightingGuard.RetakeDecision.SKIP_PERMANENT_CAR,
            g.decideRetake(screenPermanentOwnerIsCar = true),
        )
        // 19:35:45.396 / .630 / .824 重绘风暴：全部被 3s 去重吃掉
        clock.advance(1_000)
        assertFalse(g.shouldProcessRequestUi("nowplaying:"))
        clock.advance(200)
        assertFalse(g.shouldProcessRequestUi("nowplaying:"))
        clock.advance(200)
        assertFalse(g.shouldProcessRequestUi("nowplaying:"))
        // 19:35:47.504 车机再 take permanent（车机复述仍是车机）
        clock.advance(1_700)
        assertTrue(g.shouldProcessRequestUi("nowplaying:"))
        assertEquals(
            "即便被放行，永久=车机也拦下跳变——整个倒车段零次让出拿回",
            RequestUiFightingGuard.RetakeDecision.SKIP_PERMANENT_CAR,
            g.decideRetake(true),
        )
    }
}
