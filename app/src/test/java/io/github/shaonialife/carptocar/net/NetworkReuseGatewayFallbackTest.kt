package io.github.shaonialife.carptocar.net

import io.github.shaonialife.carptocar.net.CarNetworkReadiness.State
import io.github.shaonialife.carptocar.net.NetworkReusePolicy.Decision
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关兜底（1.2.109 修复 2）：位置服务关闭时 SSID 永远读不到，实车
 * `CarPToCarL-log-20261124-194036.txt` 里手机全程在 172.16.222.105（车机热点网段），
 * 却被原判定按「手机上没有任何 WiFi 网络 → 重新入网」每轮重 join。
 */
class NetworkReuseGatewayFallbackTest {
    @After
    fun tearDown() {
        NetworkReusePolicy.resetRememberedPrefix()
    }

    /** SSID 不可读 + 网关落在车机网段 → 按复用处理（原逻辑会判 Rejoin）。 */
    @Test
    fun `ssid 不可读且网关命中车机网段时复用现有网络`() {
        val snapshot =
            CarNetworkSnapshot(
                expectedSsid = "DIRECT-BMW 71280",
                boundSsid = null,
                associatedSsid = null,
                hasBoundNetwork = false,
                hasAddress = false,
                gateway = "172.16.222.97",
            )
        val observation = CarNetworkReadiness.inspect(snapshot)
        assertTrue(observation.state == State.ABSENT)
        val decision = NetworkReusePolicy.decide(observation, snapshot)
        assertTrue("网关兜底应判复用，实际 ${decision.reason}", decision is Decision.Reuse)
        assertTrue(decision.reason.contains("gateway=172.16.222.97"))
        // 复用不刷屏（shouldLog 契约）
        assertFalse(NetworkReusePolicy.shouldLog(decision))
    }

    /** 回归：SSID 读得到而确实没连（真 ABSENT / WRONG_NETWORK）不走兜底。 */
    @Test
    fun `ssid 可读的真没连场景不触发兜底`() {
        val snapshot =
            CarNetworkSnapshot(
                expectedSsid = "DIRECT-BMW 71280",
                boundSsid = null,
                associatedSsid = "HomeWiFi",
                hasBoundNetwork = false,
                hasAddress = false,
                gateway = null,
            )
        val observation = CarNetworkReadiness.inspect(snapshot)
        assertTrue(observation.state == State.WRONG_NETWORK)
        assertTrue(NetworkReusePolicy.decide(observation, snapshot) is Decision.Rejoin)
    }

    /** 网关不在车机网段（家里路由）不兜底。 */
    @Test
    fun `网关不在车机网段时不兜底`() {
        val snapshot =
            CarNetworkSnapshot(
                expectedSsid = "DIRECT-BMW 71280",
                boundSsid = null,
                associatedSsid = null,
                hasBoundNetwork = false,
                hasAddress = false,
                gateway = "192.168.1.1",
            )
        val observation = CarNetworkReadiness.inspect(snapshot)
        assertTrue(NetworkReusePolicy.decide(observation, snapshot) is Decision.Rejoin)
    }

    /** 上次成功 join 记下的网段优先于静态默认值。 */
    @Test
    fun `上次成功 join 的网段覆盖静态默认`() {
        NetworkReusePolicy.rememberSuccessfulJoinPrefix("10.174.189.66")
        val snapshot =
            CarNetworkSnapshot(
                expectedSsid = "AUDI-AID",
                boundSsid = null,
                associatedSsid = null,
                hasBoundNetwork = false,
                hasAddress = false,
                gateway = "10.174.189.1",
            )
        val observation = CarNetworkReadiness.inspect(snapshot)
        val decision = NetworkReusePolicy.decide(observation, snapshot)
        assertTrue(decision is Decision.Reuse)
        assertTrue(decision.reason.contains("来自上次成功 join"))
    }

    // ---- 审计 E1（docs/flow-audit-20261125.md）：网段兜底记忆要有失效机制 ----

    /** 会话收尾（retireSessionResources 调 forgetSessionScopedPrefix）→ 动态记忆复位。 */
    @Test
    fun `会话收尾后动态网段记忆复位`() {
        NetworkReusePolicy.rememberSuccessfulJoinPrefix("10.174.189.66")
        NetworkReusePolicy.forgetSessionScopedPrefix()
        // 记忆清掉后网关兜底退回静态默认 172.16.222.：旧车机网段 10.174.189.x 不再命中。
        val snapshot =
            CarNetworkSnapshot(
                expectedSsid = "AUDI-AID",
                boundSsid = null,
                associatedSsid = null,
                hasBoundNetwork = false,
                hasAddress = false,
                gateway = "10.174.189.1",
            )
        val observation = CarNetworkReadiness.inspect(snapshot)
        assertTrue(
            "失效后旧网段不得再兜底复用",
            NetworkReusePolicy.decide(observation, snapshot) is Decision.Rejoin,
        )
    }

    /** 换目标设备（TargetDevicePrefs 变更调 forgetSessionScopedPrefix）→ 动态记忆复位。 */
    @Test
    fun `换目标设备后动态网段记忆复位`() {
        NetworkReusePolicy.rememberSuccessfulJoinPrefix("10.174.189.66")
        NetworkReusePolicy.forgetSessionScopedPrefix()
        val snapshot =
            CarNetworkSnapshot(
                expectedSsid = "DIRECT-BMW 71280",
                boundSsid = null,
                associatedSsid = null,
                hasBoundNetwork = false,
                hasAddress = false,
                gateway = "10.174.189.1",
            )
        val observation = CarNetworkReadiness.inspect(snapshot)
        assertTrue(NetworkReusePolicy.decide(observation, snapshot) is Decision.Rejoin)
        // 静态默认 172.16.222.（MGU-55-J 已验证值）保留：复位只清动态覆盖。
        val bmwSnapshot =
            CarNetworkSnapshot(
                expectedSsid = "DIRECT-BMW 71280",
                boundSsid = null,
                associatedSsid = null,
                hasBoundNetwork = false,
                hasAddress = false,
                gateway = "172.16.222.97",
            )
        val bmwObservation = CarNetworkReadiness.inspect(bmwSnapshot)
        val bmwDecision = NetworkReusePolicy.decide(bmwObservation, bmwSnapshot)
        assertTrue("静态默认兜底必须保留", bmwDecision is Decision.Reuse)
        assertFalse(bmwDecision.reason.contains("来自上次成功 join"))
    }
}
