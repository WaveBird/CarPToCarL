package io.github.shaonialife.carptocar.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.CarPlayUplinkHealth
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import io.github.shaonialife.carptocar.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）**主动连接车机**的无线 iAP2 RFCOMM 客户端 —— 真车阶段新增。
 *
 * 真无线 CarPlay 建链方向（对照 carplay-wifi-extractor client 模式，已在真车验证）：
 * 手机与车机完成系统级蓝牙配对后，由**手机主动连入**车机 SDP 广播的配件端服务
 * （UUID 00000000-deca-fade-deca-deafdecacaff，奥迪 MIB3 SDP 实测存在）；
 * 车机从不主动回连手机（测试台 DiPlay 会回连，那是接收端的测试行为）。
 *
 * 连上后复用与测试台完全相同的发送端 bring-up（[Iap2SenderSession]），
 * 成功时回调 [onEndpoint] 交出热点凭据与 AirPlay 端点。
 */
class Iap2BluetoothClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时交给主控热更新端点。 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 目标车机（动态读取：选择后无需重启链路，下一轮尝试即生效）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
    /** 「正在播放」信息提供者（歌名/歌手/专辑，见 [Iap2SenderSession]）。 */
    private val nowPlayingProvider: (() -> CarPlayNowPlaying?)? = null,
    /** 保活泵退出（BT 链路断开）时回调：主控据此释放「会话处理中」（僵尸轮次堵门修复）。 */
    private val onLinkClosed: (() -> Boolean) -> Unit = {},
) {
    companion object {
        /**
         * 配件端（车机）无线 iAP2 服务 UUID。
         * 手机端 UUID 是 …cafe（xcertplay IAP2_IPHONE_UUID），配件端是 …caff。
         */
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        private const val SESSION_TIMEOUT_MILLIS = 90_000L

        /**
         * 一轮连接结束后，到下一次尝试的**最小间隔**。
         *
         * **必须比车机的 `mediaDisconnectTimeout`（15000ms）长**——这是 4580 固件里
         * `Smartphone_Integrator` 的定值：蓝牙断开后，车机要等满 15 秒才把设备判成
         * 「已移除」。我们 3 秒就重连，等于每一次都把那个计时器续上，于是车机永远停在
         * ```
         * [CDeviceDetection] isDeviceActiveApp=%u, keepIAP2Session=%d
         * [CDeviceDetection] IAP2 session already running. Waiting for required IAP2 information.
         * ```
         * 这条分支（固件原文）：它既不重建会话，也不会重新协商 CarPlay/WiFi 状态——
         * 现场表现就是「断开后一直卡 WiFi / 卡 AirPlay」，只有冷启动的第一次能连。
         * 取 16 秒 = 15s + 1s 余量。
         */
        private const val RETRY_DELAY_MILLIS = 16_000L

        /**
         * 连续失败（含「连上即被关」）时的退避阶梯：16s → 20s → 24s → 30s → 45s → 60s，
         * 之后固定 60s。
         *
         * 起点就从 16 秒开始（见 [RETRY_DELAY_MILLIS]），别再退回秒级：密集探测既
         * 无用、又会不断把车机的「设备移除」计时器续上。本机的 …cafe 监听一直在跑，
         * 车机想连随时能连，放长间隔不会错过什么。
         *
         * 档位以前是 16/20/30 三档就跳到顶，坏目标上「一轮快一轮慢」反差太大；
         * 参考实现（link_plus `Iap2RetryBackoff`）用 3s 起步的倍增阶梯，那个下限
         * 在我们这条链路上会踩 15 秒计时器，故保留 16s 起步、把中间档补细。
         */
        /**
         * 连续失败时的退避阶梯（16s → 20s → 24s → 30s → 45s → 60s）的真源已抽到
         * [Iap2RetryPolicy]（单测锁住）。这里引用同一数组，保证循环里算出来的
         * 值与测试期望完全一致。
         *
         * 起点必须 ≥ 16 秒（车机 mediaDisconnectTimeout=15s + 1s 余量）。
         */
        private val RETRY_BACKOFF_MILLIS = Iap2RetryPolicy.RETRY_LADDER

        /** 「无效轮」判据的真源在 [Iap2RetryPolicy.nextStreak]：拿到过 0x4301/端点才算有效轮。 */
        private const val SESSION_USEFUL_MILLIS = 3_000L

        /** 退避睡眠的分片粒度：20 秒的退避也要能在 stop() 后立刻醒来。 */
        private const val BACKOFF_POLL_MILLIS = 250L

        private const val FAILURE_LOG_THROTTLE_MILLIS = 15_000L

        /**
         * 「车机靠近 → 提前重试」的最长等待：把当前这一轮退避截到这里为止。
         *
         * 物理靠近是「现在值得再试一次」的最强信号（车机刚醒/刚进无线配对窗口）。
         * 10s → 16s（2026-11-24 修复 #2，实车日志 L121→L142：NUDGE 生效轮实测 19.00s
         * 才重连）：截断值低于 16 秒时，车机 ACL 抖动时的每次抢占都会把重试间隔压到
         * mediaDisconnectTimeout（15s）以下——车机把我们当「还没走」，只重开旧会话
         * 不启动 projection。下限取 [Iap2RetryPolicy.sleepFloorMillis] 硬保证。
         */
        private const val NUDGE_MAX_WAIT_MILLIS = Iap2RetryPolicy.NUDGE_MAX_WAIT_MILLIS

        /**
         * 可发现窗口时长（秒，随 REQUEST_DISCOVERABLE 下发）。
         *
         * 对齐竞品 BOX OFF 的 300s（mcarplay CARWITH-ENTRY-FINDINGS.md）：系统默认 120s
         * 只覆盖一轮 BT 退避（阶梯 16→60s），车机晚到就错过窗口；300s 覆盖两轮以上。
         */
        private const val DISCOVERABLE_WINDOW_SECONDS = 300

        /**
         * 两次「提前重试」的最小间隔。
         *
         * 必须限流：车机可能每 30 秒抖一次 ACL，每次都催就等于把退避阶梯作废，
         * 重新变成密集探测（而密集探测会把车机的「设备移除」计时器一直续上）。
         */
        private const val NUDGE_MIN_INTERVAL_MILLIS = 60_000L

        /** 目标连不上多少轮后放开限制（16s/轮 → 约 32s 后回退，避免一个死目标堵死整轮）。 */
        private const val TARGET_FALLBACK_ROUNDS = 2

        /** 「蓝牙已连接→已断开」日志节流（车机可能反复自动重连，避免刷屏）。 */
        private const val PROFILE_LOG_THROTTLE_MILLIS = 15_000L

        /** SDP 服务探测等待窗（fetchUuidsWithSdp → ACTION_UUID 广播）。 */
        private const val SDP_PROBE_TIMEOUT_MILLIS = 2_500L

        /** SDP 探测重试次数：首次常撞上 cancelDiscovery 收尾，广播会晚于超时才到。 */
        private const val SDP_PROBE_ATTEMPTS = 2

        /**
         * 连续多少轮「主动方向在 SDP 层就失败」后切被动等车机连入。
         * 2 轮 × 16s ≈ 32 秒——SDP 里没有 …caff 说明车机没开无线窗口，主动侧再试
         * 也没用；而单轮间隔已经被 [RETRY_DELAY_MILLIS] 拉到 16 秒，轮数必须跟着降，
         * 否则光是切到被动就要干等 48 秒。
         */
        private const val PASSIVE_FALLBACK_ROUNDS = 2

        /**
         * 被动等待窗口：60s → 30s（2026-11-24 修复 #10）。
         *
         * 今晚实车（MGU-55-J）：车机回连全部发生在拆链后 **9 秒内**（L293/474/632/800/
         * 1001/1353 六次入站全成功）；60 秒窗里只有前 9 秒有用，后 51 秒是纯空等，
         * 还把整轮节奏拖慢一倍。
         */
        private const val PASSIVE_WAIT_MILLIS = 30_000L

        /** 被动窗口内的轮询粒度：也决定 stop()/车机连入的响应延迟。 */
        private const val PASSIVE_POLL_MILLIS = 2_000L

        // ---- 可发现续期（2026-11-24 修复 #3）----
        /**
         * 可发现窗口「剩多少」就续期：窗口总长 [DISCOVERABLE_WINDOW_SECONDS]（300s），
         * 剩余不足 60 秒（约一轮退避）时重请求一次，避免车机晚到错过窗口。
         */
        private const val DISCOVERABLE_RENEW_BEFORE_MILLIS = 60_000L

        /**
         * 后台自动恢复（不弹窗）下，连续多少轮拿不到「可发现」才打「请手动点启动连接」
         * 的升级提示——每轮都提醒等于刷屏，3 轮 ≈ 1 分钟够用户注意到日志/提示。
         */
        private const val DISCOVERABLE_ESCALATE_ROUNDS = 3
    }

    private var thread: Thread? = null
    private val currentSocket = AtomicReference<BluetoothSocket?>(null)

    /**
     * 客户端线程世代号：start()/stop() 都会递增。卡在阻塞 connect() 里的线程被
     * stop() 解除阻塞后（关闭 connecting socket），会因世代不符直接退出——
     * 防止「stop 后线程残活、用户再次启动时新旧两代并发连车机」
     * （2026-10-11 实测：多个僵尸线程并发 RFCOMM 会让车机频繁拆链）。
     */
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong(0)

    private val channelLock: Any = this

    /**
     * 本进程内是否**曾经成功建立过** CarPlay 会话（拿到端点即置位，永不清零）。
     *
     * 供 [targetBondedAndEverConnected] 区分「已配对且回连可信」与「bond 在但从未连通
     * （首配失败后重试，仍需可发现窗口）」。进程重启后回到 false——多弹一次可跳过的
     * 窗口，换取首配场景不漏，方向是对的。
     */
    @Volatile
    private var sessionEverEstablished: Boolean = false

    private fun ownsChannel(channel: Iap2LinkChannel): Boolean =
        activeChannel === channel && BtSessionGate.ownsControl(channel) && !channel.closedLocally

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    /** 当前保活阶段的发送端会话（bring-up 完成后由 runSenderSession 回填）。 */
    @Volatile
    private var senderSession: Iap2SenderSession? = null

    /**
     * 本次 start() 是不是用户手动「启动连接」——修复 #3（可发现续期）需要它在
     * connectLoop 每轮首部重新判断「该不该重请求可发现窗口」。后台自动恢复永远
     * 不弹窗（mcarplay 2026-10-26 实测：后台弹窗被 MIUI 静默吞掉，白弹一次）。
     */
    @Volatile
    private var interactiveStart: Boolean = true

    /**
     * 当前可发现窗口的截止时刻（uptimeMillis，0 = 没有在跑的窗口）。
     * 剩余 < [DISCOVERABLE_RENEW_BEFORE_MILLIS] 时在轮首续期。
     */
    @Volatile
    private var discoverableWindowUntilMillis = 0L

    /** 后台模式下连续多少轮没有可发现窗口了（达到 [DISCOVERABLE_ESCALATE_ROUNDS] 升级提示一次）。 */
    @Volatile
    private var backgroundDiscoverableFailRounds = 0

    /**
     * 「请手动点一次启动连接补可发现窗口」的升级提示是否已发（每轮 start() 一次，
     * 反复提醒只会刷屏）。
     */
    @Volatile
    var needsUserStartupHint: Boolean = false
        private set

    /**
     * 本轮 start() 里已经做过「断开配置文件 → 重连」的设备地址（修复 #4）。
     *
     * 2026-11-24 实车：同一轮循环里对同一台车机 <2s 内两次 `socket.connect()`
     * （第一次失败 → 断 HFP/A2DP → 立刻重拨），第二次拨号撞上车机还在恢复
     * 配置文件的窗口，只换来一次失败，还多断一次音频。每台设备每次 start()
     * 只允许一次「断配置」兜底，之后的轮次只直连。
     */
    private val profileDropTried = HashSet<String>()

    /**
     * 上一轮会话是否拿到过 0x4301/端点（修复 #1：failStreak 归零判据）。
     * 由 [runSession] 收尾回填 [Iap2RetryPolicy.nextStreak]。
     */
    @Volatile
    private var lastRoundGotEndpoint: Boolean = false

    @Volatile
    private var running = false

    /** 歌名变化时主动补推 0x5001（转发给当前保活会话）。 */
    fun pushNowPlaying() {
        senderSession?.pushNowPlaying()
    }

    /**
     * 车机 AirPlay 端口一直不开时，让 iAP2 会话重发一次 `0x4e0e + 0x4300` 叫醒它。
     *
     * 返回 false = 当前没有可用的发送端会话（链路已断/还没起来），调用方只能退而重公告 ctrl。
     */
    fun nudgeCarPlayAvailability(reason: String): Boolean =
        senderSession?.nudgeCarPlayAvailability(reason) ?: false

    /** 目标设备不在已配对列表的提示节流（避免每 3 秒刷屏）。 */
    private var lastTargetMissingLogAt = 0L

    /** 「无 iAP2 服务→跳过」日志去重（每地址一次即可，重复轮询不刷屏）。 */
    private val skippedNoService = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 「是否提供 iAP2 服务」结论缓存（地址→结论）：SDP 查询不便宜，勿每 3s 重查。 */
    private val iap2ServiceCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 非目标设备的「SDP 明确没有 …caff」软缓存（修复 #16，2026-11-24 实车 00:27:31：
     * BMW 71280 被 MISSING 永久拉黑，之后 3 分钟全打在索尼耳机上）。
     *
     * 地址 → [NoServiceRecord]（连续命中次数 + 首次判定时刻）。连续 [NO_SERVICE_CONFIRM_ROUNDS]
     * 次且在 [NO_SERVICE_TTL_MILLIS] 内才允许跳过；过期/未达标重查——车机固件晚开
     * 无线窗口、SDP 缓存陈旧，都给一条出路。**显式目标永不进这张表**（见修复 #16）。
     */
    private class NoServiceRecord(
        var hits: Int = 0,
        var firstAtMillis: Long = 0L,
    )

    private val noServiceSoft = java.util.concurrent.ConcurrentHashMap<String, NoServiceRecord>()

    /** 「SDP 明确没有 …caff」要连续几次才允许跳过（1 次太武断，实车已证明会误杀）。 */
    private val NO_SERVICE_CONFIRM_ROUNDS = 2

    /** 软跳过结论的存活时间：过这个点就算没满 2 次也重查（固件/窗口可能变了）。 */
    private val NO_SERVICE_TTL_MILLIS = 60_000L

    /**
     * 最近一次 …caff 连接失败的原始原因（IOException 摘要）。
     *
     * 只打「连接失败」时无法区分三种完全不同的情况：
     * `Service discovery failed`（SDP 里就没有该服务 = 车机没开无线 CarPlay 窗口）、
     * `Connection refused`（有服务但被拒）、`read failed / timeout`（车机卡住）。
     * 2026-10-26 雪铁龙日志就卡在这一层——只有「失败」两字，判不出是哪一种。
     */
    @Volatile
    private var lastConnectError: String? = null

    /**
     * 最近一次失败是不是**SDP 层**失败（`createRfcommSocketToServiceRecord` 直接抛，
     * 即车机 SDP 里根本没有 …caff）。
     *
     * 与「连上了但被拒 / 超时」区分开：只有这一类才值得切「被动方向」——
     * 车机既然没广播配件端服务，主动一侧再怎么重试都是空转，而由车机反向连入
     * 本机的 …cafe 监听仍有可能成功（参考实现 CarWith-CarPlay 的
     * `automatic direction fallback to car-initiated transport` 走的就是这一层）。
     */
    @Volatile
    private var lastFailureWasSdpLookup = false

    /**
     * SDP 闸门结论。
     *
     * 2026-10-26 实车日志（凯迪拉克 CADILLAC_7731）暴露的问题：`ACTION_UUID` 广播在部分机型
     * 上根本不来，旧实现「等不到广播 = 没有 iAP2 服务」，于是自动模式把真车机当耳机跳过
     * ——日志里同一台车 13 秒前被判「非 CarPlay 设备」，13 秒后手动指定目标就连上了。
     * 因此把「问不出来」与「明确没有」分开：只有 SDP 真的回了答案且不含 …caff 才算 MISSING。
     */
    private enum class Iap2Service { HAS, MISSING, UNKNOWN }

    /**
     * 设备是否提供配件端 iAP2 服务（…caff）：先读系统 uuids 缓存，缺失再
     * `fetchUuidsWithSdp` 触发 SDP 查询并等 `ACTION_UUID` 广播。
     *
     * **广播一次都没来 → [Iap2Service.UNKNOWN]**（不是「没有」）；只有收到广播且 uuids 里
     * 确实没有 …caff 才是 [Iap2Service.MISSING]。结论缓存到客户端停止——车机固件升级换
     * UUID 的场景由重启 App 覆盖。
     * 权限：start() 已校验 BLUETOOTH_CONNECT；uuids/fetchUuidsWithSdp 的
     * SecurityException 由 runCatching 吸收。
     */
    @SuppressLint("MissingPermission")
    private fun awaitIap2Service(device: BluetoothDevice): Iap2Service {
        iap2ServiceCache[device.address]?.let { return if (it) Iap2Service.HAS else Iap2Service.MISSING }

        fun cachedHas(): Boolean =
            runCatching {
                // `device.uuids` 是 List<ParcelUuid>，ParcelUuid.equals() 只认 ParcelUuid，
                // 直接和 UUID 比较**恒为 false** —— 这会让「SDP 明明有 iAP2」的车机被判成
                // MISSING 而整轮跳过（同文件排序处用的是 `it.uuid == ...`）。
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }.getOrDefault(false)

        if (cachedHas()) {
            iap2ServiceCache[device.address] = true
            return Iap2Service.HAS
        }
        // 允许重试：第一次查询常撞上 cancelDiscovery 的收尾，广播会晚于超时才到。
        repeat(SDP_PROBE_ATTEMPTS) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val receiver =
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(
                        receiverContext: Context,
                        intent: android.content.Intent,
                    ) {
                        @Suppress("DEPRECATION")
                        val hit = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (hit?.address == device.address) latch.countDown()
                    }
                }
            var answered = false
            val started =
                runCatching {
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        android.content.IntentFilter(BluetoothDevice.ACTION_UUID),
                        ContextCompat.RECEIVER_EXPORTED,
                    )
                    device.fetchUuidsWithSdp()
                }.getOrDefault(false)
            if (started) {
                try {
                    answered = latch.await(SDP_PROBE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    runCatching { context.unregisterReceiver(receiver) }
                    return Iap2Service.UNKNOWN
                }
            }
            runCatching { context.unregisterReceiver(receiver) }
            if (answered) {
                val has = cachedHas()
                iap2ServiceCache[device.address] = has
                return if (has) Iap2Service.HAS else Iap2Service.MISSING
            }
        }
        return Iap2Service.UNKNOWN
    }

    /**
     * 像个人音频设备（耳机/音箱/便携音响）——这类**绝不**为了探 iAP2 去断它的音频，
     * 也不该被当成车机候选。
     *
     * 车机常见的是 HANDSFREE / CAR_AUDIO / UNCATEGORIZED，都不会命中音频子类；
     * 但 Android 16 上 `bluetoothClass` 经常拿不到（LE Audio 耳机尤其），旧实现
     * 「拿不到就当不是耳机」，把 Redmi Buds 5 Pro 放进了车机候选，反复拨 …caff
     * 还顺手断它的 A2DP/HFP。现在按 [PersonalAudioClassifier] 补一层名字兜底。
     */
    @SuppressLint("MissingPermission")
    private fun looksLikePersonalAudio(device: BluetoothDevice): Boolean {
        val deviceClass = runCatching { device.bluetoothClass?.deviceClass }.getOrNull()
        val name =
            try {
                device.name
            } catch (_: SecurityException) {
                null
            }
        return PersonalAudioClassifier.looksLikePersonalAudio(deviceClass, name)
    }

    /**
     * 「SDP 说没有 …caff」的软跳过判定（修复 #16）：同一地址连续
     * [NO_SERVICE_CONFIRM_ROUNDS] 次 MISSING 才允许跳过，且结论 60s 过期——
     * 过期或未达标都返回 false（继续探测/拨号）。命中时推进计数。
     */
    private fun shouldSkipForNoService(address: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val record = noServiceSoft.compute(address) { _, existing ->
            if (existing == null) {
                NoServiceRecord(hits = 1, firstAtMillis = now)
            } else if (now - existing.firstAtMillis > NO_SERVICE_TTL_MILLIS) {
                NoServiceRecord(hits = 1, firstAtMillis = now)
            } else {
                existing.hits++
                existing
            }
        }
        return record != null && record.hits >= NO_SERVICE_CONFIRM_ROUNDS
    }

    /**
     * 修复 #4 的配额判定：该设备本次 start() 里还能不能做「断配置→重连」。
     * 首次申请返回 true 并占坑；之后每轮返回 false（只直连，不再动它的音频）。
     */
    private fun canDropProfilesOnce(address: String): Boolean =
        synchronized(profileDropTried) { profileDropTried.add(address) }

    /** 「蓝牙已连接→已断开」日志节流时间戳。 */
    private var lastProfileLogAt = 0L

    /** 有外部信号（车机蓝牙靠近）要求提前结束本轮退避。 */
    @Volatile
    private var nudgePending = false

    /** 上次「提前重试」的时刻（uptimeMillis），用于限流。 */
    @Volatile
    private var lastNudgeMillis = 0L

    /**
     * 车机靠近等外部信号：把本轮剩余退避截到最多 [NUDGE_MAX_WAIT_MILLIS]。
     *
     * 为什么需要它：车机刚进无线配对窗口时通常只有很短一段可连期，而退避阶梯
     * 上限是 60 秒——不催的话最容易错过那一小段。
     *
     * @return false = 没有在跑的重试循环，或 60 秒内刚催过一次（限流）
     */
    fun requestImmediateRetry(reason: String): Boolean {
        if (!running) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (lastNudgeMillis != 0L && now - lastNudgeMillis < NUDGE_MIN_INTERVAL_MILLIS) return false
        lastNudgeMillis = now
        nudgePending = true
        log("BT-C: $reason——本轮退避最多再等 ${NUDGE_MAX_WAIT_MILLIS / 1000} 秒就重试")
        return true
    }

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    /**
     * 启动连接循环。
     *
     * @param interactiveStart **用户手动「启动连接」= true**（默认）：允许在需要时弹
     *   「可被发现」系统弹窗——用户正看着屏幕，弹窗是可理解的交互。后台自动恢复
     *   （解冻重拉、进程重启续链）必须传 **false**：用户多半没在看手机，弹窗要么被
     *   忽略、要么被 MIUI「后台弹出界面」静默吞掉（mcarplay 2026-10-26 实测），白弹
     *   一次还可能盖住正在展示的其它界面。
     */
    fun start(interactiveStart: Boolean = true): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT-C: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT-C: 蓝牙不可用或未开启")
            return false
        }
        // 每轮启动重置 SDP 结论与跳过日志（新配对/换固件后重新探测）
        iap2ServiceCache.clear()
        skippedNoService.clear()
        // 1.2.108：直达路径状态随 start() 重置——首轮 SDP 标志、连续 socket 失败
        // 计数、bondedDevices 兜底复查标记、轮次计数都回到初值。
        directPolicy.reset()
        directRoundCount = 0
        // 修复 #16：软黑名单一并清空——上次进程内判「没有 iAP2 服务」的设备可能已经
        // 打开无线窗口（固件晚启动是常态），重启 start() 就该给它重新被探到的机会。
        noServiceSoft.clear()
        // 修复 #4：「断配置 → 重连」的每设备一次配额随 start() 重置
        synchronized(profileDropTried) { profileDropTried.clear() }
        // 修复 #3：交互标记落字段（connectLoop 轮首续期要用），升级提示重新计时
        this.interactiveStart = interactiveStart
        needsUserStartupHint = false
        backgroundDiscoverableFailRounds = 0
        BtSessionGate.targetSoftFallback = false
        BtSessionGate.clearRemoteDisconnectGrace()
        // 用户主动「启动连接」= 给车机端健康判定一次干净的重来机会（可能刚重启过车机）。
        CarPlayUplinkHealth.reset()
        running = true
        val myGeneration = generation.incrementAndGet()
        val target = targetProvider()
        log(
            "BT-C: 启动——将主动连入车机的无线 iAP2 服务（…caff）" +
                if (target != null) {
                    "，目标：「${target.name}」${target.address}（只连该设备）"
                } else {
                    "（未指定目标：依次尝试全部候选设备，可在「连接准备」中指定）"
                },
        )
        ensureDiscoverableWindow(target, allowSystemDialog = interactiveStart)
        Thread({ connectLoop(myGeneration) }, "iap2-bt-client").apply {
            isDaemon = true
            thread = this
            start()
        }
        return true
    }

    /**
     * 确保本机处于「可被发现」窗口（首次配对的前提，mcarplay 竞品对照结论）。
     *
     * ## 为什么需要它
     *
     * 2026-11-22 小米 17PM 实测：平时适配器是 `SCAN_MODE_CONNECTABLE`——**只可连接、
     * 不可被发现**，车机在自己的蓝牙列表里扫不到本机；只有打开系统蓝牙设置页（系统
     * 自动临时置为可发现）或发 `REQUEST_DISCOVERABLE` 弹窗确认后，才会翻成
     * `SCAN_MODE_CONNECTABLE_DISCOVERABLE`。「到蓝牙设置页才能被搜到」正是这个原因。
     *
     * 竞品 BOX OFF 的做法（见 mcarplay `CARWITH-ENTRY-FINDINGS.md` §蓝牙公告，2026-11-16
     * 静态分析）：无指定目标、开始等待前发 `REQUEST_DISCOVERABLE` 300 秒；已处于可发现
     * （`getScanMode()==23`）时**复用现有窗口、不重复弹窗**。这里照抄同一套判据，外加
     * 三条自己的边界：
     *
     *  - [allowSystemDialog]=false（后台自动恢复）→ 绝不弹窗：已可发现就复用，否则只
     *    打一行日志提示用户手动点一次「启动连接」才能补上首配窗口；
     *  - 「已配对」≠「已连通」：bond 存在但从未成功建立过 CarPlay 会话时，车机可能还没
     *    记住本机的无线 CarPlay 能力（首配失败后重试的常态），仍要给窗口；只有
     *    「已配对 **且** 曾经成功连上」才走纯回连、免弹窗。
     *  - 修复 #3：窗口有寿命（300s 只覆盖两轮退避），connectLoop 每轮首部按
     *    [discoverableWindowUntilMillis] 判「剩 <60s 就续期」——用户点完启动连接后
     *    坐进车里等红灯的 5 分钟里，窗口不再静默过期。
     *
     * @return true = 此刻本机处于可发现状态（或复用了系统窗口）；false = 请求失败/未发起
     */
    @SuppressLint("MissingPermission")
    private fun ensureDiscoverableWindow(
        target: TargetDevicePrefs.Target?,
        allowSystemDialog: Boolean,
    ): Boolean {
        val adapter =
            runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
            ?: return false
        val scanMode =
            runCatching { adapter.scanMode }.getOrDefault(BluetoothAdapter.SCAN_MODE_NONE)
        if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            log("BT-C: 本机已可被发现（scanMode=23）——复用现有窗口，不再请求")
            // 系统窗口的到期时刻我们看不到：按「再来一整个窗口」记账，下一轮再据实判定。
            discoverableWindowUntilMillis =
                android.os.SystemClock.uptimeMillis() + DISCOVERABLE_WINDOW_SECONDS * 1_000L
            return true
        }
        // 审计 A1（docs/flow-audit-20261125.md）：已保存目标且已配对 → 免窗口。
        // 车机走「回连」（它回连 …cafe 或接受本机拨号 …caff），CONNECTABLE 就够，
        // 可发现窗口对这个场景不提供任何新信息。1.2.93 的「已配对在场不弹窗」与
        // 1.2.94 的「bond+曾连通免弹」由此统一收进 directPolicy 同一判定入口
        // （shouldRequestDiscoverableWindow），不再维护两套判断。
        val savedTargetBonded = target != null && isSavedTargetBonded(adapter, target)
        if (target != null && !directPolicy.shouldRequestDiscoverableWindow(savedTargetBonded)) {
            log("BT-C: 已保存目标已配对——跳过可发现窗口请求（scanMode 保持 CONNECTABLE）")
            return true
        }
        if (target != null && targetBondedAndEverConnected(adapter, target)) {
            // 已配对且曾经连上：车机走「回连」路径，CONNECTABLE 就够，不弹窗打扰。
            return true
        }
        if (!allowSystemDialog) {
            log(
                "BT-C: 本机当前不可被发现，且本次是后台自动恢复（不弹窗）——" +
                    "若车机需要重新扫描配对本机，请手动点一次「启动连接」",
            )
            return false
        }
        return runCatching {
            context.startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_WINDOW_SECONDS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            discoverableWindowUntilMillis =
                android.os.SystemClock.uptimeMillis() + DISCOVERABLE_WINDOW_SECONDS * 1_000L
            log(
                "BT-C: 已请求可发现窗口（系统弹窗 ${DISCOVERABLE_WINDOW_SECONDS}s）——" +
                    "首次配对请在车机列表里选中本机",
            )
            true
        }.getOrElse { error ->
            log("BT-C: 可发现窗口请求失败（${error.javaClass.simpleName}）——已配对回连不受影响")
            false
        }
    }

    /**
     * 目标车机是否「已配对 **且** 曾经成功连上」。
     *
     * 只查 bond 不够：首配失败的轮次里 bond 可能已建立（系统级配对成功）但 CarPlay
     * 会话从没跑通过——此时车机侧仍可能不认本机，仍需要可发现窗口让它重新扫描。
     * 「曾经成功」以 [sessionEverEstablished] 为准（进程内存活；进程重启后回退为
     * 「只查 bond」——重启后首配窗口多给一次，代价只是一次可跳过的弹窗）。
     */
    /**
     * 保存目标是否已在 [BluetoothAdapter.bondedDevices] 中命中（地址匹配）。
     * 审计 A1（docs/flow-audit-20261125.md）的免窗口判据：只看 bond 存在与否，
     * 不要求 `sessionEverEstablished`——进程重启后它回到 false，旧判据会在
     * 「已配对+上次会话成功」的场景多打升级提示甚至多弹一次可跳过的系统窗。
     */
    @SuppressLint("MissingPermission")
    private fun isSavedTargetBonded(
        adapter: BluetoothAdapter,
        target: TargetDevicePrefs.Target,
    ): Boolean =
        runCatching {
            adapter.bondedDevices.any { TargetDevicePrefs.matches(target, it.address) }
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun targetBondedAndEverConnected(
        adapter: BluetoothAdapter,
        target: TargetDevicePrefs.Target,
    ): Boolean =
        runCatching {
            val bonded = adapter.bondedDevices.any { TargetDevicePrefs.matches(target, it.address) }
            bonded && sessionEverEstablished
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun connectLoop(myGeneration: Long) {
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            } ?: return
        var lastFailureLogAt = 0L
        var targetFailRounds = 0
        /** 连续「SDP 层失败」的轮数（见 [PASSIVE_FALLBACK_ROUNDS]）。 */
        var passiveFailRounds = 0
        /** 连续「没有有效会话」的轮数，用于 [RETRY_BACKOFF_MILLIS] 退避（连上即断也算）。 */
        var failStreak = 0
        /** 免窗口日志只打一条（审计 A1）：false=还没打过，true=已打过，bond 解除时复位重打。 */
        var directRoundDiscoverableSkipLogged = false
        while (running && generation.get() == myGeneration) {
            // ---- 修复 #3：可发现窗口续期 ----
            // 首配场景的窗口有寿命（300s 只覆盖两轮退避）；剩不足 60 秒（约一轮退避）
            // 就在轮首补一次请求，别让车机晚到时窗口已静默过期。后台模式照旧不弹窗，
            // 只累计失败轮数、到阈值打一次「请手动点启动连接」的升级提示。
            //
            // 审计 A1（docs/flow-audit-20261125.md）：续期与请求共用同一个免窗口判据
            // （directPolicy.shouldRequestDiscoverableWindow，bonded 命中即免）——
            // 已保存目标+已配对时车机走回连，每轮续期只提供 binder 成本不提供新信息。
            if (targetProvider() != null || !sessionEverEstablished) {
                val renewTarget = targetProvider()
                val savedTargetBonded =
                    renewTarget?.let {
                        isSavedTargetBonded(adapter, it)
                    } ?: false
                if (!directPolicy.shouldRequestDiscoverableWindow(savedTargetBonded)) {
                    if (!directRoundDiscoverableSkipLogged) {
                        log("BT-C: 已保存目标已配对——跳过可发现窗口续期（scanMode 保持 CONNECTABLE）")
                        directRoundDiscoverableSkipLogged = true
                    }
                } else {
                    directRoundDiscoverableSkipLogged = false
                    val now = android.os.SystemClock.uptimeMillis()
                    val windowLeft = discoverableWindowUntilMillis - now
                    if (windowLeft < DISCOVERABLE_RENEW_BEFORE_MILLIS) {
                        val renewed =
                            ensureDiscoverableWindow(targetProvider(), allowSystemDialog = interactiveStart)
                        if (renewed) {
                            backgroundDiscoverableFailRounds = 0
                        } else if (!interactiveStart) {
                            backgroundDiscoverableFailRounds++
                            if (backgroundDiscoverableFailRounds == DISCOVERABLE_ESCALATE_ROUNDS &&
                                !needsUserStartupHint
                            ) {
                                needsUserStartupHint = true
                                log(
                                    "BT-C: ⚠ 后台已连续 $backgroundDiscoverableFailRounds 轮拿不到「可发现」窗口——" +
                                        "首次配对（或车机重新扫描）需要本机可被发现，请在 App 点一次「启动连接」" +
                                        "（会弹系统确认框）或到系统蓝牙设置打开可发现；仅回连（已配对）不受影响",
                                )
                            }
                        }
                    }
                }
            }
            // ---- 修复 #11：车机侧拆链后的冷却（与手动断开同级）----
            // 旧实现只在「用户手动断开」给了 18 秒静默；车机自己关链（bt socket closed,
            // read return: -1）完全不管，1~7 秒后就带着残留状态重开——车机固件
            // mediaDisconnectTimeout=15s 还没把它判成「已移除」，照旧只走
            // keepIAP2Session=1 的「重开旧会话」分支（今晚实车 L460/L790/L992 三轮实测）。
            val sinceRemote = BtSessionGate.sinceRemoteDisconnect()
            if (sinceRemote < RETRY_DELAY_MILLIS) {
                log(
                    "BT-C: 车机 ${sinceRemote / 1000}s 前刚拆链——补足 ${RETRY_DELAY_MILLIS / 1000}s 静默再拨" +
                        "（固件 mediaDisconnectTimeout=15s，早了它只重开旧会话）",
                )
                sleepQuietly(RETRY_DELAY_MILLIS - sinceRemote + 200)
                continue
            }
            // 用户刚手动断开 → 先等车机「忘掉」上一轮，别急着拨。
            //
            // 2026-11-04 奥迪 4910（MIB3 4580）实录：16:22:32.519 手动断开，
            // 16:22:33.696（1.2 秒后）重连。车机照常给了 iAP2 身份、0x5703 热点凭据，
            // 但 **48 秒里一次 `_airplay._tcp` 都没广播、0x4301 也没回**——因为固件里
            // `mediaDisconnectTimeout = 15000ms`，设备没被判「已移除」之前它只走
            // `keepIAP2Session = 1` 分支：重开旧会话，不启动 projection。
            // 结果是我们拿网关兜底、8 次重连全超时（fe80::1086:…:7000 after 2000ms）。
            //
            // 等够 [BtSessionGate.REACTIVATE_COOLDOWN_MILLIS] 再拨，这一次才是「新建会话」。
            val cooldownLeft = BtSessionGate.REACTIVATE_COOLDOWN_MILLIS - BtSessionGate.sinceManualStop()
            if (cooldownLeft > 0) {
                log(
                    "BT-C: 距上次手动断开只有 ${BtSessionGate.sinceManualStop() / 1000} 秒——" +
                        "车机要 ${BtSessionGate.REACTIVATE_COOLDOWN_MILLIS / 1000} 秒才认为设备已移除" +
                        "（固件 mediaDisconnectTimeout=15s），先等 ${(cooldownLeft + 999) / 1000} 秒再发起" +
                        "（早了它只重开旧会话：给热点凭据，但不启动 AirPlay）",
                )
                sleepQuietly(cooldownLeft + 200)
                continue
            }
            // 车机已回连时 outboundPassiveWaitMillis() 为 0：否则 sleepPassiveGrace 立即返回、这里空转刷日志
            val passiveLeft = BtSessionGate.outboundPassiveWaitMillis()
            if (passiveLeft > 0) {
                log(
                    "BT-C: 对端刚断开会话——先被动等待 ${(passiveLeft + 999) / 1000} 秒让车机主动回连" +
                        "（避免出站拨号与车机回连发生双向 RFCOMM 碰撞）",
                )
                sleepPassiveGrace(passiveLeft, myGeneration)
                continue
            }
            // 静默降噪（2026-10-10 用户实测后启用）——会话期间完全静默，不产生任何连接尝试/日志：
            //  - inboundActive：接收端正在连我们（两条 RFCOMM 会互相踩；Audi 实测双会话混乱）
            //  - sessionActive：CarPlay 会话已建立（已拿到端点）——与其它接收端的连接尝试全部暂停
            //    典型场景：连着 Redmi/DiPlay 时不再无脑探测车机 Audi（消除日志噪音与蓝牙churn）；
            //    会话结束（任一端 BT 会话终结 / 手动断开）时自动恢复。
            //
            // **例外（2026-11-04）**：CarPlay 在跑，但跟它同一台车的 iAP2 控制链路中途掉了
            // （`packet N not acknowledged` / 车机停 ACK）。这时必须对它单独重连，否则整场
            // 会话都拿不到车机控制数据。只认那一台的地址，不是放开探测。
            // CarPlay 都结束了就不存在「补连控制链路」这回事——顺手清掉残留登记，
            // 免得下一次连的是另一台车时被旧地址带偏。
            if (!BtSessionGate.sessionActive && BtSessionGate.linkLostAt != 0L) {
                BtSessionGate.clearLinkLost()
            }
            val recoveryPeers = BtSessionGate.linkRecoveryTarget()
            if (BtSessionGate.inboundActive || (BtSessionGate.sessionActive && recoveryPeers == null)) {
                // 走到这里且还挂着「掉线登记」的，只可能是恢复窗口已过（linkRecoveryTarget 返回 null）
                if (BtSessionGate.sessionActive && BtSessionGate.linkLostPeerAddress != null) {
                    BtSessionGate.clearLinkLost()
                    log(
                        "BT-C: 控制链路 ${BtSessionGate.LINK_RECOVERY_WINDOW_MILLIS / 1000} 秒内没连回来" +
                            "——停止补连（CarPlay 画面/声音不受影响，下次整轮连接再试）",
                    )
                }
                sleepQuietly(2_000)
                continue
            }
            // 直达路径精简日志（可选 debug 级）：每轮一条，说明本轮跳过了列表扫描。
            // directRound 判定提前到候选选取之前（1.2.108）。
            val directRound = recoveryPeers == null &&
                targetProvider() != null &&
                !BtSessionGate.targetSoftFallback
            if (directRound) {
                directRoundCount++
                log(
                    "BT-C: 已保存目标直达拨号 ${targetProvider()?.name ?: "?"}" +
                        "（跳过列表扫描，第 $directRoundCount 轮）",
                )
            }
            val candidates =
                if (recoveryPeers == null) {
                    candidateDevices(adapter)
                } else {
                    // 1.2.108：恢复补连路径保持现状——它本来就走「按地址直达」语义。
                    // 注意此处仍需 bondedDevices 匹配（直达目标可能被删配对后补连无意义），
                    // 故显式以 LEGACY 模式取列表再按地址过滤，不经直达单元素路径。
                    val bonded =
                        try {
                            adapter.bondedDevices?.toList().orEmpty()
                        } catch (_: SecurityException) {
                            emptyList()
                        }
                    val hit = bonded.filter { it.address.equals(recoveryPeers, ignoreCase = true) }
                    if (hit.isEmpty()) {
                        log("BT-C: 要补连的控制链路目标「$recoveryPeers」已不在已配对列表——放弃补连")
                        BtSessionGate.clearLinkLost()
                        sleepQuietly(2_000)
                        continue
                    }
                    hit
                }
            if (candidates.isEmpty()) {
                log(
                    "BT-C: 本轮没有可尝试的候选设备——请确认车机已在系统设置里完成蓝牙配对" +
                        "（耳机/音箱已按音频设备排除，不再作为车机候选）",
                )
                sleepQuietly(RETRY_DELAY_MILLIS)
                continue
            }
            var connected: BluetoothSocket? = null
            // 本轮有几台设备是「SDP 层就没连上」（不是被拒、不是超时）。累计到
            // [PASSIVE_FALLBACK_ROUNDS] 轮就切被动方向（车机主动连入）。
            var sdpLookupFailures = 0
            for (device in candidates) {
                if (!running || generation.get() != myGeneration) return
                val name =
                    try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    } ?: device.address
                runCatching { adapter.cancelDiscovery() }
                // 自动模式（未选目标）先过 SDP 闸门：候选=全部已配对设备，耳机/手表
                // 没有 iAP2 服务，连不上还白白被断 HFP/A2DP（设备级断开兜底更会波及
                // 其它在线设备）——确认提供 …caff 才进入「断开+连接」流程。选定目标
                // 的设备不过闸（用户显式指定，保持原路径与时序）。
                // 1.2.108 直达路径：有保存目标且未软回退时，「SDP 闸门」只做**首轮一次**
                // （拿诊断结论；2026-11-23 实车证据：SDP 结论值得留档，但每轮都查会误拉黑
                // 真车机 3 分钟）。首轮之后一律跳过 SDP 直接拨 …caff。
                // 显式目标 MISSING 仍只记日志不跳过（1.2.106 起的豁免，保持生效）。
                val gate =
                    if (targetProvider() == null || BtSessionGate.targetSoftFallback) {
                        // 软回退（目标连不上、放开限制）也过闸：否则会把已配对的耳机、
                        // 蓝牙键盘（AB Shutter3）、音箱全试一遍，既断别人的音频又拖慢
                        // 重试节奏（2026-10-26 雪铁龙日志实测）。
                        awaitIap2Service(device)
                    } else if (directRound && !directPolicy.directSdpChecked) {
                        val result = awaitIap2Service(device)
                        // 无论结论如何，首轮只此一次：后续轮不再做 SDP 查询。
                        directPolicy.noteDirectSdpDone()
                        if (result == Iap2Service.MISSING) {
                            log(
                                "BT-C: 直达首轮 SDP：目标「$name」明确不含 iAP2 服务——按显式目标豁免，" +
                                    "照常拨号（SDP 缓存可能陈旧；固件晚开无线窗口是常态）",
                            )
                        }
                        result
                    } else {
                        // 直达第 2 轮起（或非直达的显式目标）：跳过 SDP，直接拨。
                        Iap2Service.HAS
                    }
                if (gate == Iap2Service.MISSING) {
                    // 修复 #16：MISSING 不再永久拉黑。2026-11-24 实车 00:27:31：
                    // BMW 71280 被「SDP 明确不含 iAP2」判死后 3 分钟全打在索尼耳机上
                    // （L170 → L176/195/208/226）。现在只记软缓存（连续
                    // [NO_SERVICE_CONFIRM_ROUNDS] 次且 60s TTL 内才跳过），显式目标
                    // **永不**跳过——用户指定了它，SDP 缺失只记日志。
                    val isExplicitTarget = targetProvider()?.let { it.address.equals(device.address, true) } == true
                    if (isExplicitTarget) {
                        if (skippedNoService.add(device.address)) {
                            log(
                                "BT-C: 目标「$name」SDP 明确不含 iAP2 服务——但它是显式目标，照常尝试拨号" +
                                    "（SDP 缓存可能陈旧；固件晚开无线窗口是常态）",
                            )
                        }
                    } else if (!shouldSkipForNoService(device.address)) {
                        log(
                            "BT-C: 「$name」SDP 不含 iAP2 服务（连续判定第 " +
                                "${noServiceSoft[device.address]?.hits ?: 1}/$NO_SERVICE_CONFIRM_ROUNDS 次）" +
                                "——未达跳过门槛，本轮仍尝试",
                        )
                    } else {
                        if (skippedNoService.add(device.address)) {
                            log("BT-C: 跳过「$name」（SDP 连续 $NO_SERVICE_CONFIRM_ROUNDS 次确认无 iAP2 服务——非 CarPlay 设备，保持其音频连接；60s 后重新探测）")
                        }
                        continue
                    }
                }
                if (gate == Iap2Service.UNKNOWN) {
                    // SDP 没问出结论：先**不动音频**，直接探一次 …caff。
                    // 探通=它就是车机（自动模式不再误杀，这是 2026-10-26 凯迪拉克日志的病根）；
                    // 探不通再看是不是「蓝牙已连接时不开放服务窗口」的机型（奥迪实测），
                    // 此时才断开配置文件重试一次——耳机类设备永远不会被我们断掉音频。
                    var probe = tryConnect(device)
                    if (probe == null && !looksLikePersonalAudio(device) && canDropProfilesOnce(device.address)) {
                        val dropped = BtProfiles.disconnect(context, device)
                        if (dropped.isNotEmpty()) {
                            log("BT-C: $name SDP 未给结论、直连失败——已断开 ${dropped.joinToString("/")} 再试一次（本次 start() 内该设备仅此一次）")
                        }
                        probe = tryConnect(device)
                    }
                    if (probe == null) {
                        iap2ServiceCache[device.address] = false
                        if (lastFailureWasSdpLookup && !looksLikePersonalAudio(device)) {
                            sdpLookupFailures++
                        }
                        // 修复 #16：这条「SDP 未给结论 + 直连失败」同样只进软缓存，
                        // 不再永久拉黑（同 BMW 71280 误杀的另一半来源）。
                        if (targetProvider()?.address.equals(device.address, true) != true &&
                            shouldSkipForNoService(device.address)
                        ) {
                            if (skippedNoService.add(device.address)) {
                                log("BT-C: 跳过「$name」（SDP 未给出结论、直连 …caff 也失败——连续确认按非 CarPlay 处理，60s 后重探）")
                            }
                        }
                        continue
                    }
                    log("BT-C: 「$name」SDP 未给出结论，但直连 …caff 成功——按车机处理")
                    connected = probe
                    break
                }
                // 先「保持现有配置文件」直连一次。
                // 2026-10-26 雪铁龙 R4 日志：手机 HFP/A2DP 在线时先被我们主动断开，
                // 紧接着 …caff 连接在 **180ms** 内就失败——这么快不是超时，是 SDP 里
                // 压根查不到该服务。顺序反过来（先试、失败再断）对两种机型都成立：
                // 需要「保持连接」的车机第一次就能成，奥迪那类需要「仅配对」的车机
                // 会在第一次失败后走到下面的断开重试。
                var socket = tryConnect(device)
                if (socket == null) {
                    // 「只配对、不连接」：奥迪 MIB3 实测手机与车机处于蓝牙已连接状态
                    // （HFP/A2DP 在线）时，车机不开放无线 CarPlay 的 iAP2 服务窗口——
                    // 表现为每 3s 连接失败。iAP2 是独立的 SDP 直连服务，与这些配置文件
                    // 无关，断开后不影响 CarPlay 本身（声音走 WiFi 的 AirPlay）。
                    //
                    // 耳机/音箱例外：哪怕被显式选成目标，也不为探 iAP2 断它的音频——
                    // 正在放的歌会被掐掉，而且它本来就不可能提供 …caff。
                    //
                    // 修复 #4：每台设备每次 start() 只允许一次「断配置→重连」。今晚实车
                    // 同一轮里 <2s 两次 socket.connect()（第一次失败→断配置→立刻重拨），
                    // 第二次拨号撞上车机还在恢复配置文件的窗口；之后轮次只直连，让
                    // 「断配置」这类强动作退回成每轮启动的一次性尝试。
                    val dropped =
                        if (looksLikePersonalAudio(device) || !canDropProfilesOnce(device.address)) {
                            emptyList<String>()
                        } else {
                            BtProfiles.disconnect(context, device)
                        }
                    if (dropped.isNotEmpty()) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                            lastProfileLogAt = now
                            val left = BtProfiles.stillConnected(context, device.address)
                            log(
                                "BT-C: $name 处于蓝牙已连接状态（${dropped.joinToString("/")}）——已断开重试" +
                                    if (left.isEmpty()) "" else "（仍有 ${left.joinToString("/")} 在线）",
                            )
                        }
                    }
                    socket = tryConnect(device)
                }
                if (socket != null) {
                    log("BT-C: ✓ 已连入 $name 的无线 iAP2 服务（RFCOMM 建立）")
                    connected = socket
                    // 连上了就说明「掉线恢复」这件事做完了（真掉线会在 runSession 收尾时重新记）
                    BtSessionGate.clearLinkLost()
                    break
                } else {
                    if (lastFailureWasSdpLookup) sdpLookupFailures++
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastFailureLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastFailureLogAt = now
                        val reason = lastConnectError ?: "原因未知"
                        // 「SDP 里查不到 …caff」与「连得上但被拒」是两种完全不同的故障，
                        // 前者意味着车机没开无线 CarPlay（有些车型根本只有有线 CarPlay）。
                        val hint =
                            if (reason.contains("discovery", ignoreCase = true) ||
                                reason.contains("service", ignoreCase = true)
                            ) {
                                "——车机 SDP 里没有无线 CarPlay 的 iAP2 服务（未开无线 CarPlay / 该车型仅支持有线）"
                            } else {
                                // 修复 #13：不再写死「16 秒后重试」——退避阶梯在用
                                // 16/20/24/30/45/60，写死值会误导排障（实车日志实测
                                // 文案与实际间隔相差最多 44 秒）。真实值由本循环末尾
                                // 的「下一轮间隔放长到 Xs」行给出。
                                "——车机未就绪或不在无线配对窗口（本轮失败计 $failStreak，" +
                                    "具体重试间隔见后续日志）"
                            }
                        log("BT-C: 连接 $name 失败（$reason）$hint")
                    }
                }
            }
            if (connected == null) {
                // 1.2.108 直达路径：连续 N 轮拨号全部 `read failed/socket closed`（链路层
                // 失败）才做一次 bondedDevices 兜底复查——典型场景是用户在系统设置里把
                // 保存的目标删除了配对，直达路径不查列表不会自己发现。复查结果只打
                // 日志供诊断，**不影响直达路径继续尝试**（2026-11-24 19:31 实车：
                // bondedDevices 快照瞬时残缺曾让旧实现误判「目标不在列表」空转 6 秒，
                // 所以这里绝不能因为复查为空就停拨）。
                if (directRound) {
                    val allSocketFailed =
                        lastConnectError?.contains("read failed", ignoreCase = true) == true ||
                            lastConnectError?.contains("socket closed", ignoreCase = true) == true
                    directPolicy.noteRoundOutcome(allSocketFailed)
                    if (directPolicy.shouldRecheckBonded()) {
                        val bondedNow =
                            runCatching { adapter.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
                        val stillBonded =
                            bondedNow.any { TargetDevicePrefs.matches(targetProvider()!!, it.address) }
                        val verdict =
                            if (stillBonded) {
                                "仍在已配对列表（链路层失败另有原因）"
                            } else {
                                "已不在已配对列表（可能被用户在系统设置删除配对）"
                            }
                        log(
                            "BT-C: 直达拨号已连续 ${directPolicy.bondedRecheckAfterRounds} 轮 read failed/socket closed" +
                                "——兜底复查 bondedDevices：目标$verdict——仅记录，直达路径继续尝试",
                        )
                    }
                }
                // 目标连续连不上 → 放开目标限制（见 BtSessionGate.targetSoftFallback）。
                // 只在「用户指定了目标」时计数，且只降级一次。
                // 补连控制链路期间（recoveryPeers != null）不计数：那不是「这次坐的是另一台车」，
                // 放开限制只会让补连轮次去拨耳机/音箱，白白浪费恢复窗口。
                // 1.2.108：软回退（目标连续失败后放开目标限制、去试其它已配对设备）
                // **仅当没有保存目标时**才允许启用——有保存目标时永不尝试其它设备
                // （实车 WF-SP700N 教训：放开限制后索尼耳机被当车机候选反复拨、还被断
                // 音频）。用户确认：已保存目标就只连它，连不上是车机状态问题，不是
                // 「该换一台设备连」的信号。
                // 旧行为（1.2.107 及以前）在有保存目标时也会启用软回退，现按
                // [DirectConnectPolicy.shouldEnableSoftFallback] 收口。
                if (recoveryPeers == null &&
                    targetProvider() != null &&
                    !BtSessionGate.targetSoftFallback &&
                    directPolicy.shouldEnableSoftFallback(hasSavedTarget = targetProvider() != null)
                ) {
                    targetFailRounds++
                    if (targetFailRounds >= TARGET_FALLBACK_ROUNDS) {
                        BtSessionGate.targetSoftFallback = true
                        log(
                            "BT-C: 目标「${targetProvider()?.name}」连续 $targetFailRounds 轮连不上" +
                                "——本轮放开目标限制，任一已配对车机连入都接受（不必等它）",
                        )
                    }
                }
                // 方向兜底（修复 #10，判据放宽）：连续多轮「SDP 里根本没有 …caff」
                // **或本轮没拿到端点**（lastRoundGotEndpoint == false）都计入被动窗口。
                // 依据：今晚实车 6 次成功全是车机主动连入（L293/474/632/800/1001/1353），
                // 主动拨 6 败 0 成——MGU 这类车机只认自己发起的建链，SDP 有没有 …caff
                // 不该是切被动的唯一门槛。补连控制链路期间不切（恢复窗口别花在干等）。
                if (recoveryPeers == null && (sdpLookupFailures > 0 || !lastRoundGotEndpoint)) {
                    passiveFailRounds++
                    if (passiveFailRounds >= PASSIVE_FALLBACK_ROUNDS) {
                        passiveFailRounds = 0
                        awaitCarInitiated(myGeneration)
                        continue
                    }
                } else {
                    passiveFailRounds = 0
                }
                failStreak++
                val backoff = backoffMillis(failStreak)
                if (failStreak >= 3) {
                    log(
                        "BT-C: 已连续 $failStreak 轮没建起有效会话——下一轮间隔放长到 ${backoff / 1000}s" +
                            "（这台车机可能只在自己愿意时发起：被动监听一直在，不必密集探测）",
                    )
                }
                sleepBackoff(backoff)
                continue
            }
            if (generation.get() != myGeneration) {
                // stop() 与 connect() 成功竞争：本线程已过期，不建会话
                currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
                return
            }
            val sessionStartedAt = android.os.SystemClock.uptimeMillis()
            runSession(connected, myGeneration)
            // 「连上就被关」不算成功：2026-10-28 大众实测 52 轮全是「连入 → 0.5 秒被车机
            // 关闭」（车机不在无线窗口/要自己发起）。这类轮次按失败计，否则退避永远回到 3 秒，
            // 每轮白断一次车机、还可能抢掉它主动连入的时机。
            // 「连上就被关」不算成功：2026-10-28 大众实测 52 轮全是「连入 → 0.5 秒被车机
            // 关闭」（车机不在无线窗口/要自己发起）。这类轮次按失败计，否则退避永远回到 3 秒，
            // 每轮白断一次车机、还可能抢掉它主动连入的时机。
            // 修复 #1：有效轮的唯一判据是「拿到过 0x4301/端点」（[Iap2RetryPolicy.nextStreak]），
            // 活过 3 秒不再归零——MGU-55-J 的失败形态正是每轮活 20+ 秒却拿不到端点。
            val aliveMillis = android.os.SystemClock.uptimeMillis() - sessionStartedAt
            if (CarPlayUplinkHealth.isDead) {
                // 车机端 CarPlay 已退出：蓝牙侧它照常识别、照常 ACK，只有 0x4301 永远不来。
                // 间隔不再一律 60 秒，而是 20→30→45→60 的阶梯：用户当场重启车机时不必
                // 白等到下一分钟（CarPlayUplinkHealth.nextDeadRetryDelayMillis）。
                failStreak = Iap2RetryPolicy.nextStreak(failStreak, gotEndpoint = false)
                val deadDelay = CarPlayUplinkHealth.nextDeadRetryDelayMillis()
                log(
                    "BT-C: 车机端 CarPlay 已退出——本轮结束，${deadDelay / 1000} 秒后再探" +
                        "（${CarPlayUplinkHealth.describe()}；车机重启好会自动接上）",
                )
                sleepBackoff(deadDelay)
            } else {
                failStreak = Iap2RetryPolicy.nextStreak(failStreak, gotEndpoint = lastRoundGotEndpoint)
                if (lastRoundGotEndpoint) {
                    sleepBackoff(RETRY_DELAY_MILLIS)
                } else {
                    if (aliveMillis >= SESSION_USEFUL_MILLIS) {
                        log(
                            "BT-C: 会话存活 ${aliveMillis / 1000}s 但没拿到 0x4301/端点——按无效轮计退避" +
                                "（实车 MGU-55-J：连上、走完身份、20+ 秒后被拆链，0x4301 从未到手）",
                        )
                    }
                    val backoff = backoffMillis(failStreak)
                    if (failStreak >= 3) {
                        log(
                            "BT-C: 已连续 $failStreak 轮没拿到端点——下一轮间隔放长到 ${backoff / 1000}s" +
                                "（这台车机可能只在自己愿意时发起：被动监听一直在，不必密集探测）",
                        )
                    }
                    sleepBackoff(backoff)
                }
            }
        }
        log("BT-C: 客户端已停止")
    }

    /**
     * 被动方向兜底窗口：主动连 …caff 连续多轮在 SDP 层失败时，**停掉主动探测**，
     * 给车机一个「反过来连我们」的干净窗口（本机的 …cafe 监听由
     * [Iap2BluetoothServer] 一直开着，这里只负责让出通道并给出可读日志）。
     *
     * 依据（2026-10-26 反编译对照，`docs/cadillac-ct5-carplay-apk-analysis.md` §2.1）：
     * 同侧参考实现 CarWith-CarPlay 在主动建连失败（且非认证类失败）时会打印
     * `automatic direction fallback to car-initiated transport`，注册本机
     * `…cafe` 监听 + 固定通道 29 后**等车机连进来**（等待窗 60s/120s）。
     * 我们的监听一直存在，缺的正是「让出通道 + 明确等待」这一步：
     * 旧实现每 3 秒重新 createRfcomm 一次，既不成功也刷日志，还可能与车机的
     * 反向建连抢同一时刻的空口（未验证，保留为假设）。
     *
     * 窗口内一旦车机连入（`BtSessionGate.inboundActive`）或会话已建立就立刻返回，
     * 由主循环接管；超时则回主动方向再来一轮，并给出「该车型可能仅支持有线」的结论性提示。
     */
    private fun awaitCarInitiated(myGeneration: Long) {
        log(
            "BT-C: 主动连 …caff 连续 $PASSIVE_FALLBACK_ROUNDS 轮在 SDP 层就失败（车机没广播配件端服务）" +
                "——切换为等车机主动连入，最长 ${PASSIVE_WAIT_MILLIS / 1000} 秒（本机 …cafe 监听仍在运行）",
        )
        val deadline = android.os.SystemClock.uptimeMillis() + PASSIVE_WAIT_MILLIS
        while (running && generation.get() == myGeneration) {
            if (BtSessionGate.inboundActive || BtSessionGate.sessionActive) {
                log("BT-C: 被动窗口内车机主动连入——不再探测 …caff")
                return
            }
            if (android.os.SystemClock.uptimeMillis() >= deadline) break
            sleepQuietly(PASSIVE_POLL_MILLIS)
        }
        if (!running || generation.get() != myGeneration) return
        log(
            "BT-C: 被动窗口 ${PASSIVE_WAIT_MILLIS / 1000} 秒内车机没有连入——回到主动方向重试；" +
                "若长期如此，请确认车机是否启用无线 CarPlay（仅支持有线的车型不会有任何无线 iAP2 服务）",
        )
    }

    /**
     * 1.2.108「已保存目标直达连接」决策内核（可单测，见 [DirectConnectPolicy]）。
     * 每次 start() 重置（首轮 SDP 标志 / 连续 socket 失败计数 / 复查标记）。
     */
    private val directPolicy = DirectConnectPolicy()

    /** 直达路径的轮次计数（start() 重置；用于精简日志「第 n 轮」）。 */
    private var directRoundCount = 0

    /**
     * 候选设备：优先 SDP 缓存里带配件端 UUID 的已配对设备；
     * 缓存缺失时退化为全部已配对设备（connect 时的 SDP 查询会自行过滤）。
     * 指定了目标车机时**只返回该设备**（用户配对了多台车机时避免误连）。
     *
     * ## 1.2.108：已保存目标 → 直达路径（不查 bondedDevices）
     *
     * 旧行为每轮重新遍历 `bondedDevices` 再过滤，实车代价（2026-11-23/24 证据）：
     *  - 真车机被 SDP 误判拉黑 3 分钟（`SDP 明确不含 iAP2 服务`）；
     *  - `bondedDevices` 快照瞬时残缺（19:31）→ 误判「目标不在已配对列表」空转 6 秒；
     *  - 17PM 配对了多台设备，每轮全部白查。
     *
     * 现在 targetProvider 有保存目标且未软回退时，**直接用目标 MAC
     * `getRemoteDevice` 构造单元素列表返回**——不读 bondedDevices、不做列表过滤。
     * 手机对未配对地址调 `getRemoteDevice` 是合法的（拨号会失败但不会崩），
     * 而实际场景目标必然已配对；bondedDevices 复查降级为连续多轮 socket 失败后的
     * 兜底诊断（见 [connectLoop] 里 directPolicy.shouldRecheckBonded 的调用处）。
     */
    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: android.bluetooth.BluetoothAdapter): List<BluetoothDevice> {
        // 直达路径：有保存目标且未软回退时直接 getRemoteDevice，不碰 bondedDevices
        // （用户确认：已选定并保存目标时不应再轮询列表——像 iPhone 一样直奔目标地址）。
        val target = targetProvider()
        if (target != null &&
            !BtSessionGate.targetSoftFallback &&
            directPolicy.candidateMode(
                hasSavedTarget = true,
                softFallbackActive = BtSessionGate.targetSoftFallback,
                forAddress = null,
            ) == DirectConnectPolicy.CandidateMode.DIRECT
        ) {
            // 精简日志已在 connectLoop 轮首统一打（每轮一条），这里不再重复。
            return listOf(adapter.getRemoteDevice(target.address))
        }
        val bonded =
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        targetProvider()?.let { target ->
            // 目标策略：地址命中即唯一候选——不再按 SDP 缓存过滤（缓存可能缺失，
            // 而用户显式指定就该尝试）；目标不在已配对列表时给一次提示并空转等待。
            val hit = bonded.filter { TargetDevicePrefs.matches(target, it.address) }
            if (!BtSessionGate.targetSoftFallback) {
                if (hit.isEmpty()) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTargetMissingLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastTargetMissingLogAt = now
                        log(
                            "BT-C: 目标设备「${target.name}」(${target.address}) 不在已配对列表" +
                                "——请到系统蓝牙设置完成配对，或在「连接准备」改选目标",
                        )
                    }
                    return emptyList()
                }
                return hit
            }
            // 软回退：目标降级为「优先」，其余已配对设备一并作为候选
            // （按 UUID 提示有 iAP2 服务的排前面，减少无谓的连接尝试）。
            //
            // 但「放开限制」不等于「所有已配对设备都试一遍」（2026-11-14 一加 15 实测）：
            // 连 Redmi Buds 5 Pro 都被反复拨 …caff，失败后还会被断掉 A2DP/HFP 再试一次，
            // 用户看到的就是「蓝牙一直在断」。所以回退池要过一道
            // [keepAsSoftCandidate]：耳机/音箱、以及本轮已经判定「不是车机」的一律排除。
            //
            // **显式指定的目标永不排除**：PC 测试台（xcertplay / DiPlay）可能既没有
            // CarPlay UUID、又拿不到 Class of Device，排掉就再也连不上了。
            val softPool =
                bonded.filter { device ->
                    TargetDevicePrefs.matches(target, device.address) || keepAsSoftCandidate(device)
                }
            if (softPool.isEmpty()) return softPool
            return softPool.sortedWith(
                compareByDescending<BluetoothDevice> { TargetDevicePrefs.matches(target, it.address) }
                    .thenByDescending { d -> d.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true },
            )
        }
        val withService =
            bonded.filter { device ->
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }
        if (withService.isNotEmpty()) return withService
        // 未指定目标时也不再「全部已配对设备都试」：SDP 里没广播 …caff 的那一堆里，
        // 耳机/音箱和已经判定过不是车机的都排除掉（同 [keepAsSoftCandidate] 的理由）。
        return bonded.filter { keepAsSoftCandidate(it) }
    }

    /**
     * 软回退池的准入：这台设备还值不值得当车机试一次（修复 #15，改「正向证据」制）。
     *
     * 旧行为「拿不到 Class of Device 就当不是耳机」在一加 16 上把索尼 WF-SP700N
     * 放进了候选池（2026-11-24 实车 L163 → L176/195/208/226：3 分钟 4 次全打在耳机上）。
     * 新判据——**要有正向证据才拨**，三者缺一不进池：
     *  1. SDP 里明确广播了 …caff（它自己说自己是 CarPlay 配件）；
     *  2. CoD 拿得到且不是音频类（系统明确说它不是耳机/音箱）；
     *  3. 是显式目标（用户点名，见调用处——显式目标不经本函数过滤）。
     *
     * CoD 为 null 且没有 …caff 的设备（索尼耳机在 Android 16 的常态）本轮不拨：
     * 拨不进去还断它音频，纯负收益。`wf-`/`wh-` 型号前缀补进 [PersonalAudioClassifier]
     * 的名字兜底，覆盖「CoD 有但名字是唯一线索」的场景。
     */
    @SuppressLint("MissingPermission")
    private fun keepAsSoftCandidate(device: BluetoothDevice): Boolean {
        if (skippedNoService.contains(device.address)) return false
        if (iap2ServiceCache[device.address] == false) return false
        val advertisesIap2 =
            try {
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            } catch (_: SecurityException) {
                false
            }
        if (advertisesIap2) return true
        val deviceClass =
            try {
                device.bluetoothClass?.deviceClass
            } catch (_: SecurityException) {
                null
            }
        // 正向证据制：CoD 缺失 + 没有 …caff → 不拨（2026-11-24 实车：消灭连索尼耳机）。
        // 名字兜底仍在：名字像耳机（含 wf-/wh- 前缀）也拒，其余 CoD 非音频的照旧准入。
        if (deviceClass == null) return false
        return !looksLikePersonalAudio(device)
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): BluetoothSocket? {
        val socket =
            try {
                device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
            } catch (error: IOException) {
                // 这一步抛异常 = SDP 里连 …caff 记录都没有（车机没开无线 CarPlay 窗口），
                // 与「连上了但被拒」是两回事，必须分别记下来。
                lastConnectError = "建立 socket 失败：${error.message ?: error.javaClass.simpleName}"
                lastFailureWasSdpLookup = true
                return null
            }
        // 连接前就登记：connect() 是阻塞调用（SDP 查询 + 建链最长可达数十秒），
        // stop() 只有能拿到 socket 引用才能 close() 打断它，否则线程会滞留。
        currentSocket.set(socket)
        // 修复 #5：建链中标记——音频守卫见 true 就不做设备级 disconnect()（会拆掉
        // 正在建立的 ACL）。finally 保证任何退出路径都清掉。
        BtSessionGate.outboundConnecting = true
        try {
            socket.connect()
            lastConnectError = null
            lastFailureWasSdpLookup = false
            return socket
        } catch (error: IOException) {
            lastConnectError = error.message ?: error.javaClass.simpleName
            lastFailureWasSdpLookup = false
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            return null
        } finally {
            BtSessionGate.outboundConnecting = false
        }
    }

    private fun runSession(
        socket: BluetoothSocket,
        myGeneration: Long,
    ) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt-car") { line -> log(line) }
        val previous =
            synchronized(channelLock) {
                if (!running || generation.get() != myGeneration) {
                    channel.close()
                    return
                }
                activeChannel = channel
                senderSession = null
                BtSessionGate.claimControl(channel, inbound = false)
            }
        previous?.close()
        val peerAddress =
            try {
                socket.remoteDevice.address
            } catch (_: SecurityException) {
                null
            }
        // 真正建链了：登记对端，音频配置守卫据此知道该盯哪台车机。
        BtSessionGate.notePeerActive(peerAddress)
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                onEndpoint = {
                    if (ownsChannel(channel)) {
                        // 首配成功即记下：之后「已配对且曾连上」免弹可发现窗（见
                        // ensureDiscoverableWindow / targetBondedAndEverConnected）。
                        sessionEverEstablished = true
                        onEndpoint(it)
                    }
                },
                onWifiCredentials = { if (ownsChannel(channel)) onWifiCredentials(it) },
                onEndpointUpdate = { if (ownsChannel(channel)) onEndpointUpdate(it) },
                nowPlayingProvider = nowPlayingProvider,
                onSessionReady = { session ->
                    synchronized(channelLock) {
                        if (ownsChannel(channel)) {
                            senderSession = session
                            BtSessionGate.markControlReady(channel)
                        }
                    }
                },
                onLinkClosed = {
                    onLinkClosed { running && ownsChannel(channel) }
                },
            )
        } catch (error: IOException) {
            log("BT-C: SESSION 失败 - ${error.message}")
        } finally {
            // 先读「本轮拿到过 0x4301 没有」，再清 senderSession——它是判定车机端 CarPlay
            // 是否已退出的唯一正面证据（见 CarPlayUplinkHealth）。
            val remoteLoss = !channel.closedLocally
            var gotStartSession = false
            val owned =
                synchronized(channelLock) {
                    if (activeChannel === channel) {
                        gotStartSession = senderSession?.startSessionReceived == true
                        activeChannel = null
                        senderSession = null
                        BtSessionGate.releaseControl(channel)
                    } else {
                        false
                    }
                }
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            channel.close()
            log("BT-C: 本轮会话结束（客户端仍在运行，将自动重连）")
            // 修复 #1：回填「本轮拿到过 0x4301 没有」，connectLoop 据此决定 failStreak
            // 归零还是继续爬梯（不再按「活过 3 秒」判定——MGU-55-J 每轮都活过 3 秒
            // 却从未拿到 0x4301，旧判据让退避阶梯永远停在第一档）。
            lastRoundGotEndpoint = owned && gotStartSession
            // 修复 #11：链路终结（含车机拆链）进入与手动断开同级的车机侧冷却窗口。
            if (remoteLoss) {
                BtSessionGate.noteRemoteDisconnectAt()
            }
            // 只统计「本轮到死都没拿到 0x4301 且是自然结束」的轮次：用户主动停止、或为换链路
            // 而退役控制会话，都不是车机的态度，计进去会让「连续两轮」变得廉价。
            // 成功那条路已在会话内 noteStartSession() 清零，这里不必重复。
            if (owned &&
                remoteLoss &&
                !gotStartSession &&
                running &&
                generation.get() == myGeneration &&
                CarPlayUplinkHealth.finishRound(gotStartSession = false)
            ) {
                log(
                    "BT-C: ⚠ 判定「车机端 CarPlay 已退出」——${CarPlayUplinkHealth.describe()}。" +
                        "这不再是「连不上」：车机的 AirPlay/WiFi 组件没起来，蓝牙侧它照常应答。" +
                        "重连先按 20/30/45 秒复核、多轮不过才放到 " +
                        "${CarPlayUplinkHealth.DEAD_RETRY_DELAY_MILLIS / 1000} 秒一轮，" +
                        "请**重启车机**（或熄火重上电）后再试；车机恢复后本机会自动接上。",
                )
            }
            // CarPlay 还在跑、又不是我们主动拆的 → 这不是「本轮结束」，是控制链路掉线。
            // 主循环的静音闸门（sessionActive）会把重连一起挡掉，所以必须显式登记，
            // 让出站循环对**这一台**单独补连（2026-11-04 奥迪 MIB3 实车：
            // 三轮会话全在 `packet 115 not acknowledged` 后再也没人重连，车机控制数据全丢）。
            if (owned && running && remoteLoss && BtSessionGate.sessionActive) {
                BtSessionGate.noteLinkLost(peerAddress)
                log(
                    "BT-C: CarPlay 会话仍在，但 iAP2 控制链路已断（${peerAddress ?: "地址未知"}）" +
                        "——只对它单独补连，最长 ${BtSessionGate.LINK_RECOVERY_WINDOW_MILLIS / 1000} 秒",
                )
            }
        }
    }

    /** 主动结束当前 BT 控制会话（不影响重连循环）。供 AirPlay 配对前复现控制通道退役状态。 */
    fun detachCurrentSession() {
        synchronized(channelLock) {
            activeChannel?.also { channel ->
                activeChannel = null
                senderSession = null
                BtSessionGate.releaseControl(channel)
            }
        }?.let { channel ->
            channel.close()
            log("BT-C: 已主动断开当前控制会话")
        }
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    @Synchronized
    fun stop() {
        running = false
        // 递增世代：即便有线程卡在 connect()，事后也会因世代不符自杀，不会在下次启动时复活
        generation.incrementAndGet()
        BtSessionGate.clearLinkLost()
        detachCurrentSession()
        thread?.interrupt()
        thread = null
        log("BT-C: 已停止")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }

    /** 第 [streak] 次连续失败的退避间隔（0 次 = 基础间隔）。 */
    private fun backoffMillis(streak: Int): Long =
        RETRY_BACKOFF_MILLIS[(streak - 1).coerceIn(0, RETRY_BACKOFF_MILLIS.size - 1)]

    /**
     * 退避睡眠：分片检查 [running]，让 stop() 能立刻打断 20 秒的长退避
     * （整段 `Thread.sleep` 会让关闭会话时线程还挂着睡满一轮）。
     *
     * 同时消费 [nudgePending]：车机靠近时不干等满一轮，把截止时刻收到
     * [NUDGE_MAX_WAIT_MILLIS]；但**任何截断后的剩余睡眠都不得低于
     * [Iap2RetryPolicy.sleepFloorMillis]**（= max(NUDGE 窗, 退避起点) = 16s）——
     * 车机 ACL 抖动时频繁抢占空口，等于替车机续 mediaDisconnectTimeout（15s），
     * 那正是 2026-11-24 实车「每 1~7 秒重开旧会话全失败」的直接诱因之一。
     */
    private fun sleepBackoff(millis: Long) {
        val started = android.os.SystemClock.uptimeMillis()
        val floor = Iap2RetryPolicy.sleepFloorMillis()
        var deadline = started + maxOf(millis, floor)
        while (running && android.os.SystemClock.uptimeMillis() < deadline) {
            if (nudgePending) {
                nudgePending = false
                val capped = maxOf(started + NUDGE_MAX_WAIT_MILLIS, started + floor)
                if (capped < deadline) deadline = capped
            }
            sleepQuietly(BACKOFF_POLL_MILLIS)
        }
    }

    private fun sleepPassiveGrace(
        millis: Long,
        myGeneration: Long,
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + millis
        while (running && generation.get() == myGeneration && android.os.SystemClock.uptimeMillis() < deadline) {
            if (BtSessionGate.outboundPassiveWaitMillis() == 0L) return
            sleepQuietly(BACKOFF_POLL_MILLIS)
        }
    }
}

/**
 * 入站会话门闸：车机主动连入的 iAP2 会话活跃期间，出站客户端暂停连接尝试。
 * 目的：车机通常只有单条无线 iAP2 上下文，双向同时建链会让双方互相干扰。
 */
object BtSessionGate {
    private val clockBaseNano = System.nanoTime()

    /** 被动回连避让窗口用的单调时钟（毫秒，恒 > 0；单测可注入）。 */
    internal var nowMillis: () -> Long = { ((System.nanoTime() - clockBaseNano) / 1_000_000L) + 1L }

    @Volatile
    private var controlOwner: Iap2LinkChannel? = null

    /**
     * RFCOMM 控制通道成功连入的累计次数（每次 [claimControl] +1）。
     *
     * 音频配置守卫用它区分「**本轮**车机连进来了」和「上一轮残留的已连接状态」：
     * 守卫启动时记下当前值，之后一旦变大就立即收工——bmw134 的收敛结论是
     * 「车机一连入 `…cafecafe` 守卫就没有存在意义」，我们此前等的是更晚的
     * `iap2Ready`（完整 bring-up），卡在 `0x4300` ACK 时就会白踢几十秒。
     */
    private val controlConnectCounter = AtomicLong(0)

    val controlConnectCount: Long get() = controlConnectCounter.get()

    /** 入站与出站共享同一车机控制上下文，后建立的通道接管所有权并返回旧通道供调用方退役。 */
    @Synchronized
    fun claimControl(
        channel: Iap2LinkChannel,
        inbound: Boolean,
    ): Iap2LinkChannel? {
        val previous = controlOwner
        controlConnectCounter.incrementAndGet()
        iap2Ready = false
        inboundActive = inbound
        controlOwner = channel
        return previous
    }

    fun ownsControl(channel: Iap2LinkChannel): Boolean = controlOwner === channel

    /** 当前是否存在活跃的 BT iAP2 控制通道（含 bring-up 握手期，早于 [iap2Ready]）。 */
    val hasActiveControl: Boolean get() = controlOwner != null

    @Synchronized
    fun markControlReady(channel: Iap2LinkChannel) {
        if (controlOwner === channel) iap2Ready = true
    }

    @Synchronized
    fun releaseControl(channel: Iap2LinkChannel): Boolean {
        if (controlOwner !== channel) return false
        controlOwner = null
        iap2Ready = false
        inboundActive = false
        return true
    }

    /** 入站（车机主动连入）的 iAP2 会话活跃：出站暂停，避免两条 RFCOMM 互相踩。 */
    @Volatile
    var inboundActive: Boolean = false

    /**
     * 最近一次**真正建链**的对端地址（入站或出站都算，null = 还没连上过）。
     *
     * 用途：音频配置守卫（[BtProfiles.guardAudioProfiles]）要知道「盯哪台设备」。
     * 用户指定了目标时直接用目标地址；没指定时（自动选车机）只能靠这里记下的
     * 实际对端——否则守卫会盯着一台没连上的设备空转。
     */
    @Volatile
    var activePeerAddress: String? = null

    fun notePeerActive(address: String?) {
        if (address != null) activePeerAddress = address
    }

    /**
     * iAP2 会话已跑通 bring-up（发送端会话就绪）。
     *
     * 与 [sessionActive]（CarPlay 端点拿到、画面能上）不同：这个更早，只表示车机
     * 已经认可这条控制链路。音频配置守卫（[BtProfiles.guardAudioProfiles]）
     * 就是看它来决定「还要不要继续断 HFP/A2DP」——宝马 fork 的结论是 iAP2 一建立
     * 车机就不再依赖配置文件状态，继续断只是徒增抖动。
     */
    @Volatile
    var iap2Ready: Boolean = false

    /**
     * CarPlay 会话已建立（任一路径成功拿到端点后置位，由 SessionController 维护）：
     * 暂停一切出站连接尝试（静默降噪）——典型表现为连着 DiPlay 时不再探测 Audi。
     * 会话结束/手动断开时由 SessionController 清除。
     */
    @Volatile
    var sessionActive: Boolean = false

    /**
     * 目标设备软回退：指定了目标车机但连续多轮连不上时置位。
     *
     * 场景：用户上次连的是 A 车（配了目标），这次坐的是 B 车 / 测试台。
     * 旧行为是**死等目标**——出站只试目标、入站把非目标全部掐掉，
     * 用户体感就是「点了启动连接，四十多秒毫无反应」（2026-10-25 日志实测
     * 17:48:47 启动 → 17:49:30 才连上）。置位后目标降级为「优先」：
     * 出站回退到全部已配对设备，入站也不再拒绝。
     *
     * 每次 `connect()` 重新开始都会清零，所以真车场景只要目标在窗口内可达，
     * 行为与旧版完全一致。
     */
    @Volatile
    var targetSoftFallback: Boolean = false

    /**
     * 「CarPlay 在跑、但我们自己的 iAP2 控制链路掉了」——掉线那一台的地址与时刻。
     *
     * 2026-11-04 奥迪 MIB3 实车（`docs/audi-mib3-ea-tunnel-20261104.md`）：BT iAP2 在连上
     * 约 45 秒后判 `packet 115 not acknowledged` 拆链，而此刻 CarPlay 画面/声音都在跑，
     * [sessionActive] 于是把出站连接整体静音 —— **整场会话再没人重连控制链路**。
     * 车机控制面板从头到尾「未连接车机」、歌名/媒体键也全没了，只有手动断开重连才好。
     *
     * 静音闸门的本意是「别去骚扰别的接收端」，不是「自己这条链断了也不管」。所以这里
     * 记下掉线的那台：出站循环在 CarPlay 期间**只对它**重连，连上即清；超过
     * [LINK_RECOVERY_WINDOW_MILLIS] 还没连回来就认输（打一行日志，回到静音）。
     */
    @Volatile
    var linkLostPeerAddress: String? = null

    /** 掉线时刻（uptimeMillis，0 = 没有待恢复的链路）。 */
    @Volatile
    var linkLostAt: Long = 0L

    /** 单条控制链路的恢复窗口：超时后不再拨（CarPlay 本身不受影响）。 */
    const val LINK_RECOVERY_WINDOW_MILLIS = 180_000L

    /**
     * 用户手动断开的时刻（uptimeMillis，0 = 本次进程还没手动断过）。
     *
     * 为什么要记（2026-11-04 奥迪 4910 / MIB3 4580 实车）：车机固件里
     * `mediaDisconnectTimeout = 15000ms`——蓝牙断开后要满 15 秒，它才把设备判成
     * 「已移除」。在那之前 `keepIAP2Session = 1`，车机只走
     * `IAP2 session already running. Waiting for required IAP2 information.` 这条分支：
     * **接受 RFCOMM、走完 iAP2 身份交换、下发 0x5703 热点凭据，但不启动 CarPlay
     * projection**——7000 端口不开、`_airplay._tcp` 不广播、0x4301 不回。
     *
     * 那一轮实录：16:22:32.519 手动断开 → 16:22:33.696（**1.2 秒**）重连 → 车机给了
     * 热点和凭据 → 之后 48 秒里 mDNS 一直是「未发现」，只能拿网关兜底，8 次重连全超时。
     * 而前一轮（车机主动连入、它自己启动的 projection）10 秒就连上了。
     *
     * 所以：手动断开后要给车机留足 [REACTIVATE_COOLDOWN_MILLIS] 再发起。
     */
    @Volatile
    var manualStopAt: Long = 0L

    /**
     * 手动断开后的重新发起冷却：**必须 > 车机的 mediaDisconnectTimeout（15s）**。
     * 取 18 秒留 3 秒余量。改小就等于把车机的移除计时器一次次续上，永远停在旧会话上。
     */
    const val REACTIVATE_COOLDOWN_MILLIS = 18_000L

    /**
     * 对端（车机 UI）主动结束会话后的被动等待窗口：
     * 车机侧断开再点重连时由车机主动回连本机 `…cafe` 监听，此窗口内暂停本机出站拨号以避免双向 RFCOMM 碰撞。
     */
    const val PASSIVE_RECONNECT_GRACE_MILLIS = 15_000L

    @Volatile
    private var remoteDisconnectAt: Long = 0L

    /**
     * 出站 RFCOMM **正在建链中**（createRfcomm/connect 已发起、尚未成功或失败）。
     *
     * 修复 #5（2026-11-24 实车 00:26 段）：音频配置守卫的设备级 `disconnect()` 兜底
     * 会把这条正在建立的 ACL 连同配置文件一起拆掉——守卫踢 HFP 的那一刻恰逢
     * socket.connect() 进行中，链路直接断，拨号轮全部失败。设备级断开前必须确认
     * 这个标志为 false；守卫的 [guardAudioProfiles] shouldPause 也把它算进去。
     */
    @Volatile
    var outboundConnecting: Boolean = false

    /** 记一次「用户手动断开」。 */
    fun noteManualStop() {
        manualStopAt = android.os.SystemClock.uptimeMillis()
        // 修复 #11：手动断开也是「链路终结」的一种，统一纳入拆链后冷却窗口。
        noteRemoteDisconnectAt()
    }

    /**
     * 最近一次「**任意**链路终结」的时刻（uptimeMillis，0 = 本次进程还没断过链）。
     *
     * 修复 #11（2026-11-24）：`REACTIVATE_COOLDOWN_MILLIS` 的语义从「手动断开专用」
     * 扩成「任何一次链路终结后」——手动断开（[noteManualStop]）、车机侧拆链
     * （`runSession` 收尾 `remoteLoss` 为真）、我们主动退役控制会话都会调
     * [noteRemoteDisconnectAt]。依据：固件 `mediaDisconnectTimeout=15s` 对「设备
     * 还在不在」的判定不看断链是谁发起的，三种路径都只会在 15 秒内撞上
     * `keepIAP2Session=1` 的旧会话分支（今晚实车 L460/L790/L992，1~7 秒重开全失败）。
     */
    @Volatile
    var remoteDisconnectAtMillis: Long = 0L

    /** 记一次「任意链路终结」（修复 #11）。 */
    fun noteRemoteDisconnectAt() {
        remoteDisconnectAtMillis = android.os.SystemClock.uptimeMillis()
    }

    /** 距上次任意链路终结多少毫秒；从未断过返回 [Long.MAX_VALUE]。 */
    fun sinceRemoteDisconnect(): Long {
        val at = remoteDisconnectAtMillis
        if (at == 0L) return Long.MAX_VALUE
        return android.os.SystemClock.uptimeMillis() - at
    }

    /** 距上次手动断开多少毫秒；本次进程没手动断过返回 [Long.MAX_VALUE]。 */
    fun sinceManualStop(): Long {
        val at = manualStopAt
        if (at == 0L) return Long.MAX_VALUE
        return android.os.SystemClock.uptimeMillis() - at
    }

    /** 记一次「对端主动结束已建立的会话」，开启被动回连避让窗口。 */
    fun noteRemoteSessionEnded() {
        remoteDisconnectAt = nowMillis()
    }

    /** 用户手动点击「启动连接」时清除被动避让窗口，确保首次主动拨号零延迟。 */
    fun clearRemoteDisconnectGrace() {
        remoteDisconnectAt = 0L
    }

    /** 被动回连避让窗口剩余毫秒数（0 = 无需避让）。 */
    fun passiveGraceRemainingMillis(): Long {
        val at = remoteDisconnectAt
        if (at == 0L) return 0L
        return (PASSIVE_RECONNECT_GRACE_MILLIS - (nowMillis() - at)).coerceAtLeast(0L)
    }

    /**
     * 出站拨号此刻仍需避让的毫秒数：车机已回连（入站会话 / CarPlay 会话已起）即避让目的已达成，返回 0。
     * 避让窗口本身不清，热点随会话断开时仍按它判定是否保持被动等待。
     */
    fun outboundPassiveWaitMillis(): Long = if (inboundActive || sessionActive) 0L else passiveGraceRemainingMillis()

    /** 记一次「控制链路掉线」，交给出站循环单独重连。 */
    fun noteLinkLost(address: String?) {
        if (address == null) return
        linkLostPeerAddress = address
        linkLostAt = android.os.SystemClock.uptimeMillis()
    }

    /** 链路已恢复（或用户已停止）——清掉恢复任务。 */
    fun clearLinkLost() {
        linkLostPeerAddress = null
        linkLostAt = 0L
    }

    /**
     * 当前需要恢复的目标地址；null = 不需要（没有掉线 / CarPlay 已结束 / 已超窗）。
     *
     * CarPlay 会话结束时 [sessionActive] 会被 SessionController 清掉，这里跟着失效。
     */
    fun linkRecoveryTarget(): String? {
        if (!sessionActive) return null
        val address = linkLostPeerAddress ?: return null
        val at = linkLostAt
        if (at == 0L) return null
        if (android.os.SystemClock.uptimeMillis() - at > LINK_RECOVERY_WINDOW_MILLIS) return null
        return address
    }
}
