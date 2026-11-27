package io.github.shaonialife.carptocar.carlife

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.shaonialife.carptocar.SessionController

/**
 * 可选无障碍服务（档位 2）：自动点击小米 CarWith / 百度 CarLife+ 的无线连接，
 * 并在 `CarLifeHijack` 接管出帧后自动退入后台。
 *
 * 隐私与安全约束：
 * - `res/xml/carlife_auto_trigger_service.xml` 已在系统层按 `packageNames` 严格限定只接收
 *   CarWith / CarLife 相关包事件，不监听任何聊天、支付或系统设置界面；
 * - 未由 `CarLifeAutoCoordinator` 主动武装（25 秒短窗口）时，`onAccessibilityEvent` 立即返回，零开销；
 * - 实际点击规则全部收口在纯策略机 [CarLifeAutoTriggerPolicy] 中，绝不点击有线/个人热点/取消/断开等高危项。
 */
class CarLifeAutoTriggerService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = null
        runCatching { disableSelf() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无障碍自动点击功能已停用（已改用 CarWith 原生有线虚拟屏直连入口）
        return
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        snapshots: MutableList<CarLifeAutoTriggerPolicy.NodeSnapshot>,
        targets: MutableMap<Int, AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > MAX_TRAVERSAL_DEPTH || snapshots.size >= MAX_COLLECTED_NODES) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (!text.isNullOrBlank() || !desc.isNullOrBlank()) {
            val clickableNode = findClickableAncestor(node)
            val id = snapshots.size
            if (clickableNode != null) {
                targets[id] = clickableNode
            }
            snapshots +=
                CarLifeAutoTriggerPolicy.NodeSnapshot(
                    id = id,
                    text = text,
                    contentDescription = desc,
                    viewId = node.viewIdResourceName,
                    clickable = clickableNode != null,
                    enabled = node.isEnabled && (clickableNode?.isEnabled ?: true),
                    visible = node.isVisibleToUser,
                )
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (snapshots.size >= MAX_COLLECTED_NODES) break
            collectNodes(node.getChild(i), depth + 1, snapshots, targets)
        }
    }

    /**
     * MIUI / HyperOS 设置列表里文字节点本身常常 `isClickable=false`，真正可点击的是其
     * 上层条目容器（1~3 层祖先）；而顶部标题栏 TextView 的祖先都不可点击，天然被过滤掉。
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ANCESTOR_HOPS + 1) {
            val candidate = current ?: return null
            if (candidate.isClickable && candidate.isEnabled) return candidate
            current = candidate.parent
        }
        return null
    }

    companion object {
        private const val MAX_TRAVERSAL_DEPTH = 24
        private const val MAX_COLLECTED_NODES = 160
        private const val MAX_ANCESTOR_HOPS = 3

        @Volatile
        private var instance: CarLifeAutoTriggerService? = null

        /** 服务当前是否真正连接存活（已停用，恒返回 false）。 */
        val isConnected: Boolean
            get() = false

        /** 执行全局返回桌面（已停用，恒返回 false）。 */
        fun requestGoHome(): Boolean = false

        /** 检查系统无障碍设置里是否已启用本服务（已停用，恒返回 false）。 */
        fun isEnabledInSettings(context: Context): Boolean = false
    }
}
