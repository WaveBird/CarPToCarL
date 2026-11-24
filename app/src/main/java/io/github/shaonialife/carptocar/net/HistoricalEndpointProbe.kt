package io.github.shaonialife.carptocar.net

import java.net.InetSocketAddress
import java.net.Socket

/**
 * 端点解析前对「历史地址」的快速 TCP 探测策略。纯逻辑（探测函数可注入），便于单测。
 *
 * 审计 B4（docs/flow-audit-20261125.md）：车机 mDNS 记录闪断（onServiceLost「服务消失 CarPlay」）
 * 后，旧实现无脑等满 `ENDPOINT_RESOLVE_WAIT_MS` 的完整 NSD 发现；而 lastKnown 缓存
 * （跨轮保留）与 ctrl 邀请来源地址（`CarPlayCtrlAdvertiser.lastInvitePeer`）在已保存目标
 * 场景几乎总是可用，且车机 IP 在同一热点会话内不变。应在 mDNS 等待**之前**先做一次
 * 零等待 TCP 探测：可连即直接用，不可连再进入 mDNS 等待。NSD 保留为回退与失效更新路径。
 *
 * 探测超时 1.5s（比旧 probeEndpoint 的 800ms 稍宽——历史值跨轮/跨网络，允许一次
 * 慢网；总成本上限仍是固定的，失败即回退 NSD，不循环重试）。
 */
class HistoricalEndpointProbe(
    private val probeTimeoutMillis: Int = PROBE_TIMEOUT_MILLIS,
    /** 探测实现，注入便于单测（默认真实 TCP connect）。 */
    private val tryConnect: (host: String, port: Int) -> Boolean = { host, port ->
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), probeTimeoutMillis)
                true
            }
        } catch (_: Exception) {
            false
        }
    },
) {
    /**
     * 依次探测 [candidates]，返回第一个可连的 `host:port`；全部失败返回 null。
     * 候选应已由 [EndpointHosts.candidates] / 调用方按可信度排序（IPv4 优先）。
     */
    fun probe(
        candidates: List<String>,
        port: Int,
    ): ProbeHit? {
        if (port <= 0) return null
        for (host in candidates) {
            if (host.isBlank() || EndpointHosts.isPlaceholder(host)) continue
            if (tryConnect(host, port)) return ProbeHit(host, port)
        }
        return null
    }

    /** 一个可用的历史端点（host 已实测可连）。 */
    data class ProbeHit(
        val host: String,
        val port: Int,
    )

    companion object {
        /** 单个历史地址的 TCP 探测上限（审计 B4 建议 ~1.5s 快速超时）。 */
        const val PROBE_TIMEOUT_MILLIS = 1_500
    }
}
