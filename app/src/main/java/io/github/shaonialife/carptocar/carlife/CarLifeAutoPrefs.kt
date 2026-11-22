package io.github.shaonialife.carptocar.carlife

import android.content.Context

/**
 * CarWith / CarLife 自动拉起与退后台偏好。
 *
 * 与 `CarLifeHijack` 共用 `settings` 偏好文件：
 * - [launchTarget]：优先拉起哪一个本机引擎（默认自动，小米机型优先小米 CarWith）；
 * - [autoLaunchOnConnect]：发起车机连接（含靠近车机自动启动）时，若 7240 未出帧，是否自动拉起；
 * - [autoBackgroundOnReady]：接管 7240 并收到首帧后，是否自动将 CarWith/CarLife 切入后台。
 */
object CarLifeAutoPrefs {
    private const val PREFS = "settings"
    private const val KEY_TARGET = "carlife_launch_target"
    private const val KEY_AUTO_LAUNCH = "carlife_auto_launch_on_connect"
    private const val KEY_AUTO_BACKGROUND = "carlife_auto_background_on_ready"

    enum class LaunchTarget(
        val key: String,
        val label: String,
    ) {
        AUTO("auto", "自动（优先百度 CarLife+）"),
        XIAOMI_CARWITH("xiaomi_carwith", "小米 CarWith"),
        BAIDU_CARLIFE("baidu_carlife", "百度 CarLife+"),
        XIAOMI_CARLIFE_COMPONENT("xiaomi_carlife_component", "小米 CarLife+ 组件"),
        ;

        fun next(): LaunchTarget {
            val all = entries
            return all[(ordinal + 1) % all.size]
        }

        companion object {
            fun fromKey(key: String?): LaunchTarget =
                entries.firstOrNull { it.key == key } ?: AUTO
        }
    }

    fun launchTarget(context: Context): LaunchTarget =
        LaunchTarget.fromKey(
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TARGET, null),
        )

    fun setLaunchTarget(
        context: Context,
        target: LaunchTarget,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET, target.key)
            .apply()
    }

    fun autoLaunchOnConnect(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_LAUNCH, true)

    fun setAutoLaunchOnConnect(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_LAUNCH, enabled)
            .apply()
    }

    fun autoBackgroundOnReady(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_BACKGROUND, true)

    fun setAutoBackgroundOnReady(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_BACKGROUND, enabled)
            .apply()
    }
}
