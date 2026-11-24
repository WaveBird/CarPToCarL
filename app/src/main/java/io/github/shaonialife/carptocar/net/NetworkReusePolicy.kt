package io.github.shaonialife.carptocar.net

/**
 * 「这条热点还能不能直接复用」的判定：把 [CarNetworkReadiness] 的状态翻译成**下一步动作**。
 *
 * 为什么单独抽一层：复用判断原来散在 SessionController 的几处 `if (boundCarSsid == ssid
 * && currentNetwork != null)`，它信的是**我们自己记的账**（`boundCarSsid`），而不是系统
 * 现在的样子。系统在下面悄悄换过网络（热点掉了、被 ROM 踢回家里那条）时，账本还是
 * 车机热点、`currentNetwork` 也还非空，于是我们「复用」的其实是家里那条 WiFi——
 * 之后 mDNS、ctrl 广播、建链全打在错的网络上，日志里只看到「已绑定车机热点，跳过重复绑定」。
 *
 * 现在的规矩：
 *  - 复用之前必须 `expected == bound`（读不到名字时至少要有地址且确实绑着）；
 *  - 「网络在但没就绪」→ [Decision.WaitForAddress]，等地址，**绝不重新发 specifier**
 *    （`joinCarPlayNetwork` 第一行是 `release()`，会把正在建立的网络拆掉）；
 *  - 「已关联未绑定」→ [Decision.AdoptNow]，零交互绑一下；
 *  - 「真的掉了」/「不是这条」→ [Decision.Rejoin]，才走完整入网。
 */
object NetworkReusePolicy {
    /** 等 DHCP 给出地址的上限。超过就别等了，交给完整入网流程重来。 */
    const val WAIT_FOR_ADDRESS_MILLIS = 3_000L

    /**
     * 车机热点网段前缀（/24 前 3 段）。实车 2026-11-24：手机全程拿 `172.16.222.105`，
     * 车机固定为 `172.16.222.97`（mDNS/ctrl 邀请多方印证）。静态默认值可被
     * [rememberSuccessfulJoinPrefix] 覆盖——上次**真正连上**的网段比默认值更可信
     * （不同车机网段不同）。
     */
    const val DEFAULT_CAR_IPV4_PREFIX = "172.16.222."

    @Volatile
    private var lastSuccessfulJoinPrefix: String? = null

    /**
     * 记一次「真正成功 join」后的本机 IPv4 网段前缀（`172.16.222.` 形态，/24）。
     * 由调用方在 join 成功、拿到地址时喂进来（喂 null 清除）。
     */
    fun rememberSuccessfulJoinPrefix(localIpv4: String?) {
        lastSuccessfulJoinPrefix =
            localIpv4
                ?.takeIf { it.count { c -> c == '.' } == 3 }
                ?.let { base ->
                    val parts = base.trim().split('.')
                    val octets = parts.mapNotNull { it.toIntOrNull() }
                    if (octets.size == 4 && octets.all { it in 0..255 }) parts.take(3).joinToString(".") + "." else null
                }
    }

    /**
     * 会话收尾/换目标时失效动态网段记忆（审计 E1，`docs/flow-audit-20261125.md`）。
     *
     * 1.2.109 的网关兜底使用「上次成功 join 网段」记忆：用户换一台车（不同网段热点）
     * 且新热点 SSID 不可读时，旧网段兜底会**拒绝正确复用**（gateway 不匹配旧前缀 →
     * Rejoin，白 join 一次后才在新 join 成功时更新）。记忆是进程内存（进程重启本来就
     * 清），这里补的正是「同进程跨车」这个洞：静态默认 `172.16.222.`（MGU-55-J 的
     * 已验证值）保留不删——兜底只是退回静态默认，不会变空。`onInvalidated` 由调用方
     * 注入（SessionController 传 appendLog），测试传 `{}`；缺省空表示不打日志。
     */
    fun forgetSessionScopedPrefix() {
        val had = lastSuccessfulJoinPrefix != null
        lastSuccessfulJoinPrefix = null
        if (had) {
            onInvalidated?.invoke(
                "WIFI: 会话收尾/换目标——动态网段记忆已失效（网关兜底回退静态默认 $DEFAULT_CAR_IPV4_PREFIX）",
            )
        }
    }

    /** 失效时的一行日志（SessionController 注入 appendLog；测试传 null 关闭）。 */
    @Volatile
    var onInvalidated: ((String) -> Unit)? = null

    /** 仅供单测复位。 */
    internal fun resetRememberedPrefix() {
        lastSuccessfulJoinPrefix = null
    }

    sealed interface Decision {
        /** 一句话「为什么这么判」，直接进日志（与 UI 共用）。 */
        val reason: String

        /** 直接复用当前绑定的网络：已就绪且（能核对时）就是目标热点。 */
        data class Reuse(
            override val reason: String,
        ) : Decision

        /** 系统已关联目标热点、只是进程没绑——[WifiCarPlayConnector.adoptAssociated] 一下即可。 */
        data class AdoptNow(
            override val reason: String,
        ) : Decision

        /** 网络在但没地址：等它就绪，别重新入网（重新入网会 release 掉这条）。 */
        data class WaitForAddress(
            override val reason: String,
            val waitMillis: Long = WAIT_FOR_ADDRESS_MILLIS,
        ) : Decision

        /** 真的掉了 / 不是目标热点：只能走完整入网。 */
        data class Rejoin(
            override val reason: String,
        ) : Decision
    }

    fun decide(observation: CarNetworkReadiness.Observation): Decision = decide(observation, null)

    /**
     * 判定入口（保留原 [decide] 单参签名——调用点很多，不破坏）：多带一份快照，
     * SSID 全读不到时才有机会走网关兜底。
     *
     * 优先级：READY/ADOPT/WAIT/WRONG_NETWORK 原语义不动（它们不依赖 SSID 可读性）；
     * **只有** [CarNetworkReadiness.State.ABSENT]（原判「手机上没有任何 WiFi 网络」）
     * 且快照确认 SSID 全都读不到（位置服务关闭的典型形态）时，才试网关兜底。
     *
     * 实车证据（2026-11-24 晚 `194036`）：手机全程在 `172.16.222.105`（车机热点网段，
     * 网关=车机自己），却因 SSID 读不到每轮被判「手机上没有任何 WiFi 网络 → 重新入网」，
     * 白白重 join。
     */
    fun decide(
        observation: CarNetworkReadiness.Observation,
        snapshot: CarNetworkSnapshot?,
    ): Decision {
        val base =
            when (observation.state) {
                CarNetworkReadiness.State.READY -> Decision.Reuse(observation.detail)
                CarNetworkReadiness.State.ADOPT -> Decision.AdoptNow(observation.detail)
                CarNetworkReadiness.State.NOT_READY ->
                    Decision.WaitForAddress(observation.detail, WAIT_FOR_ADDRESS_MILLIS)
                CarNetworkReadiness.State.WRONG_NETWORK -> Decision.Rejoin(observation.detail)
                CarNetworkReadiness.State.ABSENT -> Decision.Rejoin(observation.detail)
            }
        if (base !is Decision.Rejoin || observation.state != CarNetworkReadiness.State.ABSENT) return base
        val shot = snapshot ?: return base
        // SSID 读得到而判成 ABSENT 是真没连（兜底只服务「SSID 不可读」这一种形态）
        if (!shot.ssidUnknown) return base
        return gatewayFallback(shot) ?: base
    }

    /**
     * 网关兜底（1.2.109 修复 2，私有——不改任何调用点接口）：SSID 不可读、但默认网络
     * 的网关 IP 落在车机网段——这台手机几乎必然挂在车机热点上（车机热点网关=车机自己，
     * 车机是热点里唯一在 7000/5001 端口上应答的设备）。按「已在车机热点」处理，
     * 走 READY 复用分支。
     */
    private fun gatewayFallback(snapshot: CarNetworkSnapshot): Decision? {
        val gateway = snapshot.gateway?.takeIf { it.isNotBlank() && !EndpointHosts.isPlaceholder(it) } ?: return null
        val remembered = lastSuccessfulJoinPrefix
        val prefix = remembered ?: DEFAULT_CAR_IPV4_PREFIX
        if (!gateway.startsWith(prefix)) return null
        return Decision.Reuse(
            "SSID 不可读但网关判定已在车机热点（gateway=$gateway，网段 $prefix" +
                if (remembered != null) "，来自上次成功 join）" else "）——复用现有网络，跳过重新入网",
        )
    }

    /** 这条判定值不值得写进日志（`Reuse` 的常态复用不刷屏，由调用方自己补一行）。 */
    fun shouldLog(decision: Decision): Boolean = decision !is Decision.Reuse

    /**
     * 一行日志：`WIFI 复用判定：网络在但没就绪… → 等地址（最长 3000ms）`。
     *
     * 状态与动作都写出来，是因为排障时最想知道的正是「当时系统认为是什么状态、
     * 我们因此做了什么」——只写动作的话，事后仍然要猜当时读到了什么。
     */
    fun describe(decision: Decision): String {
        val action =
            when (decision) {
                is Decision.Reuse -> "直接复用"
                is Decision.AdoptNow -> "绑定即可用（不重新入网）"
                is Decision.WaitForAddress -> "等地址（最长 ${decision.waitMillis}ms，不重新入网）"
                is Decision.Rejoin -> "重新入网"
            }
        return "WIFI 复用判定：${decision.reason} → $action"
    }
}
