package io.github.shaonialife.carptocar.net

/**
 * specifier 通道失败后的改道策略。纯逻辑、无 Android 依赖，便于单测。
 *
 * 审计 B2（docs/flow-audit-20261125.md）：建议通道已授权/已生效的会话里，
 * `WifiNetworkSpecifier` 的 Unavailable 通常意味着「系统确认框无人点」——
 * 车上没人点屏，这条路径注定失败。旧实现仍然自动 force 重试
 * （`SPECIFIER_AUTO_RETRY_TIMES` 次、每次绕过 WifiJoinGate 的 5s 去重），
 * 把 25s WiFi 总预算全烧在一条已被判死的通道上（实车证据：审计原文
 * 「每次绕过 5s 去重 force=true，把 WiFi 总预算（25s）全烧在一条已被判死的通道上」）。
 *
 * 新规则：
 *  - 建议通道**未**授权（首次连接、建议可能还没批）：保留自动重试能力——
 *    specifier 静默满足与建议授权都需要机会；
 *  - 建议通道**已**授权：specifier 失败 1 次即改走 `awaitAssociation`
 *    （建议关联）短窗，不再 force 重试。
 */
class SpecifierFailoverPolicy {
    /**
     * specifier 失败后是否还允许自动重试 specifier。
     *
     * @param suggestionAuthorized 建议通道是否已授权/已生效（[WifiSuggestionChannel.isAuthorized]）
     * @param specifierFailures 本轮 specifier 已连续失败的次数（含刚发生的这一次）
     */
    fun shouldRetrySpecifier(
        suggestionAuthorized: Boolean,
        specifierFailures: Int,
    ): Boolean = !suggestionAuthorized || specifierFailures < 1

    /** 建议已授权且 specifier 已失败 1 次 → 改走建议关联短窗（awaitAssociation）。 */
    fun shouldFailoverToAwaitAssociation(
        suggestionAuthorized: Boolean,
        specifierFailures: Int,
    ): Boolean = suggestionAuthorized && specifierFailures >= 1
}
