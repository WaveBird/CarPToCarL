package io.github.shaonialife.carptocar.carlife

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.shaonialife.carptocar.EXTRA_MOVE_TO_BACKGROUND
import io.github.shaonialife.carptocar.MainActivity
import io.github.shaonialife.carptocar.SessionController

/**
 * CarWith / CarLife 自动拉起、无障碍协同与出帧后退后台总控（进程级单例）。
 *
 * 衔接四方：
 * 1. `MainActivity`（手动一键拉起 / 连接自动拉起 / 取消时撤防）；
 * 2. `CarLifeHijack`（首帧到达回调 + 7240 运行态）；
 * 3. `CarLifeAutoTriggerService`（可选无障碍服务：仅在武装窗口内自动点击无线连接，并在出帧后执行 `GLOBAL_ACTION_HOME`）；
 * 4. 兜底回退（未开启无障碍时，出帧后也能把 `MainActivity` 唤回或切后台，让 CarWith/CarLife 转入后台运行）。
 */
object CarLifeAutoCoordinator {
    enum class ReturnAction {
        /** 不主动切换前后台。 */
        NONE,

        /** 把 CarPToCarL 唤回前台（自然将 CarWith / CarLife 压入后台运行）。 */
        BRING_APP_BACK,

        /** 把 CarWith / CarLife（及本应用）整体退回桌面/后台，接近有线无感体验。 */
        GO_HOME,
    }

    enum class LaunchOutcome {
        LAUNCHED,
        ALREADY_RUNNING,
        NOT_INSTALLED,
        DISABLED,
        FAILED,
    }

    data class LaunchResult(
        val outcome: LaunchOutcome,
        val appName: String? = null,
        val detail: String? = null,
    )

    private const val AUTO_LAUNCH_COOLDOWN_MS = 25_000L
    private const val POLL_INTERVAL_MS = 500L
    private const val READY_SETTLE_DELAY_MS = 750L
    private const val CONNECT_GUARD_TIMEOUT_MS = 15_000L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val policy = CarLifeAutoTriggerPolicy()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var returnAction: ReturnAction = ReturnAction.NONE

    @Volatile
    private var armedDeadlineMs: Long = 0L

    @Volatile
    private var backgroundScheduled: Boolean = false

    @Volatile
    private var lastAutoLaunchAtMs: Long = 0L

    @Volatile
    private var launchedAppName: String = "CarWith / CarLife+"

    @Volatile
    private var pendingConnectCallback: (() -> Unit)? = null

    private val connectGuardTimeoutRunnable =
        Runnable {
            val callback =
                synchronized(lock) {
                    val cb = pendingConnectCallback
                    pendingConnectCallback = null
                    cb
                }
            if (callback != null) {
                SessionController.appendLog(
                    "CARLIFE-AUTO: 连接前等首帧超时（${CONNECT_GUARD_TIMEOUT_MS / 1000}s）——放行继续连接车机",
                )
                callback.invoke()
            }
        }

    private val readyPollRunnable =
        object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                val active = synchronized(lock) { armedDeadlineMs > 0L && now <= armedDeadlineMs }
                if (!active) {
                    disarm("等待超时（未在 25 秒内收到 CarLife 首帧）")
                    return
                }
                if (CarLifeHijack.sourceActive) {
                    onSourceFrameArrived()
                    return
                }
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    /** 绑定 `CarLifeHijack` 首帧回调（幂等，在 `MainActivity.onCreate` 调用）。 */
    fun bind(context: Context) {
        appContext = context.applicationContext
        CarLifeHijack.onSourceReady = { onSourceFrameArrived() }
    }

    /** 当前是否处于「等待 CarWith / CarLife 启动并出帧」的武装窗口内。 */
    fun isArmed(): Boolean {
        val now = SystemClock.uptimeMillis()
        return synchronized(lock) { armedDeadlineMs > 0L && now <= armedDeadlineMs }
    }

    /** 无障碍服务是否应在本轮窗口内执行自动点击。 */
    fun isAutoClickArmed(): Boolean {
        val now = SystemClock.uptimeMillis()
        return synchronized(lock) { policy.isArmed(now) && !backgroundScheduled }
    }

    /** 供无障碍服务查询下一步应点击的节点（线程安全）。 */
    fun decideNextClick(
        packageName: String?,
        nodes: List<CarLifeAutoTriggerPolicy.NodeSnapshot>,
    ): CarLifeAutoTriggerPolicy.ClickDecision? {
        if (CarLifeHijack.sourceActive) {
            onSourceFrameArrived()
            return null
        }
        val now = SystemClock.uptimeMillis()
        return synchronized(lock) {
            policy.nextClick(
                packageName = packageName,
                nodes = nodes,
                nowMs = now,
                sourceActive = false,
            )
        }
    }

    /**
     * 判断「连接车机时」是否应该自动拉起 CarWith / CarLife（不带副作用）。
     * 供 `MainActivity` 静默靠近触发时决定是否暂缓立即 `moveTaskToBack`。
     */
    fun shouldAutoLaunchOnConnect(context: Context): Boolean {
        if (!CarLifeHijack.isEnabled(context)) return false
        if (!CarLifeAutoPrefs.autoLaunchOnConnect(context)) return false
        if (CarLifeHijack.sourceActive || CarLifeHijack.isActive) return false
        val now = SystemClock.uptimeMillis()
        if (lastAutoLaunchAtMs > 0L && now - lastAutoLaunchAtMs < AUTO_LAUNCH_COOLDOWN_MS) return false
        return CarLifeLauncher.detectInstalled(context).isNotEmpty()
    }

    /**
     * 用户在 App 内手动点「启动并退后台」：
     * - 空闲时：拉起目标并武装，出帧后把 `CarPToCarL` 唤回前台（目标自然转入后台），方便直接看绿灯状态与发起连接；
     * - 连接中/已连接时：拉起目标并武装，出帧后直接回桌面/退后台。
     */
    fun launchManual(
        context: Context,
        log: (String) -> Unit,
        connectionActive: Boolean,
    ): LaunchResult {
        val action =
            when {
                !CarLifeAutoPrefs.autoBackgroundOnReady(context) -> ReturnAction.NONE
                connectionActive -> ReturnAction.GO_HOME
                else -> ReturnAction.BRING_APP_BACK
            }
        return launchInternal(
            context = context,
            log = log,
            returnOnReady = action,
            recordAutoCooldown = false,
            reason = "手动一键启动",
        )
    }

    /**
     * 发起车机连接（含启动后自动连接 / 靠近车机自动启动）时按需自动拉起 CarWith / CarLife。
     */
    fun maybeAutoLaunchOnConnect(
        context: Context,
        log: (String) -> Unit,
        silentMode: Boolean,
    ): Boolean {
        if (!shouldAutoLaunchOnConnect(context)) return false
        val action =
            when {
                !CarLifeAutoPrefs.autoBackgroundOnReady(context) -> ReturnAction.NONE
                silentMode || CarLifeAutoTriggerService.isConnected -> ReturnAction.GO_HOME
                else -> ReturnAction.BRING_APP_BACK
            }
        val result =
            launchInternal(
                context = context,
                log = log,
                returnOnReady = action,
                recordAutoCooldown = true,
                reason = if (silentMode) "靠近车机自动连接" else "连接车机自动拉起",
            )
        return result.outcome == LaunchOutcome.LAUNCHED
    }

    /**
     * 连接前「等首帧守卫」：发起车机连接前，若本机画面源尚未出帧且开启了自动拉起，
     * 先自动拉起目标（优先百度 CarLife+），等待收到真实首帧（上限 15 秒）并自动退后台后，
     * 再执行 [onProceed] 发起车机连接，避免 0 帧进 `RECORD` 被车机（如宝马 MGU）直接断开。
     *
     * @return true = 已进入等首帧守卫（将异步回调 [onProceed]）；false = 已就绪或无需等待，已同步执行 [onProceed]。
     */
    fun ensureSourceReadyThen(
        context: Context,
        log: (String) -> Unit,
        silentMode: Boolean,
        onProceed: () -> Unit,
    ): Boolean {
        val app = context.applicationContext
        appContext = app
        if (!CarLifeHijack.isEnabled(app) ||
            !CarLifeAutoPrefs.autoLaunchOnConnect(app) ||
            CarLifeHijack.sourceActive ||
            CarLifeHijack.isStreaming
        ) {
            if (CarLifeHijack.isStreaming && !CarLifeHijack.sourceActive) {
                CarLifeHijack.requestKeyFrame()
            }
            onProceed()
            return false
        }
        if (CarLifeHijack.isZeroFrameStalled) {
            log("CARLIFE-AUTO: 7240 当前处于持续零帧占口状态——跳过等首帧守卫，建议先点「清理占口」")
            onProceed()
            return false
        }
        if (CarLifeLauncher.detectInstalled(app).isEmpty()) {
            onProceed()
            return false
        }

        // 先确保 7240 守候已启动并挂上首帧回调
        CarLifeHijack.prewarm(app) { line -> SessionController.appendLog(line) }
        CarLifeHijack.onSourceReady = { onSourceFrameArrived() }

        val action =
            when {
                !CarLifeAutoPrefs.autoBackgroundOnReady(app) -> ReturnAction.NONE
                silentMode || CarLifeAutoTriggerService.isConnected -> ReturnAction.GO_HOME
                else -> ReturnAction.BRING_APP_BACK
            }

        synchronized(lock) {
            pendingConnectCallback = onProceed
        }
        mainHandler.removeCallbacks(connectGuardTimeoutRunnable)
        mainHandler.postDelayed(connectGuardTimeoutRunnable, CONNECT_GUARD_TIMEOUT_MS)

        // 若未在冷却期内且尚未拉起，主动调起目标应用
        val now = SystemClock.uptimeMillis()
        val inCooldown = lastAutoLaunchAtMs > 0L && now - lastAutoLaunchAtMs < AUTO_LAUNCH_COOLDOWN_MS
        if (!inCooldown) {
            val result =
                launchInternal(
                    context = context,
                    log = log,
                    returnOnReady = action,
                    recordAutoCooldown = true,
                    reason = if (silentMode) "靠近车机等首帧守卫" else "连接前等首帧守卫",
                )
            if (result.outcome == LaunchOutcome.ALREADY_RUNNING) {
                firePendingConnect("画面源已就绪")
                return false
            }
            if (result.outcome != LaunchOutcome.LAUNCHED) {
                firePendingConnect("拉起失败，直接继续连接")
                return false
            }
        } else {
            // 冷却期内只武装等待窗口，不重复闪开界面
            synchronized(lock) {
                armedDeadlineMs = now + CONNECT_GUARD_TIMEOUT_MS
                backgroundScheduled = false
                returnAction = action
            }
            mainHandler.removeCallbacks(readyPollRunnable)
            mainHandler.postDelayed(readyPollRunnable, POLL_INTERVAL_MS)
        }
        log("CARLIFE-AUTO: 连接前等首帧守卫已启动——等待本机出首帧（上限 ${CONNECT_GUARD_TIMEOUT_MS / 1000}s）后自动发起车机连接")
        return true
    }

    /** 主动撤防（用户取消连接/断开时调用，避免残留自动点击或延迟退后台）。 */
    fun disarm(reason: String? = null) {
        val wasArmed: Boolean
        synchronized(lock) {
            wasArmed = armedDeadlineMs > 0L || policy.isArmed(SystemClock.uptimeMillis()) || pendingConnectCallback != null
            armedDeadlineMs = 0L
            backgroundScheduled = false
            returnAction = ReturnAction.NONE
            pendingConnectCallback = null
            policy.disarm()
        }
        mainHandler.removeCallbacks(readyPollRunnable)
        mainHandler.removeCallbacks(connectGuardTimeoutRunnable)
        if (wasArmed && !reason.isNullOrBlank()) {
            SessionController.appendLog("CARLIFE-AUTO: 已结束等待窗口（$reason）")
        }
    }

    private fun firePendingConnect(note: String) {
        mainHandler.removeCallbacks(connectGuardTimeoutRunnable)
        val callback =
            synchronized(lock) {
                val cb = pendingConnectCallback
                pendingConnectCallback = null
                cb
            } ?: return
        SessionController.appendLog("CARLIFE-AUTO: $note → 立即放行发起车机连接")
        mainHandler.post { callback.invoke() }
    }

    private fun launchInternal(
        context: Context,
        log: (String) -> Unit,
        returnOnReady: ReturnAction,
        recordAutoCooldown: Boolean,
        reason: String,
    ): LaunchResult {
        val app = context.applicationContext
        appContext = app
        if (!CarLifeHijack.isEnabled(app)) {
            return LaunchResult(LaunchOutcome.DISABLED, detail = "本地 CarLife 劫持已在设置中关闭")
        }
        if (CarLifeHijack.sourceActive) {
            return LaunchResult(LaunchOutcome.ALREADY_RUNNING, detail = "本机画面源已在后台运行中")
        }
        // 先确保 7240 守候线程已经在跑，端口一开即秒接管
        CarLifeHijack.prewarm(app) { line -> SessionController.appendLog(line) }
        CarLifeHijack.onSourceReady = { onSourceFrameArrived() }

        val installed = CarLifeLauncher.detectInstalled(app)
        val preferred =
            CarLifeLauncher.resolvePreferred(installed, CarLifeAutoPrefs.launchTarget(app))
                ?: return LaunchResult(LaunchOutcome.NOT_INSTALLED, detail = "未检测到已安装的小米 CarWith 或百度 CarLife+")
        val intent =
            preferred.intent
                ?: return LaunchResult(LaunchOutcome.FAILED, appName = preferred.displayName, detail = "找不到可启动入口")

        val isDirectCarWith = preferred.intent?.component?.className == CarLifeLauncher.XIAOMI_CARWITH_DIRECT_ENTRY
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            armedDeadlineMs = now + CarLifeAutoTriggerPolicy.DEFAULT_WINDOW_MS
            backgroundScheduled = false
            returnAction = returnOnReady
            launchedAppName = preferred.displayName
            if (!isDirectCarWith) {
                policy.arm(now)
            } else {
                policy.disarm()
            }
            if (recordAutoCooldown) lastAutoLaunchAtMs = now
        }
        mainHandler.removeCallbacks(readyPollRunnable)
        mainHandler.postDelayed(readyPollRunnable, POLL_INTERVAL_MS)

        return runCatching {
            val launchIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            val a11yState =
                when {
                    isDirectCarWith -> "公开有线虚拟屏直连入口（无需无障碍点击）"
                    CarLifeAutoTriggerService.isConnected -> "无障碍助手已武装（自动点进无线连接）"
                    else -> "未开无障碍（若未自动出帧请手动点一次无线连接）"
                }
            val bgState =
                when (returnOnReady) {
                    ReturnAction.GO_HOME -> "出帧后自动退回桌面"
                    ReturnAction.BRING_APP_BACK -> "出帧后切回 CarPToCarL（目标转后台）"
                    ReturnAction.NONE -> "出帧后不切后台"
                }
            log("CARLIFE-AUTO: $reason → 已调起「${preferred.displayName}」（$a11yState · $bgState）")
            LaunchResult(LaunchOutcome.LAUNCHED, appName = preferred.displayName)
        }.getOrElse { error ->
            disarm()
            log("CARLIFE-AUTO: 调起「${preferred.displayName}」失败（${error.javaClass.simpleName}: ${error.message}）")
            LaunchResult(
                LaunchOutcome.FAILED,
                appName = preferred.displayName,
                detail = "调起失败：${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    /** 收到 CarLife 真实首帧时触发：立即撤防自动点击，放行待决车机连接，并在短暂稳定后把目标置入后台。 */
    internal fun onSourceFrameArrived() {
        val action: ReturnAction
        val targetName: String
        val hasPendingConnect: Boolean
        synchronized(lock) {
            hasPendingConnect = pendingConnectCallback != null
            if (armedDeadlineMs <= 0L && !hasPendingConnect) return
            if (backgroundScheduled && !hasPendingConnect) return
            backgroundScheduled = true
            policy.disarm()
            armedDeadlineMs = 0L
            action = returnAction
            returnAction = ReturnAction.NONE
            targetName = launchedAppName
        }
        mainHandler.removeCallbacks(readyPollRunnable)
        if (hasPendingConnect) {
            firePendingConnect("✓ 已收到 $targetName 真实首帧")
        }
        if (action == ReturnAction.NONE) {
            SessionController.appendLog("CARLIFE-AUTO: ✓ $targetName 已接管出帧（按设置保持当前界面）")
            return
        }
        SessionController.appendLog(
            "CARLIFE-AUTO: ✓ $targetName 已接管出帧——${READY_SETTLE_DELAY_MS}ms 后自动置于后台",
        )
        mainHandler.postDelayed(
            { performReturnAction(action, targetName) },
            READY_SETTLE_DELAY_MS,
        )
    }

    private fun performReturnAction(
        action: ReturnAction,
        targetName: String,
    ) {
        val app = appContext ?: return
        when (action) {
            ReturnAction.NONE -> Unit

            ReturnAction.GO_HOME -> {
                if (CarLifeAutoTriggerService.requestGoHome()) {
                    SessionController.appendLog("CARLIFE-AUTO: ✓ 已将「$targetName」退入后台（返回桌面）")
                    return
                }
                // 无障碍未开启时的兜底：唤起 MainActivity 并让它立刻 moveTaskToBack(true)
                val fallback =
                    Intent(app, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        )
                        putExtra(EXTRA_MOVE_TO_BACKGROUND, true)
                    }
                runCatching { app.startActivity(fallback) }
                    .onSuccess {
                        SessionController.appendLog("CARLIFE-AUTO: ✓ 已触发兜底退后台（让「$targetName」转入后台运行）")
                    }.onFailure {
                        SessionController.appendLog(
                            "CARLIFE-AUTO: ⚠ 自动退后台被系统限制（请开启无障碍或「显示在其他应用上层」）",
                        )
                    }
            }

            ReturnAction.BRING_APP_BACK -> {
                val backIntent =
                    Intent(app, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        )
                    }
                val brought = runCatching { app.startActivity(backIntent) }.isSuccess
                if (brought) {
                    SessionController.appendLog("CARLIFE-AUTO: ✓ 已回到 CarPToCarL（「$targetName」已在后台运行）")
                } else if (CarLifeAutoTriggerService.requestGoHome()) {
                    SessionController.appendLog("CARLIFE-AUTO: ✓ 已将「$targetName」退入后台（返回桌面）")
                } else {
                    SessionController.appendLog(
                        "CARLIFE-AUTO: ⚠ 切回 CarPToCarL 被系统限制——可手动切回，「$targetName」已就绪",
                    )
                }
            }
        }
    }
}
