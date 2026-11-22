package io.github.shaonialife.carptocar.carlife

import android.app.ActivityManager
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 本机 CarWith / CarLife+ 探测、状态汇总与安全调起入口。
 *
 * 只调用系统公开/已导出（`exported=true`）的 Activity，不越权触碰非导出内部页面：
 * - 小米 CarWith（`com.miui.carlink`）：经 `miui.intent.action.UCAR_CONNECT_SETTINGS`
 *   调起已导出的 `UCarConnectInfoActivity`；
 * - 百度 CarLife+（`com.baidu.carlife`）：经标准桌面 Launcher Intent 调起；
 * - 小米 CarLife+ 组件（`com.baidu.carlife.xiaomi`）：调起已导出的 `com.baidu.carlife.CarlifeActivity`；
 * - 其它厂商 CarLife 组件（荣耀/OPPO/vivo/华为）：经 Launcher Intent 调起。
 */
object CarLifeLauncher {
    const val ACTION_XIAOMI_CARWITH_MAIN = "miui.intent.action.UCAR_APP_SETTINGS"
    const val ACTION_XIAOMI_CARWITH_CONNECT = "miui.intent.action.UCAR_CONNECT_SETTINGS"
    const val XIAOMI_CARWITH_DIRECT_ENTRY = "com.miui.carlink.castfwk.usb.CarlifeAdbActivity"
    private const val XIAOMI_CARWITH_MAIN_ACTIVITY = "com.carwith.launcher.settings.phone.UCarScreenSettingsActivity"
    private const val XIAOMI_CARWITH_CONNECT_ACTIVITY = "com.carwith.launcher.settings.phone.UCarConnectInfoActivity"
    private const val CARLIFE_ACTIVITY = "com.baidu.carlife.CarlifeActivity"

    data class DetectedApp(
        val target: CarLifeAutoPrefs.LaunchTarget,
        val packageName: String,
        val displayName: String,
        val intent: Intent?,
    )

    enum class EngineState {
        DISABLED,
        NOT_INSTALLED,
        NOT_STARTED,
        PORT_LISTENING,
        HANDSHAKEN_WAITING_FRAMES,
        ZERO_FRAME_STALLED,
        READY,
        STREAMING_TO_CAR,
        ATTACHED_NO_FLOW,
    }

    data class StatusSnapshot(
        val state: EngineState,
        val badgeText: String,
        val shortLine: String,
        val detailLine: String,
        val preferredApp: DetectedApp?,
        val installedApps: List<DetectedApp>,
    )

    /** 本机是否具备小米 CarWith 公开直连入口（`CarlifeAdbActivity` + `com.baidu.carlife.xiaomi`）。 */
    fun hasXiaomiCarWithDirectEntry(context: Context?): Boolean {
        val ctx = context ?: return false
        val pm = ctx.packageManager
        val hasComponent =
            runCatching {
                pm.getApplicationInfo(CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARLIFE, 0).enabled
            }.getOrDefault(false)
        if (!hasComponent) return false
        val intent =
            Intent().apply {
                component = ComponentName(CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH, XIAOMI_CARWITH_DIRECT_ENTRY)
            }
        return canResolveActivity(pm, intent)
    }

    /** 直接请求小米 CarWith 公开虚拟屏入口（无需无障碍点击）。 */
    fun launchXiaomiCarWithDirect(context: Context?): Boolean {
        val ctx = context ?: return false
        if (!hasXiaomiCarWithDirectEntry(ctx)) return false
        return runCatching {
            val intent =
                Intent().apply {
                    component = ComponentName(CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH, XIAOMI_CARWITH_DIRECT_ENTRY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * 本应用当前是否在前台（mcarplay 同款 `ActivityManager.getMyMemoryState` 判定）。
     *
     * 为什么需要它（2026-10-26 小米实测，mcarplay `CarLifeLauncher.isAppInForeground` 原注释）：
     * **后台发起的 `startActivity` 可能被 MIUI「后台弹出界面」静默丢弃且不抛异常**——
     * `CarlifeAdbActivity` 从未创建，7240 一直 ECONNREFUSED，表现成「CarWith 有时启动失败」。
     * 调起方拿到 true 返回值不能证明入口真的收到请求；结合本方法把「本应用在后台」这一
     * 风险条件亮出来，才能给用户一句可行动的告警（去允许「后台弹出界面」）。
     *
     * importance=0（系统未填充）按前台处理，避免误报。
     */
    fun isAppInForeground(): Boolean =
        runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(true)

    /**
     * 本机是否小米/红米（MIUI/HyperOS）。
     *
     * 用于「自动」目标的机型分派：小米机的 CarWith 走**公开直连入口**
     * （`CarlifeAdbActivity`，拉起即建虚拟屏、退到后台照常出帧——它本来就是设计成
     * 无界面后台服务），因此小米机把 CarWith 排在百度 CarLife+ 前面；非小米机没有
     * CarWith，维持原「优先百度 CarLife+」逻辑。
     */
    fun isXiaomiDevice(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Redmi", ignoreCase = true)

    /** 探测本机已安装且可合法调起的 CarWith / CarLife 应用列表（按推荐顺序）。 */
    fun detectInstalled(context: Context): List<DetectedApp> {
        val pm = context.packageManager
        val found = mutableListOf<DetectedApp>()

        // 1. 小米 CarWith 公开直连入口（CarlifeAdbActivity，无需 ADB/root/无障碍点击，
        //    直接拉起原生 CarWith 虚拟屏并开放七路端口，约 0.7s 出 1920×720@30fps 真实画面）
        val carWithDirectIntent =
            Intent().apply {
                component = ComponentName(CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH, XIAOMI_CARWITH_DIRECT_ENTRY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val hasDirectCarWith = canResolveActivity(pm, carWithDirectIntent)
        if (hasDirectCarWith) {
            found +=
                DetectedApp(
                    target = CarLifeAutoPrefs.LaunchTarget.XIAOMI_CARWITH,
                    packageName = CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH,
                    displayName = "小米 CarWith（有线虚拟屏）",
                    intent = carWithDirectIntent,
                )
        }

        // 2. 百度 CarLife+（独立版：无 CarWith 直连入口时的首选，直接打开即可绑定 7240 出帧）
        val baiduIntent =
            pm.getLaunchIntentForPackage(CarLifeAutoTriggerPolicy.PACKAGE_BAIDU_CARLIFE)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (baiduIntent != null) {
            found +=
                DetectedApp(
                    target = CarLifeAutoPrefs.LaunchTarget.BAIDU_CARLIFE,
                    packageName = CarLifeAutoTriggerPolicy.PACKAGE_BAIDU_CARLIFE,
                    displayName = "百度 CarLife+",
                    intent = baiduIntent,
                )
        }

        // 3. 小米 CarWith 设置页兜底（旧版 CarWith < 3.1.2 没有 CarlifeAdbActivity 时使用）
        if (!hasDirectCarWith) {
            val carWithMainIntent =
                Intent(ACTION_XIAOMI_CARWITH_MAIN).apply {
                    component = ComponentName(CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH, XIAOMI_CARWITH_MAIN_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val carWithFallbackIntent =
                Intent(ACTION_XIAOMI_CARWITH_CONNECT).apply {
                    component = ComponentName(CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH, XIAOMI_CARWITH_CONNECT_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val resolvedCarWithIntent =
                when {
                    canResolveActivity(pm, carWithMainIntent) -> carWithMainIntent
                    canResolveActivity(pm, carWithFallbackIntent) -> carWithFallbackIntent
                    else -> null
                }
            if (resolvedCarWithIntent != null) {
                found +=
                    DetectedApp(
                        target = CarLifeAutoPrefs.LaunchTarget.XIAOMI_CARWITH,
                        packageName = CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH,
                        displayName = "小米 CarWith",
                        intent = resolvedCarWithIntent,
                    )
            }
        }

        // 3. 小米智能车载百度 CarLife+ 组件（CarWith 配套组件）
        val xiaomiCarLifeIntent =
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARLIFE, CARLIFE_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (canResolveActivity(pm, xiaomiCarLifeIntent)) {
            found +=
                DetectedApp(
                    target = CarLifeAutoPrefs.LaunchTarget.XIAOMI_CARLIFE_COMPONENT,
                    packageName = CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARLIFE,
                    displayName = "小米 CarLife+ 组件",
                    intent = xiaomiCarLifeIntent,
                )
        }

        // 4. 其它厂商预装 CarLife 组件兜底
        val oemCandidates =
            listOf(
                CarLifeAutoTriggerPolicy.PACKAGE_HONOR_CARLIFE to "荣耀 CarLife+",
                CarLifeAutoTriggerPolicy.PACKAGE_OPPO_CARLIFE to "OPPO CarLife+",
                CarLifeAutoTriggerPolicy.PACKAGE_VIVO_CARLIFE to "vivo CarLife+",
                CarLifeAutoTriggerPolicy.PACKAGE_HUAWEI_CARLIFE to "华为 CarLife+",
            )
        for ((pkg, label) in oemCandidates) {
            val intent =
                pm.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            if (intent != null) {
                found +=
                    DetectedApp(
                        target = CarLifeAutoPrefs.LaunchTarget.BAIDU_CARLIFE,
                        packageName = pkg,
                        displayName = label,
                        intent = intent,
                    )
            }
        }
        return found
    }

    /**
     * 按用户偏好选出要调起的目标（纯函数，便于 JVM 单测）。
     * 偏好项未安装时按推荐列表自动回退。
     */
    fun resolvePreferred(
        installed: List<DetectedApp>,
        preference: CarLifeAutoPrefs.LaunchTarget,
        /** 单测注入：生产路径取默认（[isXiaomiDevice] 真实机型判定）。 */
        xiaomiDevice: Boolean = isXiaomiDevice(),
    ): DetectedApp? {
        if (installed.isEmpty()) return null
        if (preference == CarLifeAutoPrefs.LaunchTarget.AUTO) {
            // 机型分派：小米/红米优先 CarWith（直连入口、天然后台），其余维持原
            // 「detectInstalled 的推荐序」（CarWith 直连 > 百度 CarLife+ > …）。
            return if (xiaomiDevice) {
                installed.firstOrNull { it.target == CarLifeAutoPrefs.LaunchTarget.XIAOMI_CARWITH }
                    ?: installed.first()
            } else {
                installed.first()
            }
        }
        return installed.firstOrNull { it.target == preference } ?: installed.first()
    }

    /** 根据当前运行态拼装界面与体检共用的状态快照（纯函数，便于 JVM 单测）。 */
    fun buildSnapshot(
        hijackEnabled: Boolean,
        videoFlowingToCar: Boolean,
        isAttached: Boolean,
        sourceActive: Boolean,
        isActive: Boolean,
        portOpenDetected: Boolean,
        autoTriggerArmed: Boolean,
        accessibilityEnabled: Boolean,
        installedApps: List<DetectedApp>,
        preference: CarLifeAutoPrefs.LaunchTarget,
        streamSize: Pair<Int, Int>? = null,
        zeroFrameStalledSec: Int = 0,
    ): StatusSnapshot {
        val preferred = resolvePreferred(installedApps, preference)
        val appName = preferred?.displayName ?: "CarWith / CarLife+"
        val sizeTag = streamSize?.let { " · ${it.first}×${it.second}" }.orEmpty()

        return when {
            !hijackEnabled ->
                StatusSnapshot(
                    state = EngineState.DISABLED,
                    badgeText = "已关闭",
                    shortLine = "CarLife 劫持已在设置里关闭 —— 不会有车机画面",
                    detailLine = "本地 CarLife 劫持开关处于关闭状态，开启后才能向车机提供画面。",
                    preferredApp = preferred,
                    installedApps = installedApps,
                )

            videoFlowingToCar ->
                StatusSnapshot(
                    state = EngineState.STREAMING_TO_CAR,
                    badgeText = "推流中",
                    shortLine = "车机画面来自 $appName（视频流正常$sizeTag）",
                    detailLine = "本机 $appName 已在后台运行并持续向车机直通视频与音频$sizeTag。",
                    preferredApp = preferred,
                    installedApps = installedApps,
                )

            isAttached ->
                StatusSnapshot(
                    state = EngineState.ATTACHED_NO_FLOW,
                    badgeText = "等帧中",
                    shortLine = "已接入 $appName，等待视频帧送达车机",
                    detailLine = "车机会话已接上本地通道，但暂未收到新帧；若持续黑屏请点「启动并退后台」重新激活。",
                    preferredApp = preferred,
                    installedApps = installedApps,
                )

            sourceActive ->
                StatusSnapshot(
                    state = EngineState.READY,
                    badgeText = "运行中",
                    shortLine = "✓ 已接管 $appName 画面（后台运行中$sizeTag）—— 可直接连接车机",
                    detailLine = "本机 $appName 已打开并在后台稳定出帧$sizeTag，点「启动连接」即可推到车机。",
                    preferredApp = preferred,
                    installedApps = installedApps,
                )

            isActive && zeroFrameStalledSec >= CarLifeHijack.ZERO_FRAME_STALL_SEC ->
                StatusSnapshot(
                    state = EngineState.ZERO_FRAME_STALLED,
                    badgeText = "零帧占口",
                    shortLine = "⚠ 7240 已连上 ${zeroFrameStalledSec}s 仍 0 帧 —— 疑似被后台僵死组件占口，点「清理占口」",
                    detailLine =
                        "已与本机 7240 握手 ${zeroFrameStalledSec}s 但持续零帧（encode=0）。" +
                            "常见原因是后台僵死的「小米 CarLife+ 组件」占着 7240 挡住了「百度 CarLife+」。" +
                            "点右侧「清理占口」去强行停止占口组件后，再启动即可秒出帧。",
                    preferredApp = preferred,
                    installedApps = installedApps,
                )

            isActive ->
                StatusSnapshot(
                    state = EngineState.HANDSHAKEN_WAITING_FRAMES,
                    badgeText = "等出帧",
                    shortLine = "已连上本机 7240 端口，等待 $appName 开始出帧…",
                    detailLine =
                        "已与本机 7240 握手，正在等待首帧画面（超过 ${CarLifeHijack.ZERO_FRAME_STALL_SEC}s 仍 0 帧将自动提示清理占口）。",
                    preferredApp = preferred,
                    installedApps = installedApps,
                )

            portOpenDetected ->
                StatusSnapshot(
                    state = EngineState.PORT_LISTENING,
                    badgeText = "握手中",
                    shortLine = "检测到 7240 端口已开放，正在接管 $appName…",
                    detailLine = "本机 $appName 已启动，正在建立本地接管通道。",
                    preferredApp = preferred,
                    installedApps = installedApps,
                )

            installedApps.isEmpty() ->
                StatusSnapshot(
                    state = EngineState.NOT_INSTALLED,
                    badgeText = "未安装",
                    shortLine = "未检测到小米 CarWith 或百度 CarLife+ —— 车机连上后将无画面",
                    detailLine = "请先确认手机已安装「小米 CarWith」或「百度 CarLife+」，再发起连接。",
                    preferredApp = null,
                    installedApps = emptyList(),
                )

            else ->
                StatusSnapshot(
                    state = EngineState.NOT_STARTED,
                    badgeText = if (autoTriggerArmed) "拉起中" else "未启动",
                    shortLine =
                        if (autoTriggerArmed) {
                            "正在拉起 $appName…"
                        } else {
                            "$appName 未运行（7240 未监听）—— 点「启动并退后台」直接拉起"
                        },
                    detailLine =
                        if (autoTriggerArmed) {
                            "已调起 $appName，正在等待 7240 端口开放；接管出帧后自动退入后台。"
                        } else {
                            "已检测到「$appName」，当前未启动（7240 未监听）。点右侧按钮或直接连接车机即可自动拉起并在后台建立虚拟屏。"
                        },
                    preferredApp = preferred,
                    installedApps = installedApps,
                )
        }
    }

    /** 采一份当前手机上的实时快照（轻量，不阻塞主线程网络 IO）。 */
    fun snapshot(
        context: Context,
        videoFlowingToCar: Boolean,
    ): StatusSnapshot {
        val installed = detectInstalled(context)
        val pref = CarLifeAutoPrefs.launchTarget(context)
        val ready = CarLifeHijack.sourceActive || CarLifeHijack.isStreaming
        val streamSize = CarLifeHijack.actualStreamSize ?: CarLifeHijack.videoSize.takeIf { ready }
        return buildSnapshot(
            hijackEnabled = CarLifeHijack.isEnabled(context),
            videoFlowingToCar = videoFlowingToCar,
            isAttached = CarLifeHijack.isAttached,
            sourceActive = ready,
            isActive = CarLifeHijack.isActive,
            portOpenDetected = CarLifeHijack.portOpenDetected,
            autoTriggerArmed = CarLifeAutoCoordinator.isArmed(),
            accessibilityEnabled = CarLifeAutoTriggerService.isEnabledInSettings(context),
            installedApps = installed,
            preference = pref,
            streamSize = streamSize,
            zeroFrameStalledSec = CarLifeHijack.zeroFrameStalledSec,
        )
    }

    private fun canResolveActivity(
        pm: PackageManager,
        intent: Intent,
    ): Boolean =
        runCatching {
            val component = intent.component
            if (component != null) {
                val info = pm.getActivityInfo(component, 0)
                return info.exported && info.enabled
            }
            val info = pm.resolveActivity(intent, 0)?.activityInfo ?: return false
            info.exported && info.enabled
        }.getOrDefault(false)
}
