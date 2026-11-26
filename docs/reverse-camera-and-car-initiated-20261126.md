# 倒车后进不去 CarPlay（只有声音）+ 车机端点「连接」连不上（1.2.115）

- 依据日志：`logs/CarPToCarL-log-20261126-085356.txt`、`logs/CarPToCarL-log-20261126-085805.txt`
- 版本基线：1.2.113（`7eb3c39`），本轮改为 1.2.115
- 车机：BMW 71280 / MGU-55-J（SDP + 0x5703 旧代 WiFi，`takeConstraint=500`）
- 结论状态：**代码级根因已定位并修复；未做真车验证**（本轮无 JDK/SDK，连单测都没能跑，见第六节）

---

## 一、两个现象

1. 已配对状态下，用户在**车机**上点「连接」（或点 Apple CarPlay 图标）→ 连不上。
2. 车机倒车影像开启之后，用户再从车机点 Apple CarPlay → **进不去显示页面，只有声音**。

---

## 二、问题 2（只有声音）根因：1.2.107 的「永久归属复核」把申请也拦了

### 2.1 音频与画面是两条独立的所有权链路

- 画面：`resourceID=1` 主屏 → 需要 `phoneOwnsScreen` 才送帧（`video/VideoSender`，AirPlay type=110）；
- 声音：`resourceID=2` 主音频 → 独立申请（`airplay/CarPlayResourceStreams.applyAudio`）。

所以「音频正常、画面没有」= 主屏归属没回到手机，**不是** AirPlay 掉链。

### 2.2 倒车之后主屏在哪

车机把屏拿去显示倒车影像/原生页时，会发 `changeModes transferType=1`（接管）并带
`takeConstraint=500`：

```
[08:53:22.202] /info.modes resources=[{transferPriority=500, takeConstraint=500,
                                        resourceID=1, transferType=1, borrowConstraint=100}, …]
```

`takeConstraint=500` 的语义（`AirPlayResourceState.allows`）= **用户发起的申请
（PRIORITY_USER=500）允许把屏拿回来**——门是车机自己留的。

### 2.3 我们怎么回的

`CarPlayResourceStreams.applyScreen`（1.2.107 起）有这么一刀：

```kotlin
val blockedByPermanentCar = screenRequestFromCarUi && permanentOwnerCarBeforeClaim
if (blockedByPermanentCar) false else client.acquireScreenResource(...)
```

车机把屏拿走之后 `permanentEntity` 就**在车机**，于是用户点 CarPlay → 车机发 `requestUI`
→ 我们算 `blockedByPermanentCar=true` → **一条申请都不发**，只打一行「车机永久持有中，
不申请——等待车机 requestUI 或 HMI 操作」。

而这一条 `requestUI` **就是**它在说的「请把画面给我」。结果：屏永远回不到手机，
车机上只剩音频 → 「进不去显示页面，只有声音」。

1.2.107 加这一刀的原始动机是防争夺（`docs/HANDOFF-20261125.md` 第六节验收 4/5），
但防的是「屏本来在手机手里、做让出→拿回跳变」那条路，被一起套到了「申请」上。

### 2.4 同源的第二处缺口：流被退役后 requestUI 什么都不做

`applyScreen` 里 `if (!owned && !screenNegotiated) return`——车机持有 + 本轮屏幕流已被
TEARDOWN 时，`requestUI` 连 `republishModes`/SETUP 都不做。申请被拦时正是落在这一支。

### 2.5 第三处：回到手机只补关键帧，不重发 VideoConfig

`applyScreen` 的热流分支（`owned && warmAlive`）原来只 `requestKeyFrame()`。
车机去显示倒车影像时很可能把自己的解码器/avcC 丢了；回来只补 IDR 时，
`VideoSender.submitH264` 的「SPS 变了才重发 config」分支不会触发（`configSent` 仍 true）。

### 2.6 修复（1.2.115）

| 位置 | 改动 |
|---|---|
| `airplay/RequestUiFightingGuard.kt` | 新增 `decideClaim()/onClaimPublished()`：申请回主屏走独立退避（1.5s 起、被车机拿走翻倍、封顶 30s），**不受**「永久归属于车机」拦阻，也不被让出跳变的熔断连坐 |
| `airplay/CarPlayResourceStreams.kt` | 删掉 `blockedByPermanentCar`；`requestUI 且屏不在本机` → 按 `decideClaim` 节流**直接申请**（不做让出跳变）；申请成功后 `onClaimPublished()` 开始等车机复述 |
| `airplay/CarPlayResourceStreams.kt` | 主屏归还（热流/软恢复两条路）从「只补关键帧」升级为 `rearmDecoderOnScreenReturn()` |
| `video/VideoSender.kt` | 新增 `rearmDecoderOnScreenReturn()`：重发缓存 csd（VideoConfig）→ 再催 IDR。**刻意不发 RECORD**（宝马对重复 RECORD 过敏，见 `docs/bmw-fork-port-20261108.md` 第三节） |

分工原则（关键，别再合回去）：
- 屏**还在手机**（entity=手机）→ 让出→拿回跳变（制造归属边沿，11-22 实车验证有效）；
- 屏**已被车机拿走**（entity=车机）→ 直接申请（不做让出），节流退避。

---

## 三、问题 1（车机端点连接）根因：出站拨号和车机连入抢同一条 ACL + 监听只在界面拉起后才有

### 3.1 日志统计（今天这轮 10 分钟）

| 方向 | 次数 | 结果 |
|---|---|---|
| 本机主动拨 `…caff` | 5 轮（08:48:19 / 08:48:37 / 08:49:08 / 08:49:31 / 08:50:06） | **0 成功**，全部 `read failed, socket might closed or timeout, read ret: -1` |
| 车机主动连入 `…cafe` | 4 次（08:49:42 / 08:50:31 / 08:53:19 / 08:53:29） | **4 次全成**（都拿到了 0x5703 并起流） |

同期还有 20 条：

```
[08:48:21.912] APPROACH: 车机蓝牙链路只维持了 474ms 就断开——按抖动处理
[08:49:05.892] APPROACH: 车机蓝牙链路只维持了 2128ms 就断开——按抖动处理
…（420~556ms 为主）
```

这些 400~500ms 的 ACL 生命周期与「每轮拨号里 SDP 查询 + 建链 + 失败关闭」完全对齐：
出站拨号在**同一条 ACL** 上与车机的连入互相踩。

### 3.2 结构性缺口：监听必须等界面起来

- 监听（SDP `…cafe` + `listenUsingRfcomm`）只在 `SessionController.ensure()`
  →`applyBtCompat` 或 `startConnection` 时启动；
- 收摊（`CarIdleGuard` 30s 判定 CLOSE）会 `Process.killProcess`，**SDP 记录与监听一起消失**；
- 之后唯一能拉起进程的清单接收器 `CarApproachReceiver`（`AndroidManifest.xml` ACL 广播）
  只做一件事：`launchApp()` 把界面叫到前台，**自己不建链**；
- 界面冷启动 1~3 秒，而车机的 SDP/RFCOMM 在 ACL 之后几百毫秒就到 → 车机侧看到的正是
  `read failed, socket might closed or timeout`。

也就是说：用户「在车机上点连接」这条路上，唯一的成功窗口被界面启动时间吃掉了。

### 3.3 修复（1.2.115）

| 位置 | 改动 |
|---|---|
| `SessionController.onCarInboundApproach()`（新增） | ACL 一出现就**headless** 建对象 + 起 iAP2 监听 + 占位 SDP（不依赖界面），并记入站窗口；返回监听是否在位 |
| `bt/CarApproachReceiver.kt` | ACL_CONNECTED（目标车机）→ 先 `onCarInboundApproach()` 再 `launchApp()`；重试态（RETRYING）也调用（那里正是「我们在拨、它在连」的碰撞点） |
| `bt/Iap2BluetoothClient.kt` `BtSessionGate` | 新增 `INBOUND_APPROACH_GRACE_MILLIS=20s` 出站静默窗口，并入 `outboundPassiveWaitMillis()`（与 15s 被动窗口取大）；`INBOUND_APPROACH_GRACE_OWN_DIAL_MILLIS=4s` 处理「ACL 广播不分方向」——本机拨号时只压 4 秒，避免把出站路径（奥迪这类靠主动拨的车机）自我封锁 |
| `bt/Iap2BluetoothClient.abortOutboundDialForInbound()`（新增） | 车机 RFCOMM **真的进来了**（`onPeerConnected`）→ 关掉在途的 `socket.connect()`（只在 `outboundConnecting` 期间动手，已建成会话绝不误伤） |
| `SessionController.startAudioProfileGuard` | `shouldPause` 加上入站窗口：车机刚连入时不再设备级断 HFP/A2DP（那会带 ACL 一起断） |

---

## 四、下一次上车的验收清单

| # | 判据 | 日志关键字 |
|---|---|---|
| 1 | 车机上点「连接」能直接连入 | `BT: 车机主动连入（靠近拉起）——监听已提前挂起` 后紧跟 `BT: 接收端已连入` |
| 2 | 出站拨号不再与车机连入对撞 | 不再出现密集的 `车机蓝牙链路只维持了 4xx ms 就断开` |
| 3 | 倒车回来点 Apple CarPlay 能进画面 | `MODE: 申请主屏 fromRequestUi=true acquired=true（车机持有中→已按 requestUI 申请取回，不做让出跳变）` |
| 4 | 取回后解码器被重新武装 | `VIDEO: ✓ VideoConfig 已发送 … 主屏归还重发 csd` + 紧随的 `(key)` 帧 |
| 5 | 即便车机反复拿走也不风暴 | 出现 `申请节流退避中` / `下次申请退避延长到 Nms`，且**没有**「每秒一次」的 modesChanged |
| 6 | 画面真的亮了 | 体感 + `车机收帧统计 … rfps=5x`（pre-decode 只能证明进前级） |

## 五、风险与回滚

- `decideClaim` 只放宽了「申请」，没放宽「让出→拿回」，所以 1.2.107 的防争夺目标不受影响；
  退避翻倍 + 封顶 30s 保证不会变风暴。若某台车机对 `permanentEntity` 声明敏感（申请 → 拆链），
  表现会立刻是 `control EOF` + `requestUI 申请回后车机又拿走主屏`，此时把
  `CLAIM_BACKOFF_MIN_MS` 抬到 5s 即可快速收敛。
- `INBOUND_APPROACH_GRACE_MILLIS` 只影响「出站拨号的节奏」，不影响监听与入站；
  最坏情况是出站晚 20 秒试一次（原本退避也在 16~24 秒量级）。

## 六、本轮的验证边界（必须如实记录）

- 本机**没有 JDK / Android SDK**（`java: command not found`，无 `local.properties`），
  因此 `./gradlew testDebugUnitTest` / `assembleDebug` **一条都没跑**。
- 新增/修改的单测（`RequestUiFightingGuardTest` 4 条、`BtSessionGateTest` 2 条）只做了静态复核，
  必须在本机跑过再上车。
- 所有结论来自「日志 + 代码走读」，真车行为仍以第六节验收清单为准。
