package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 B1（docs/flow-audit-20261125.md）：建议通道与 specifier 同轮双注册、
 * 0x5703/0x4301 各登记一次（实车日志双条「已登记热点建议」）。
 * 收敛为：同一 (SSID,密码,安全类型) 组合登记成功一次后不再重复登记；
 * 建议已授权/生效时本轮走「复用 → 建议短窗 → specifier」。
 */
class WifiSuggestionChannelTest {
    private val ssid = "BMW94841 CarPlay"

    @Test
    fun firstCombinationRequiresRegistration() {
        val channel = WifiSuggestionChannel()
        assertTrue("首次必须登记（否则建议通道根本不存在）", channel.shouldRegister(ssid, "12345678", 2))
    }

    @Test
    fun sameCombinationRegisteredOnceIsNotRepeated() {
        val channel = WifiSuggestionChannel()
        channel.recordRegistered(ssid, "12345678", 2)
        assertFalse(
            "审计 B1：登记成功一次后本进程不再重复登记（0x5703/0x4301 双路共用）",
            channel.shouldRegister(ssid, "12345678", 2),
        )
    }

    @Test
    fun passphraseChangeTriggersReregistration() {
        val channel = WifiSuggestionChannel()
        channel.recordRegistered(ssid, "12345678", 2)
        assertTrue("车机改热点密码（0x5703 重新下发）必须重新登记", channel.shouldRegister(ssid, "87654321", 2))
    }

    @Test
    fun ssidChangeTriggersReregistration() {
        val channel = WifiSuggestionChannel()
        channel.recordRegistered(ssid, "12345678", 2)
        assertTrue("换车机（SSID 变更）必须重新登记", channel.shouldRegister("Audi_5GHz", "12345678", 2))
    }

    @Test
    fun authorizationIsRememberedAndCleared() {
        val channel = WifiSuggestionChannel()
        assertFalse("未授权前不能走建议短窗", channel.isAuthorized(ssid))
        // 实车证据（审计 B1/B2）：建议真的连上过 = 通道已授权，之后 specifier 失败 1 次即改道。
        channel.markAuthorized(ssid)
        assertTrue(channel.isAuthorized(ssid))
        // 车机换网：授权记忆清掉，回到保守路径。
        channel.clearAuthorized(ssid)
        assertFalse("SSID 变更后旧授权记忆不再可信", channel.isAuthorized(ssid))
    }

    @Test
    fun authorizationIsPerSsid() {
        val channel = WifiSuggestionChannel()
        channel.markAuthorized(ssid)
        assertFalse("授权按 SSID 记，不跨热点泛化", channel.isAuthorized("Audi_5GHz"))
    }
}

/**
 * 审计 B2：建议通道已授权时 specifier join 失败 1 次即改走 awaitAssociation（建议关联）短窗，
 * 不再 force 自动重试烧满 25s 预算；「建议未授权」场景保留 specifier 重试能力。
 */
class SpecifierFailoverPolicyTest {
    @Test
    fun suggestionAuthorizedFailsOnceThenFailover() {
        val policy = SpecifierFailoverPolicy()
        assertTrue(
            "建议已授权 + specifier 已失败 1 次 → 改走建议关联短窗（不再烧预算）",
            policy.shouldFailoverToAwaitAssociation(suggestionAuthorized = true, specifierFailures = 1),
        )
        assertFalse(
            "已改道的会话不允许再 force 重试 specifier（审计 B2：烧满 25s 预算的根源）",
            policy.shouldRetrySpecifier(suggestionAuthorized = true, specifierFailures = 1),
        )
    }

    @Test
    fun suggestionAuthorizedButNoFailureYetKeepsSpecifier() {
        val policy = SpecifierFailoverPolicy()
        assertFalse("失败 0 次还没有改道依据——specifier 快路（静默满足）要先试", policy.shouldFailoverToAwaitAssociation(true, 0))
        assertTrue(policy.shouldRetrySpecifier(true, 0))
    }

    @Test
    fun suggestionUnauthorizedKeepsRetryAbility() {
        val policy = SpecifierFailoverPolicy()
        // 首次连接、建议可能还没批：specifier 静默满足与建议授权都需要机会——保留自动重试。
        assertTrue(
            "建议未授权时保留 specifier 重试能力（审计 B2 保留语义）",
            policy.shouldRetrySpecifier(suggestionAuthorized = false, specifierFailures = 1),
        )
        assertFalse(policy.shouldFailoverToAwaitAssociation(false, 1))
        assertTrue(policy.shouldRetrySpecifier(false, 2))
    }

    @Test
    fun multipleFailuresUnderAuthorizationAlwaysFailover() {
        val policy = SpecifierFailoverPolicy()
        assertTrue(policy.shouldFailoverToAwaitAssociation(true, 2))
        assertTrue(policy.shouldFailoverToAwaitAssociation(true, 5))
        assertFalse(policy.shouldRetrySpecifier(true, 2))
    }
}

/**
 * 审计 B4：端点解析前先快连历史地址（lastKnown / lastInvitePeer），~1.5s 快速超时；
 * 可连即直接用（跳过 mDNS 等待），全部失败回退 NSD。NSD 保留为回退与失效更新路径。
 */
class HistoricalEndpointProbeTest {
    private val goodHost = "10.174.189.1"

    @Test
    fun firstReachableCandidateWins() {
        val probed = mutableListOf<String>()
        val probe =
            HistoricalEndpointProbe(tryConnect = { host, port ->
                probed += "$host:$port"
                host == goodHost
            })
        val hit = probe.probe(listOf("192.168.1.99", goodHost), 7000)
        assertEquals("可连的历史地址直接采用（审计 B4：跳过 mDNS 等待）", goodHost, hit?.host)
        assertEquals(7000, hit?.port)
        // 实车证据（审计 B4 原文）：车机 IP 在同一热点会话内不变，历史值快连成功即用。
        assertEquals(listOf("192.168.1.99:7000", "$goodHost:7000"), probed)
    }

    @Test
    fun allUnreachableReturnsNullToFallbackNsd() {
        val probe = HistoricalEndpointProbe(tryConnect = { _, _ -> false })
        assertNull("全部失败回退完整 NSD 发现（审计 B4：NSD 保留为回退路径）", probe.probe(listOf(goodHost), 7000))
    }

    @Test
    fun placeholderAndBlankCandidatesSkipped() {
        val probed = mutableListOf<String>()
        val probe =
            HistoricalEndpointProbe(tryConnect = { host, _ ->
                probed += host
                true
            })
        val hit = probe.probe(listOf("0.0.0.0", "", goodHost), 7000)
        assertEquals(goodHost, hit?.host)
        assertFalse("占位网关（宝马 iD8 实测 0.0.0.0）不参与快连", probed.contains("0.0.0.0"))
        assertFalse("空白候选不参与快连", probed.contains(""))
    }

    @Test
    fun invalidPortShortCircuits() {
        val probe = HistoricalEndpointProbe(tryConnect = { _, _ -> true })
        assertNull("端口非法（0x4301 未带端口且未兜底）不做无意义探测", probe.probe(listOf(goodHost), 0))
    }
}
