package io.github.shaonialife.carptocar.net

/**
 * WiFi 建议通道（[WifiCarPlayConnector.registerSuggestion]）的登记状态：
 * 去重 + 已授权判定。纯逻辑、无 Android 依赖，便于单测。
 *
 * 审计 B1（docs/flow-audit-20261125.md）：0x5703 与 0x4301 两条路在同一轮各登记一次
 * 建议通道（实车日志出现两条「已登记热点建议」）；specifier 失败后 [WifiCarPlayConnector.joinSilentlyBySuggestion]
 * 内部还会再登记一次。系统虽对重复建议返回 ADD_DUPLICATE，但每轮多条 binder 调用 +
 * 多行日志，且建议网络是系统级自动连接——登记成功一次后本进程不该重复登记。
 *
 * 实车证据（审计 B1 原文）：同一轮 0x5703/0x4301 双通道并发，每条路径都走
 * registerSuggestion → joinCarWifiSpecifier，日志双条登记。
 */
class WifiSuggestionChannel {
    @Volatile
    private var lastRegisteredKey: String? = null

    private val authorizedSsids = HashSet<String>()

    /**
     * 本轮是否还需要登记建议。
     *
     * 规则：同一 `(SSID, 密码, 安全类型)` 组合登记成功过一次就不再登记——
     * SSID 变更或密码变更（车机改热点密码）才重新登记。
     */
    @Synchronized
    fun shouldRegister(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): Boolean = lastRegisteredKey != key(ssid, passphrase, securityType)

    /** 登记结果回填：成功（含系统返回 ADD_DUPLICATE 视为已登记）后记住，本轮不再重复。 */
    @Synchronized
    fun recordRegistered(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ) {
        lastRegisteredKey = key(ssid, passphrase, securityType)
    }

    /**
     * 建议通道是否已「授权/生效」（粗判）。
     *
     * 审计 B1/B2 的判据原文：「建议已授权（可用『系统已关联过该 SSID 历史』粗判）」。
     * 应用拿不到系统的授权状态位，只能以事件侧面记录：建议路径真的连上过、
     * 或登记后观察到系统已关联该 SSID，都算已生效。
     */
    @Synchronized
    fun isAuthorized(ssid: String): Boolean = ssid in authorizedSsids

    /** 观察到「建议通道已生效」的一次事件（静默入网成功 / 系统已自动关联）。 */
    @Synchronized
    fun markAuthorized(ssid: String) {
        authorizedSsids.add(ssid)
    }

    /** 车机换网/换密码后清授权记忆（新凭据未经授权，回到保守路径）。 */
    @Synchronized
    fun clearAuthorized(ssid: String) {
        authorizedSsids.remove(ssid)
    }

    private fun key(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): String = "$ssid|$passphrase|$securityType"
}
