package io.github.shaonialife.carptocar.carlife

/**
 * 无障碍自动触发 CarWith / CarLife 无线连接的纯策略机（无 Android 依赖，便于 JVM 单测）。
 *
 * 安全边界：
 * 1. **仅在武装窗口内动作**（默认 25 秒，由 `CarLifeAutoCoordinator` 在调起应用时武装）；
 * 2. **仅作用于 CarWith / CarLife 白名单包名**，绝不碰系统设置或其它应用；
 * 3. **严禁点击会破坏无线 CarPlay 的选项**（如「个人热点 / 开启热点 / 关闭WLAN / 有线 / USB / 亿连 / 断开 / 取消」）；
 * 4. **同文案去重冷却 + 总点击次数上限**，杜绝死循环连点。
 */
class CarLifeAutoTriggerPolicy(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val labelCooldownMs: Long = DEFAULT_LABEL_COOLDOWN_MS,
    private val minStepIntervalMs: Long = DEFAULT_MIN_STEP_INTERVAL_MS,
    private val maxTotalClicks: Int = DEFAULT_MAX_TOTAL_CLICKS,
    private val maxClicksPerLabel: Int = DEFAULT_MAX_CLICKS_PER_LABEL,
) {
    /** 单个界面节点的轻量快照（由 AccessibilityService 从节点树提取）。 */
    data class NodeSnapshot(
        val id: Int,
        val text: String?,
        val contentDescription: String?,
        val viewId: String?,
        val clickable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
    ) {
        val label: String
            get() =
                text
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: contentDescription?.trim().orEmpty()
    }

    data class ClickDecision(
        val nodeId: Int,
        val matchedLabel: String,
        val stage: String,
        val priority: Int,
    )

    private var armedUntilMs: Long = 0L
    private var lastClickAtMs: Long = 0L
    private var totalClicks: Int = 0
    private val labelLastClickAt = HashMap<String, Long>()
    private val labelClickCount = HashMap<String, Int>()

    fun arm(nowMs: Long) {
        armedUntilMs = nowMs + windowMs
        lastClickAtMs = 0L
        totalClicks = 0
        labelLastClickAt.clear()
        labelClickCount.clear()
    }

    fun disarm() {
        armedUntilMs = 0L
        lastClickAtMs = 0L
        totalClicks = 0
        labelLastClickAt.clear()
        labelClickCount.clear()
    }

    fun isArmed(nowMs: Long): Boolean = armedUntilMs > 0L && nowMs <= armedUntilMs

    /**
     * 从当前界面节点里挑出最合适的一步点击；无合适目标或冷却中返回 null。
     *
     * 返回非空时会同步记录本次点击的节流状态（由调用方立即执行点击）。
     */
    fun nextClick(
        packageName: String?,
        nodes: List<NodeSnapshot>,
        nowMs: Long,
        sourceActive: Boolean,
    ): ClickDecision? {
        if (sourceActive) {
            disarm()
            return null
        }
        if (!isArmed(nowMs)) return null
        if (packageName == null || packageName !in ALLOWED_PACKAGES) return null
        if (totalClicks >= maxTotalClicks) return null
        if (lastClickAtMs > 0L && nowMs - lastClickAtMs < minStepIntervalMs) return null

        var best: ClickDecision? = null
        for (node in nodes) {
            if (!node.clickable || !node.enabled || !node.visible) continue
            val rawLabel = node.label
            if (rawLabel.isEmpty() || rawLabel.length > MAX_LABEL_LENGTH) continue
            if (isForbidden(rawLabel, node.viewId)) continue

            val match = matchRule(packageName, rawLabel) ?: continue
            val key = "$packageName:${match.key}"
            val count = labelClickCount[key] ?: 0
            if (count >= maxClicksPerLabel) continue
            val lastAt = labelLastClickAt[key] ?: 0L
            if (lastAt > 0L && nowMs - lastAt < labelCooldownMs) continue

            if (best == null || match.priority > best.priority) {
                best =
                    ClickDecision(
                        nodeId = node.id,
                        matchedLabel = rawLabel,
                        stage = match.stage,
                        priority = match.priority,
                    )
            }
        }
        if (best != null) {
            val match = matchRule(packageName, best.matchedLabel)
            if (match != null) {
                val key = "$packageName:${match.key}"
                labelLastClickAt[key] = nowMs
                labelClickCount[key] = (labelClickCount[key] ?: 0) + 1
            }
            lastClickAtMs = nowMs
            totalClicks++
        }
        return best
    }

    private data class RuleMatch(
        val key: String,
        val stage: String,
        val priority: Int,
    )

    private fun matchRule(
        packageName: String,
        label: String,
    ): RuleMatch? {
        val compact = label.replace(" ", "")
        // 1. 终极确认按钮：真正启动 7240 监听的一步，优先级最高
        for (token in FINAL_CONNECT_EXACT) {
            if (compact.equals(token, ignoreCase = true)) {
                return RuleMatch(key = "final:$token", stage = "启动无线连接", priority = 100)
            }
        }
        // 2. 首次隐私/弹窗确认按钮（仅在白名单包内）
        for (token in DIALOG_CONFIRM_EXACT) {
            if (compact.equals(token, ignoreCase = true)) {
                return RuleMatch(key = "confirm:$token", stage = "确认提示框", priority = 90)
            }
        }
        // 3. 向导「下一步」（例如 CarWith 手机连接车辆热点向导）
        for (token in WIZARD_NEXT_EXACT) {
            if (compact.equals(token, ignoreCase = true)) {
                return RuleMatch(key = "next:$token", stage = "向导下一步", priority = 80)
            }
        }
        // 4. 无线传输方式（优先 WLAN直连，次选 手机连接车辆热点 / WLAN连接）
        for ((index, token) in WIRELESS_TRANSPORT_TOKENS.withIndex()) {
            if (compact.contains(token, ignoreCase = true)) {
                return RuleMatch(
                    key = "transport:$token",
                    stage = "选择无线方式",
                    priority = 75 - index,
                )
            }
        }
        // 5. 进入「CarLife无线连接」/「无线连接」
        for ((index, token) in WIRELESS_ENTRY_TOKENS.withIndex()) {
            if (compact.contains(token, ignoreCase = true)) {
                return RuleMatch(
                    key = "wireless:$token",
                    stage = "进入无线连接",
                    priority = 65 - index,
                )
            }
        }
        // 6. 小米 CarWith 主页进入 CarLife 入口
        if (packageName == PACKAGE_XIAOMI_CARWITH) {
            for ((index, token) in CARWITH_ENTRY_TOKENS.withIndex()) {
                if (compact.contains(token, ignoreCase = true)) {
                    return RuleMatch(
                        key = "carwith:$token",
                        stage = "进入CarLife入口",
                        priority = 55 - index,
                    )
                }
            }
        }
        return null
    }

    private fun isForbidden(
        label: String,
        viewId: String?,
    ): Boolean {
        val compact = label.replace(" ", "")
        if (FORBIDDEN_TOKENS.any { compact.contains(it, ignoreCase = true) }) {
            return true
        }
        // 避免误点设置项/标题栏返回按钮
        if (viewId != null) {
            val lowerId = viewId.lowercase()
            if (lowerId.contains("back") || lowerId.contains("close") || lowerId.contains("cancel")) {
                return true
            }
        }
        return false
    }

    companion object {
        const val PACKAGE_XIAOMI_CARWITH = "com.miui.carlink"
        const val PACKAGE_BAIDU_CARLIFE = "com.baidu.carlife"
        const val PACKAGE_XIAOMI_CARLIFE = "com.baidu.carlife.xiaomi"
        const val PACKAGE_HONOR_CARLIFE = "com.baidu.carlife.honor"
        const val PACKAGE_OPPO_CARLIFE = "com.baidu.carlife.oppo"
        const val PACKAGE_VIVO_CARLIFE = "com.baidu.carlife.vivo"
        const val PACKAGE_HUAWEI_CARLIFE = "com.baidu.carlife.huawei"

        val ALLOWED_PACKAGES: Set<String> =
            setOf(
                PACKAGE_XIAOMI_CARWITH,
                PACKAGE_BAIDU_CARLIFE,
                PACKAGE_XIAOMI_CARLIFE,
                PACKAGE_HONOR_CARLIFE,
                PACKAGE_OPPO_CARLIFE,
                PACKAGE_VIVO_CARLIFE,
                PACKAGE_HUAWEI_CARLIFE,
            )

        const val DEFAULT_WINDOW_MS = 25_000L
        const val DEFAULT_LABEL_COOLDOWN_MS = 3_500L
        const val DEFAULT_MIN_STEP_INTERVAL_MS = 650L
        const val DEFAULT_MAX_TOTAL_CLICKS = 8
        const val DEFAULT_MAX_CLICKS_PER_LABEL = 2

        /** 超过该长度的文本通常是说明段落而非按钮/条目，跳过以避免误匹配说明文案里的关键词。 */
        private const val MAX_LABEL_LENGTH = 26

        private val FINAL_CONNECT_EXACT =
            listOf(
                "开始连接",
                "立即连接",
                "发起连接",
                "重新连接",
                "连接车机",
            )

        private val DIALOG_CONFIRM_EXACT =
            listOf(
                "同意",
                "同意并继续",
                "确定",
                "确认",
                "继续",
                "允许",
                "跳过",
                "知道了",
            )

        private val WIZARD_NEXT_EXACT =
            listOf(
                "下一步",
            )

        private val WIRELESS_TRANSPORT_TOKENS =
            listOf(
                "WLAN直连",
                "手机连接车辆热点",
                "WLAN连接",
                "Wi-Fi连接",
                "WiFi连接",
            )

        private val WIRELESS_ENTRY_TOKENS =
            listOf(
                "CarLife无线连接",
                "无线连接",
            )

        private val CARWITH_ENTRY_TOKENS =
            listOf(
                "CarLife连接",
                "百度CarLife+",
                "百度CarLife投屏连接",
                "百度CarLife",
                "连接方式",
                "连接车辆",
                "添加车辆",
                "连接新车辆",
                "其他连接方式",
                "其它连接方式",
            )

        /** 严禁误点的词：有线/热点(会关Wi-Fi)/亿连/取消/断开/设置等。注意不含「车辆热点」。 */
        private val FORBIDDEN_TOKENS =
            listOf(
                "有线",
                "USB",
                "亿连",
                "个人热点",
                "手机热点",
                "开启热点",
                "打开热点",
                "便携式热点",
                "关闭WLAN",
                "断开",
                "取消",
                "不同意",
                "拒绝",
                "退出",
                "删除",
                "卸载",
                "忽略",
                "稍后",
                "暂不",
                "下载",
                "插件设置",
                "蓝牙设置",
                "连接设置",
                "反馈",
                "帮助",
                "教程",
            )
    }
}
