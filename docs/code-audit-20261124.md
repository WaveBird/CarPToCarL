# 「蓝牙重连」×「WiFi join」代码审查（2026-11-24）

审查对象：`/Users/jame/CarPToCarL`（`io.github.shaonialife.carptocar`）HEAD 工作区。
范围：`bt/Iap2BluetoothClient.kt`、`bt/Iap2BluetoothServer.kt`、`bt/BtProfiles.kt`、`iap2/CarPlayUplinkHealth.kt`、
`iap2/Iap2SenderSession.kt`（WiFi 凭据部分）、`net/WifiJoinGate.kt`、`net/WifiCarPlayConnector.kt`、
`util/ConnectBudget.kt`、`SessionController.kt`（BT / WiFi 两段）。
方法：只读。全部结论都落到 `文件:行号`；注释里写明历史实测依据的，先引注释再判定。
旁证日志：`/tmp/carlog/CarPToCarL/CarPToCarL-log-20261123-085705.txt`、`-191538.txt`、`-192522.txt`（1.2.103-debug）。
> 今晚（00:26:30 → 00:31:51）那份日志本地不存在，凡依赖它的判断都放进第 4 节「不确定项」。

---

## 1. 结论摘要（最关键的五条）

1. **退避阶梯被「有用会话」清零，8 轮全程 16 秒**。`Iap2BluetoothClient.kt:783-786` 只要本轮会话活过 3 秒（`SESSION_USEFUL_MILLIS`，:89）就把 `failStreak` 归零并固定睡 `RETRY_DELAY_MILLIS`(16s)。而 MGU-55-J 的失败形态恰恰是「连上 → 22 秒后被车机拆 → 没有 0x4301」——每轮 22s + 16s ≈ 38~40s，8 轮 ≈ 5 分 20 秒，与实测 5 分 21 秒量级一致（算术在 Q1 末尾展开）。注释里设计的 16→20→24→30→45→60 阶梯**在真车这条路上从未生效**。
2. **后台自动恢复永不请求「可被发现」，且 300 秒窗口不续期**。`ensureDiscoverableWindow()` 只在 `start()` 里调一次（`:410`），`interactiveStart` 是**函数参数不是字段**（grep 全文只有 :370/:376/:410），退避循环里无法再请求；后台恢复路径（`SessionController.kt:2148` 传 `false`）在不可被发现时只打一行日志（`:460-466`）就继续跑。配合 `sessionEverEstablished` 是**进程级 volatile**（`:169-170`，进程重启即 false），车机重新扫描不到本机 → 只能靠出站拨号；用户手动点「启动连接」才会补上窗口——这正是「手动停一下再启动，25 秒就好了」的机制。
3. **`NUDGE_MAX_WAIT_MILLIS = 10_000L` 直接违反「间隔必须 > 15s」这条铁律**。`:96-102` 注释自认「截到 10 秒」，`sleepBackoff()` `:1094-1105` 无条件把本轮退避压到 10s；触发源是车机 ACL 抖动（`CarApproachReceiver.kt:166`）。等于每 60 秒给车机的 `mediaDisconnectTimeout` 计时器续一次命——与 `:58-72` 那段注释的整个论证自相矛盾。
4. **音频配置守卫的设备级 `disconnect()` 兜底会拆掉正在建立的 ACL**。`BtProfiles.kt:127-134`：配置级断开 1.5s 不生效就升级为 `BluetoothDevice.disconnect()`（拆 ACL）。守卫只判 `hasActiveControl`（= RFCOMM **已连上**，`Iap2BluetoothClient.kt:1161`），出站 `socket.connect()` 这几秒里守卫照样动手。守卫上限 300 秒（`AudioProfileGuardPolicy.MAX_MILLIS`，`BtProfiles.kt:419`）= 5 分钟，日志里「本次共断开 7/8 次」就是它——`BT-GUARD:` 那些行全部来自 `BtProfiles.kt:198/222/254`。
5. **「卡在加入热点中」有两个独立成因，一个是时间窗乘积，一个是文案永不收尾。**
   (a) **时间窗乘积**：单轮入网上限 = 首轮(15s+20s 宽限) + 2×(5s+35s) + 建议 12s×2 ≈ **144s**（`SessionController.kt:268/284/410-411/296`），而车机自己的蓝牙窗口只有 **约 27 秒**（`SessionController.kt:263-266` 注释：「BMW iD9（IDC23）实测在交出 0x4301 后约 27 秒就拆蓝牙链（2026-10-29，连续 6 轮 25~28s）」）。**35 > 27，第一轮就注定超窗**——`ConnectBudget.WIFI` 那 60s 预算只报不拦（`ConnectBudget.kt:145-155`，且 `overdue()` 全文无调用者）。
   (b) **文案永不收尾**：`onSenderLinkClosed()` 在车机拆链时置 `connectCancelled = true`（`SessionController.kt:2026`），而 `retryJoinSpecifierSilently` 的循环开头与睡眠之后各有一次 `if (connectCancelled) return false`（`:3650`、`:3656`）——**这两条早退都不改写 `phaseHint`**，于是界面永久停在 `:3651` 的「正在重试加入车机热点 DIRECT-BMW 71280…（无需操作）」。`HotspotHint` 的读时校正只在网络**已经可用**时生效（`:1986`），帮不上。
   (c) 叠加因素：MGU-55-J 的 `0x1D01` 清单在 12/13 项之间抖动（`WifiGenMemory.kt:10-18`），判成 NEW 时「不发 0x5702」（`Iap2SenderSession.kt:2318-2324`），而 `19:13:11` 那轮车机又同时声明 `0x4301=不发`——这一轮任何凭据都拿不到，却没有快速失败。

---

## 2. 逐条回答

### 一、重试节奏

#### Q1 `RETRY_DELAY_MILLIS` 的使用点、退避阶梯的实现、第 n 轮的实际间隔

**常量与阶梯**
- `Iap2BluetoothClient.kt:58-72`：`RETRY_DELAY_MILLIS = 16_000L`，注释依据是固件 `mediaDisconnectTimeout = 15000ms`（4580 固件 `Smartphone_Integrator` 定值），「取 16 秒 = 15s + 1s 余量」。
- `Iap2BluetoothClient.kt:74-86`：`RETRY_BACKOFF_MILLIS = longArrayOf(16_000L, 20_000L, 24_000L, 30_000L, 45_000L, 60_000L)`，注释写明「档位以前是 16/20/30 三档就跳到顶…参考实现 link_plus `Iap2RetryBackoff` 用 3s 起步的倍增阶梯，那个下限在我们这条链路上会踩 15 秒计时器」。
- `Iap2BluetoothClient.kt:1084-1086`：`backoffMillis(streak) = RETRY_BACKOFF_MILLIS[(streak-1).coerceIn(0, 5)]`。

**三个使用点**
| 位置 | 场景 | 实际间隔 |
|---|---|---|
| `:594` | 本轮没有任何候选设备 | `sleepQuietly(16s)`（**不吃阶梯**） |
| `:710` | 失败日志文案里的 `${RETRY_DELAY_MILLIS / 1000} 秒后重试` | 只是文案，见 Q3 |
| `:785` | 会话「有用」（活过 3s）后结束 | `sleepBackoff(16s)`，且 `failStreak` 已归零 |

**真正的梯队在 `:748-757`**：`failStreak++` → `backoffMillis(failStreak)` → `sleepBackoff(...)`。第 1~6 次失败依次 16 / 20 / 24 / 30 / 45 / 60 秒，之后恒 60 秒。判死路径另用 `CarPlayUplinkHealth.nextDeadRetryDelayMillis()`（`:774`）：20 / 30 / 45 / 60 秒。

**判定：有缺陷。** 阶梯的入口是「本轮完全没连上」（`:716 if (connected == null)`）与「连上就被关」（`:780 aliveMillis < 3000`）。一旦会话活过 3 秒，`:783-786` 走 `else` 分支把 `failStreak = 0`，于是**永远回到 16 秒**。MGU-55-J 的失败形态正是「能连上、能走完 iAP2 身份、22 秒后被车机拆链、拿不到 0x4301」（日志 `-192522.txt`：`19:13:01.842 GATE: 会话 #1 结束（22s）——蓝牙链路断开，未进入推流`），每轮都命中 `else` 分支。

**建议改法**：把「有用」的判据从「活了 3 秒」改成「**拿到过端点/0x4301**」。

```kotlin
// Iap2BluetoothClient.kt:780-786 替换
} else if (aliveMillis < SESSION_USEFUL_MILLIS) {
    failStreak++
    sleepBackoff(backoffMillis(failStreak))
} else {
    // 「活过 3 秒」不等于有进展：车机可能只是走完身份再拆链（BMW MGU 每轮 22s，
    // 从不给 0x4301）。只有真的拿到端点才算成功，否则照样进阶梯——否则
    // 16→20→24→30→45→60 这条阶梯在真车上永远走不到第 2 档（2026-11-24 实测 8 轮全 16s）。
    val progressed = senderSessionGotEndpointThisRound   // 由 runSession 回填（见下）
    if (progressed) {
        failStreak = 0
        sleepBackoff(RETRY_DELAY_MILLIS)
    } else {
        failStreak++
        sleepBackoff(backoffMillis(failStreak))
    }
}
```
`runSession()`（`:940-1048`）里已经有 `gotStartSession`（`:1006`）的现成判据，把它回填成一个 `@Volatile var lastRoundGotEndpoint` 即可，不必新造状态。

**「8 轮 ≈ 5 分 21 秒」的算术**（与日志逐行对得上）：
- 单轮 = 连接尝试（SDP 探测 2.5s×2 + 双次 `tryConnect`，见 Q4）≈ 5~15s + 会话存活 22s + 退避 16s ≈ **38~52s**；
- 日志 `-192522.txt` 里 `BT-C: 连接 WF-SP700N 失败` 的相邻间隔正是 52s / 52s / 86s（`19:12:21 → 19:13:13 → 19:14:05 → 19:15:31`，该行本身有 15s 节流，所以是下界）；
- 321s ÷ 40s ≈ **8 轮**，与「共断开 8 次」吻合。
也就是说：**这 5 分 21 秒不是「车机慢」，而是「我们每 40 秒重复一次注定失败的动作」**。改成「无端点就进阶梯」后，同样 8 轮会变成 16+20+24+30+45+60+60+60 = 315s，看起来一样长——**所以 #1 必须和 #3（可发现）、#11（拆链后补静默）一起上**，否则只是把「无效的密集重试」换成「无效的稀疏重试」。真正的收敛靠的是「给车机一个够长的干净窗口让它新建会话」。

---

#### Q2 「车机要 18 秒」到底在哪，和 16 秒是否矛盾

- **18 秒不是重试间隔**，是**手动断开后的重新发起冷却**：`Iap2BluetoothClient.kt:1270-1274`
  ```kotlin
  /** 手动断开后的重新发起冷却：**必须 > 车机的 mediaDisconnectTimeout（15s）**。
   *  取 18 秒留 3 秒余量。改小就等于把车机的移除计时器一次次续上，永远停在旧会话上。 */
  const val REACTIVATE_COOLDOWN_MILLIS = 18_000L
  ```
  日志文案在 `:527-532`：`"BT-C: 距上次手动断开只有 N 秒——车机要 18 秒才认为设备已移除（固件 mediaDisconnectTimeout=15s）…"`。**「车机要 18 秒」这句话就是这里生成的。**
- 另一处 18 是车机行为描述：`SessionController.kt:2514`「宝马 iD8 在健康推流期间也会每 18 秒左右重发一次（ctrl 邀请）」。与重连无关。

**真正的矛盾在别处**：`CarPlayUplinkHealth.kt:40-42` 的注释
```
下限仍是 20s：必须高于车机的 `mediaDisconnectTimeout`（15s，见
`Iap2BluetoothClient.RETRY_DELAY_MILLIS`），否则每轮重连都会把车机那个
「设备已移除」计时器续上，它永远不重建会话。
```
被引用的 `RETRY_DELAY_MILLIS` 实际是 **16_000L**（`Iap2BluetoothClient.kt:72`），不是 20s。注释与常量对不上（历史值残留）。
**哪边是对的**：16s 与 18s 都以 15s 为基线、都以「> 15s」为准绳，两者不冲突且都成立（前者是重试间隔下限，后者是手动断开后的冷却，留更多余量给人工停顿抖动）。**只有 `CarPlayUplinkHealth` 那句「20s」是错的**，20s 是判死阶梯的第一档（`:44`），不是全局下限。

**建议**：改注释不动常量（纯注释修改，零风险）——
```kotlin
// CarPlayUplinkHealth.kt:40-42
* 下限仍是 20s：必须高于车机的 `mediaDisconnectTimeout`（15s）。同一条铁律的两个落点：
* 常规重试下限 `Iap2BluetoothClient.RETRY_DELAY_MILLIS`=16s、手动断开后冷却
* `BtSessionGate.REACTIVATE_COOLDOWN_MILLIS`=18s；判死阶梯起点取 20s 留最多余量。
```

---

#### Q3 「16 秒后重试」在哪生成

`Iap2BluetoothClient.kt:704-712`：
```kotlin
val hint =
    if (reason.contains("discovery", true) || reason.contains("service", true)) {
        "——车机 SDP 里没有无线 CarPlay 的 iAP2 服务（未开无线 CarPlay / 该车型仅支持有线）"
    } else {
        "——车机未就绪或不在无线配对窗口，${RETRY_DELAY_MILLIS / 1000} 秒后重试…"
    }
log("BT-C: 连接 $name 失败（$reason）$hint")
```
用的是 `RETRY_DELAY_MILLIS`(16)，但**这一轮之后实际睡的是 `backoffMillis(failStreak)`**（16/20/24/30/45/60）。日志 `-085705.txt` 里 `08:48:19 / 08:49:32 / 08:50:02` 三次全写「16 秒后重试」，而间隔分别是 73s / 30s——数字从来没兑现过。

**判定：文案缺陷（会直接误导排障）。**
**建议**：这句只报原因，不报间隔；间隔由主循环统一打一行，用真实值：
```kotlin
// :710 简化为
"——车机未就绪或不在无线配对窗口（详见下一行的实际间隔）"
// :748-757 内，sleepBackoff 之前：
log("BT-C: 第 ${failStreak} 轮未建起会话——${backoff / 1000} 秒后重试（阶梯 ${RETRY_BACKOFF_MILLIS.joinToString("/") { (it/1000).toString() }}s）")
```

---

#### Q4 有没有不遵守间隔的路径

**有，四条，其中两条是硬伤。**

1. **nudge 把退避压到 10s（硬伤）** — `:96-102` + `:1094-1105`
   ```kotlin
   private const val NUDGE_MAX_WAIT_MILLIS = 10_000L   // :102
   ...
   if (nudgePending) { nudgePending = false
       val capped = started + NUDGE_MAX_WAIT_MILLIS
       if (capped < deadline) deadline = capped }        // :1098-1102
   ```
   触发链：车机 ACL 抖动 → `CarApproachReceiver.kt:166 SessionController.requestImmediateRetry("车机蓝牙靠近")` → `Iap2BluetoothClient.kt:350-358`。日志实例：`-192522.txt` `19:13:03.712 APPROACH: 车机已靠近，但本机还停在上一轮重试里（尚未建链）——不重复拉起界面，改为提前催一轮重连`。
   注释 `:98-101` 的理由是「物理靠近是现在值得再试一次的最强信号」——但**车机 ACL 抖动恰恰是「车机刚刚看到我们」**，此时抢占空口正是加固「设备仍在场」的最坏选择。
2. **同一轮里连两次 …caff（硬伤）** — `:662 tryConnect` → 失败 → `:671-676 BtProfiles.disconnect()` → `:688 tryConnect`。两次 `socket.connect()` 间隔通常 < 2 秒，且中间那次断开可能触发设备级 `disconnect()` 拆掉 ACL（见 `BtProfiles.kt:127-134`）。同样的问题在 SDP 未定论分支 `:634 / :640` 也存在。
3. `:570` / `:584` 的 `sleepQuietly(2_000)`：静音闸门与「目标不在已配对列表」的轮询，**不发起连接**，只刷日志，无害但会掩盖真实节奏。
4. `awaitCarInitiated()` `:819` 的 2s 轮询：纯等待，不发连接，无害。

**建议**：
- 常量改为 `NUDGE_MAX_WAIT_MILLIS = 16_000L`（或直接 `= RETRY_DELAY_MILLIS`），并在 `sleepBackoff` 里加硬下限：
  ```kotlin
  val capped = maxOf(started + NUDGE_MAX_WAIT_MILLIS, started + RETRY_DELAY_MILLIS).coerceAtMost(deadline)
  ```
- 把「断开配置→重连」限制为**每台设备每次 start 只能一次**（新增 `private val profileDropTried = HashSet<String>()`，`start()` 里 `clear()`），之后各轮只直连；理由写进注释：「每轮都拔一次 ACL 等于每轮把车机的移除计时器续上」。

---

### 二、可被发现（discoverable）

#### Q5 「请求可被发现」的调用点

**只有一处**：`Iap2BluetoothClient.kt:410`
```kotlin
ensureDiscoverableWindow(target, allowSystemDialog = interactiveStart)
```
`grep -n interactiveStart` 全文命中 `:370`（注释）、`:376`（形参）、`:410`（唯一调用）——**它不是字段**，所以连接循环里再也拿不到这个开关，也就再也无法补请求。实现体 `:442-480`。

#### Q6 后台自动重试时是否请求？那条分支的条件是什么？

**不请求。** `:460-466`：
```kotlin
if (!allowSystemDialog) {
    log("BT-C: 本机当前不可被发现，且本次是后台自动恢复（不弹窗）——" +
        "若车机需要重新扫描配对本机，请手动点一次「启动连接」")
    return
}
```
在这之前还有两个提前返回：
- `:452-455` `scanMode == SCAN_MODE_CONNECTABLE_DISCOVERABLE`（23）→ 复用，不请求；
- `:456-459` `target != null && targetBondedAndEverConnected(...)` → 直接 return（认为是回连场景）。

走到 `:460` 的充分条件（全部满足）：`scanMode != 23` **且**（未指定目标 **或** `targetBondedAndEverConnected == false`）**且** `allowSystemDialog == false`。

`targetBondedAndEverConnected`（`:490-498`）要求 `bonded && sessionEverEstablished`，而 `sessionEverEstablished` 是 **`@Volatile` 进程内变量**（`:162-170`，注释自认「进程重启后回到 false」）。所以**进程一旦被后台杀掉再恢复**，`sessionEverEstablished=false` → 走 `:460` → 后台路径只打日志、不请求可发现 → 车机扫不到本机。

**判定：缺陷。** 三个自洽性问题：
1. 后台恢复恰恰是最需要被发现的场景（用户不在看屏幕、车机可能刚重启、配对信息要重建）；
2. `:460-466` 的日志把问题甩给用户「请手动点一次启动连接」，但用户此时在开车；
3. 即使在**手动**路径，窗口也只有 `DISCOVERABLE_WINDOW_SECONDS = 300`（`:104-110`），而退避阶梯到顶后单轮就能吃掉 60s+——**窗口到期后没有任何续期逻辑**，因为 `interactiveStart` 传不进去。

#### Q7 手动「启动连接」走哪条路径，差异点

- 手动：`MainActivity` → `SessionController.startConnection()`（`:2802`）→ `:2849 bluetoothClient?.start()`（默认 `interactiveStart = true`）。
- 后台：`SessionController.restartBluetoothForRecovery()`（`:2143-2151`）→ `:2148 bluetoothClient?.start(interactiveStart = false)`。

**差异只有两处 if**：
1. `Iap2BluetoothClient.kt:410` 的实参（`true` / `false`）→ 决定 `:460` 那条分支；
2. 手动路径额外做：`BtSessionGate.clearRemoteDisconnectGrace()`（`SessionController.kt:2846`）、`CarPlayUplinkHealth.reset()`（`Iap2BluetoothClient.kt:398`）、`connectRetryAttempt = 0`（`SessionController.kt:2813-2817`）。

**关键观察**：手动路径的成功时间「25 秒」可以精确拆开——`disconnectNow()` 在 `:2961` 调 `BtSessionGate.noteManualStop()`，下一轮 `connectLoop` 在 `:525-535` 强制等 `REACTIVATE_COOLDOWN_MILLIS(18s) + 200ms`，再花 ~7 秒连上 = **约 25.4 秒**。也就是说，「手动停一下再启动就好了」的数学解释是：**手动路径给了车机一个真正干净的 18 秒静默窗口**，而后台重试路径从来没给过（见 Q1/Q4）。

#### Q8 有没有「第 N 次失败后主动请求可被发现」的升级逻辑

**没有。** `ensureDiscoverableWindow` 只在 `start()` 调一次；`connectLoop` 全程无二次请求；也没有任何「连续 N 轮失败 → 升级交互」的通道。

**建议（新增，位置很关键）**——在 `connectLoop` 的轮首（`:514` 之后、`:525` 冷却判断之前）加：

```kotlin
// 可发现窗口只有 300s，而退避阶梯到顶后单轮就能吃掉 60s+：窗口会在重试中途悄悄失效。
// 手动启动时续期；后台恢复时不能弹框，改为把「需要用户点一次」升级到 UI。
if (interactive) {
    val elapsed = SystemClock.uptimeMillis() - discoverableRequestedAt
    if (discoverableRequestedAt != 0L && elapsed > DISCOVERABLE_RENEW_BEFORE_MILLIS) {
        ensureDiscoverableWindow(targetProvider(), allowSystemDialog = true)
        log("BT-C: 可发现窗口已用 ${elapsed / 1000}s（上限 ${DISCOVERABLE_WINDOW_SECONDS}s）——续期一轮")
    }
} else if (failStreak >= DISCOVERABLE_ESCALATE_ROUNDS) {
    needsUserStartupHint = true   // 由 UI 读，显示「请点一次启动连接以开放可发现窗口」
    log("BT-C: 已连续 $failStreak 轮没建起会话，且本机不可被发现——" +
        "车机重新扫描不到本机；请在手机上点一次「启动连接」（会打开 300s 可发现窗口）")
}
```
新增常量（建议值）：
```kotlin
private const val DISCOVERABLE_RENEW_BEFORE_MILLIS = 60_000L   // 窗口剩不到 60s 就续期
private const val DISCOVERABLE_ESCALATE_ROUNDS = 3             // 3 轮 ≈ 48~60s 仍失败 → 升级提示
```
`interactive` 需要把 `start(interactiveStart)` 的入参存成字段（当前是形参，这是第一款改动的根因）。

---

### 三、主动连接 vs 被动等待

#### Q9 App 会主动去连车机 `…caff` 吗？在哪个方法？

**会。** UUID 定义 `:54`（`00000000-deca-fade-deca-deafdecacaff`）；实际拨号在 `tryConnect()` `:911-938`：
```kotlin
device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)   // :915
socket.connect()                                                // :927
```
调用点：`:634`、`:640`、`:662`、`:688`（见 Q4）。驱动这一切的是 `connectLoop()` `:501-789`（线程名 `iap2-bt-client`，`:411`）。

#### Q10 同时是否保持 RFCOMM 监听？并行还是互斥？

**并行，而且没有任何互斥。**
- 启动：`SessionController.kt:2849-2850` 连着两行，`bluetoothClient?.start()` 与 `bluetoothServer?.start()` 同时拉起。
- 监听：`Iap2BluetoothServer.start()` `:126-167`，`listenUsingRfcommWithServiceRecord("iAP2", …cafe)`（`:154`），线程名 `iap2-bt-accept`。
- 协调只有一把「事后闸门」`BtSessionGate`：`inboundActive` 只在 `claimControl(inbound = true)` 之后才为 true（`Iap2BluetoothClient.kt:1146-1156`、`:1177-1179`），出站循环在 `:561` 才据此让路。

**判定：缺陷（竞态窗口）。** 出站 `socket.connect()` 是阻塞数秒的调用，这期间 `inboundActive == false`、`hasActiveControl == false`，车机若同时回连 `…cafe`，两条 RFCOMM 会同时建立，`claimControl` 后建的那条会 `previous?.close()` 掉先建的（`:949-959`）。日志里「连上就被关 / 22 秒拆链」与此一致（也可能是车机侧行为，见不确定项）。
现有缓解只有 `BtSessionGate.PASSIVE_RECONNECT_GRACE_MILLIS = 15_000`（`:1280`，仅在**对端主动结束会话**后生效，由 `SessionController.kt:1849 noteRemoteSessionEnded()` 登记）。

**建议**：
1. `tryConnect()` 前置 `BtSessionGate.noteOutboundConnecting()`、`finally` 清除，新增 `@Volatile var outboundConnecting`；
2. `Iap2BluetoothServer.acceptLoop` 在 `accept()` 返回后，若 `outboundConnecting` 为真，**不要立刻起会话**，先打日志并给对端 500ms 让路；
3. 出站每轮开始前，若 `BtSessionGate.controlConnectCount` 在最近 `INBOUND_RACE_HOLDOFF_MILLIS = 5_000L` 内增长过，本轮跳过出站拨号（车机正在连进来）。日志：`"BT-C: 车机 ${since/1000}s 前刚连入过——本轮不主动拨号，让被动方向先试"`。

#### Q11 有没有「主动连失败 N 次后转纯被动」

**有，但条件过窄，真车永远触发不了。** `:738-747`：
```kotlin
if (recoveryPeers == null && sdpLookupFailures > 0) { passiveFailRounds++
    if (passiveFailRounds >= PASSIVE_FALLBACK_ROUNDS) { ... awaitCarInitiated(myGeneration) } }
else { passiveFailRounds = 0 }
```
而 `sdpLookupFailures` 只在 `lastFailureWasSdpLookup == true`（`:697`、`:644`）时累加，即**只有 `createRfcommSocketToServiceRecord` 直接抛异常**（SDP 里没有该记录）才算。

真车日志里的失败原因是 `read failed, socket might closed or timeout, read ret: -1`（`-085705.txt` 08:48/08:52/08:53/08:54，`-192522.txt` 19:12~19:15），走的是 `:931-937` 那条 `catch`，`lastFailureWasSdpLookup = false` → **`passiveFailRounds` 每轮被 `:746` 清零 → `awaitCarInitiated()` 一次都不会执行**。这正好对上「连 …caff 全部失败，最后是车机主动回连才成功」。

**建议**：
```kotlin
// :738 判据放宽为「任何一类失败都算」——真车的失败是 read failed/timeout，不是 SDP 层
if (recoveryPeers == null && (sdpLookupFailures > 0 || lastRoundFailedWithNoSession)) { ... }
```
即：把「连上了但 < `SESSION_USEFUL_MILLIS` 就被关」也计入 `passiveFailRounds`（与 Q1 的 `progressed` 判据天然共用）。阈值 `PASSIVE_FALLBACK_ROUNDS = 2` 保持不变（2 轮 × 16s ≈ 32s，可接受）。被动窗口 `PASSIVE_WAIT_MILLIS = 60_000`（`:141`）建议同步调到 30s：车机回连通常在 10 秒内（日志 `19:13:01` 断、`19:13:10` 车机回连，9 秒），60 秒纯属浪费。

---

### 四、WiFi join

#### Q12 车机热点凭据从哪来？0x5703 还是 0x4301？

**两条路并存，按「协议代际」（wifiGen）二选一。**
- `WifiGen.LEGACY`：手机发 `0x5702`（`REQUEST_ACCESSORY_WIFI_CONFIGURATION`），车机回 `0x5703`（`ACCESSORY_WIFI_CONFIGURATION`）→ `Iap2SenderSession.kt:662-760`，解析在 `:744-760`，命中后 `onWifiCredentials?.invoke(parsed)`（`:758`）并落 `WifiGenMemory.markLegacy()`（`:753`）。
- `WifiGen.NEW / UNKNOWN`：`Iap2SenderSession.kt:2318-2324` → `dispatchHint = "不发 0x5702（等 0x4301 给 AP 信息）"`；凭据从 `0x4301` 的 `CarPlayEndpoint` 里来，端点交接在 `SessionController.kt:7721-7727`（`WifiConfiguration(endpoint.ssid, endpoint.passphrase, …)`）。
- 两条都空时还有一层并行兜底：`:705-719`「0x5702 在 1000ms 内无应答 → 并行发 0x4300」，常量 `LEGACY_WIFI_FIRST_WAIT_NANOS = 1_000_000_000L`（`:462`）。

**对 0x4301 的处理 = 有等待窗口、也有超时降级，但没有「本轮不可能拿到」的快速失败。**
- 等待窗口 `START_SESSION_WAIT_NANOS / START_SESSION_WAIT_NEW_PROTOCOL_NANOS`，并按 `declaresStartSession == false` 决定是否给窗口（`:768-780`，注释：「1.2.49：只有**车机自己声明了无线 CarPlay 组件（参24）**时才破例给窗口…2026-11-15 宝马 iD8.5 九轮实证：全部 `0x4301=不发`，等 0x4301 用时 0.0s」）。
- 0x5703 等不到时打 `"iap2 0x5703 未在窗口内到达——改走 0x4300/0x4301"`（`:722`）并记 `ProtocolAudit.Code.LEGACY_WIFI_SILENT`（`:726-730`）。

**判定（问题 2 的成因之一，但**不是**主因——主因见 Q14/Q15）：**
同一台 MGU-55-J 的清单会抖（`WifiGenMemory.kt:10-18` 完整引用）：
```
19:12:36.310  可收清单(13)=… 0x5702     ← LEGACY，成功拿到 0x5703
19:13:11.178  可收清单(12)=…            ← 少了 0x5702
19:13:28.955  可收清单(13)=… 0x5702     ← LEGACY，又成功
```
`WifiGenMemory` 已经修了「跨轮误判」（`ReceiverProfile.kt:134-141` 记忆优先），**但没修「本轮两个来源都关着」**：`19:13:11` 那轮车机同时声明 `CarPlayStartSession(0x4301)=不发`（日志 `-192522.txt` 匹配行 `iap2 0x1d01 可选消息声明：StartHID(0x6800)=会发 CarPlayStartSession(0x4301)=不发`），且 `0x5702` 不在可收清单 → 严格模式还会把它裁掉（`Iap2SenderSession.kt:2341 AccessoryDeclaration.setStrictDeclaredOnly(profile.wifiGen == NEW)`）→ **这一轮无论等多久都不会有凭据**。当前实现会一路走到「加入车机热点」再超时（`ConnectBudget` 60s 只是报告，`ConnectBudget.kt:145-155` 注释明写「只负责**报告**，不负责拆链」）。

**建议（P1，新增快速失败）**：在 `Iap2SenderSession` 解析完 `0x1D01` 之后（`~:2340`，`log(...dispatchHint)` 之后）加：
```kotlin
// 三条来源同时被车机封掉 = 本轮不可能拿到 AP 信息（BMW MGU 19:13:11 那轮实录）。
// 与其挂着「加入热点」等 60s 超时，不如立刻结束本轮，让车机下一轮重发 0x1D01。
val noWifiSource =
    profile.wifiGen == ReceiverProfile.WifiGen.NEW &&
        accessoryDeclaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION) != true &&
        accessoryDeclaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) != true &&
        accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION) != true
if (noWifiSource) {
    log(
        "iap2 ⚠ 本轮无 WiFi 凭据来源：wifiGen=NEW，且车机声明不收 0x5702 / 不发 0x5703 / " +
            "不发 0x4301——不进入「加入热点」，直接结束本轮等车机重发清单",
    )
    ProtocolAudit.record(ProtocolAudit.Code.NO_WIFI_SOURCE, detail = "0x5702/0x5703/0x4301 三条全封")
    return   // 结束本轮会话，交回主循环（照常走 BT 退避）
}
```
（`accessoryDeclaresSend/Receive` 返回 `Boolean?`，所以用 `!= true` 而不是 `== false`——「车机没说」与「车机说没有」在这里应当**同权**处理，因为无论哪种都拿不到凭据。）
配套：`WifiGenMemory.isKnownLegacy` 已经能避免大部分误判（记忆命中时 `wifiGen=LEGACY`，会照发 0x5702），所以这条只是**兜住记忆未命中（首次/清数据/换车机）的那几轮**。

---

#### Q13 `WifiJoinGate` 的 5 秒去重会不会误伤真正的重试

实现 `WifiJoinGate.kt:87-107`：
```kotlin
if (running != null && running.ssid == ssid) Decision.Wait(running)
else if (!force && lastSsid == ssid && !lastSucceeded && nowMillis - lastAtMillis < dedupeWindowMillis)
    Decision.Skip(nowMillis - lastAtMillis)
else Decision.Run(fresh)
```
默认窗口 `DEFAULT_DEDUPE_WINDOW_MS = 5_000L`（`:134-138`），理由注释：「一次应用专属网络的关联 + DHCP 在 5GHz 车机热点上实测 3~8 秒，取 5 秒不会误伤正常重试」。`SessionController` 侧传的是同名同值的 `WIFI_JOIN_DEDUPE_MS = 5_000L`（`:422`、`:1410`）——**一致，无冲突**。

**判定：会误伤，而且 `force` 通道实际只覆盖了一条路 —— 文档承诺的「用户点重试走 force」并不存在。**

先钉三个事实（都已 grep 全文核对）：
1. `force = true` 传入 `joinCarWifiSpecifier` 的地方**只有一处**：`:3658`（自动重试）。而 `:3413-3414` 的注释写「@param force 用户显式点了「重试」——绕过 WIFI_JOIN_DEDUPE_MS 去重窗口」——**没有任何用户动作会传 true**。用户点「启动连接」走的是 `startConnection()`（`:2802`）→ 重新拿凭据 → `autoJoinCarWifi(force=false)`。文档与实现不符。
2. 去重窗口的锚点是「**失败返回的那一刻**」而不是「请求发起时」：`WifiJoinGate.kt:119-124` 里 `lastAtMillis = nowMillis` 只由 `finish()` 写入，而 `finish()` 在请求跑完之后才调。
3. 窗口内命中 `Skip` 后，`:3429-3436` 直接把 `JoinResult.Unavailable` 返回给调用方，**一发都没发**。

**会触发的调用序列（净损失 5 秒）：**
```
T0      0x5703 → onWifiCredentials(:3174) → autoJoinCarWifi(:3269) → joinCarWifiSpecifier(force=false, :3286)
        → decide() = Run
T0+0.3s 系统立刻 onUnavailable（WifiCarPlayConnector.kt:293-297，异常密码/不在范围都会秒回）
        → finish(succeeded=false)，lastAtMillis = T0+0.3s
T0+1.3s 0x4301 到达 → onCarPlayEndpoint → :7768 joinCarWifiSpecifier(wifiConfig, shellReady)  ← force 默认 false
        → decide() = Skip → 日志「0s 前刚加入失败——5s 去重窗口内不重复请求」→ 直接 Unavailable
        → :7782-7792 retryJoinSpecifierSilently → :3655 sleepQuietly(5_000) → :3658 force=true 才真正发
```
BMW 的 27 秒窗口在这一步被吃掉约 1/5。另外 `decision.sinceMillis / 1_000`（`:3431`）是整数除法，1.3 秒的间隔会印成「1s」、4.9 秒印成「4s」，日志精度也在骗人。

**还有两个真缺陷：**

- **`WifiJoinGate.finish()` 不在 `finally` 里**（`:3472-3483`）：
  ```kotlin
  val result = if (adopted != null) Joined(adopted) else joinSpecifierThenSuggestion(connector, wifi)
  wifiJoinGate.finish(decision.ticket, result is Joined, SystemClock.uptimeMillis())   // ← 不在 finally
  result
  ```
  `joinSpecifierThenSuggestion` 只对 `joinCarPlayNetwork` 做了 try/catch（`:3509-3521`），它内部的 `appendLog` / `silentJoinPolicy` / 任何 `Error` 若抛出，`pending` 就**永远不清**。此后同一 SSID 的每次 `decide` 都命中 `Wait`（`WifiJoinGate.kt:94-95`），每个调用方白等 40 秒（`:3443`）才拿到 `Unavailable`，直到进程重启。与项目自己的「不留僵尸轮次」原则（`SessionController.kt:2006-2011`）直接冲突。
- **`Decision.Wait` 的 40 秒完全静默**（`:3438-3451`）：`ticket.await(15+20+5=40s)`，期间不打一行日志、不改 `phaseHint`；超时后 `succeeded == null` 一律按 `Unavailable`，连「没等到结果」这件事都不留痕。

**建议**：
- `Skip` 改成「延迟后重发一次」而不是等价于失败（`Skip` 已经给出 `sinceMillis`，剩余 = `5_000 - sinceMillis`）：
  ```kotlin
  is WifiJoinGate.Decision.Skip -> {
      val wait = (WIFI_JOIN_DEDUPE_MS - decision.sinceMillis).coerceIn(0L, 2_000L)
      appendLog("WIFI: \"${wifi.ssid}\" ${"%.1f".format(decision.sinceMillis / 1000.0)}s 前刚失败——" +
          "去重窗内，${wait}ms 后自动再发一次（不再直接判失败）")
      sleepQuietly(wait)
      return joinCarWifiOnce(connector, wifi, force = true)
  }
  ```
- `finish()` 挪进 `try/finally`（`succeeded` 用局部变量承接，异常时按 false 收尾）；
- `Wait` 分支补两行日志（进入时一行、`await` 返回后一行，`ok == null` 时明写「等了 40s 没等到在跑那轮的结果」）；
- 修正文档：`force` 参数说明与实现对齐，或者真的把用户 `startConnection` 的入网标成 `userInitiated` 并传 `force=true`。

---

#### Q14 join 失败后的重试策略与上限

三层，上限都在，但**单轮总时长可以到 ~144 秒，是车机蓝牙窗口（约 27s）的 5 倍**。

| 层 | 位置 | 上限 | 单次耗时 |
|---|---|---|---|
| ① specifier（`WifiNetworkSpecifier`） | `SessionController.kt:3501-3522` | 每次 `SPECIFIER_JOIN_TIMEOUT_MS = 15_000L`（`:268`）+ `SPECIFIER_JOIN_GRACE_MS = 20_000L`（`:284`） | 最长 **35s** |
| ② 静默自动重试 | `:3645-3673` | `SPECIFIER_AUTO_RETRY_TIMES = 2`（`:410`），间隔 `SPECIFIER_RETRY_GAP_MS = 5_000L`（`:411`），先睡后发（`:3655`） | 2 × (5s + 35s) |
| ③ 建议通道（`WifiNetworkSuggestion`） | `:3526-3553` | `SILENT_JOIN_MAX_MISSES = 2`（`:305`），单次 `SUGGESTION_SILENT_WAIT_MS = 12_000L`（`:296`） | 12s × 2 |

合计上限 ≈ 35 + 80 + 24 = **139~144 秒**。对照两组既有事实：
- **车机窗口**：`SessionController.kt:263-266` 注释原文「**必须与车机的等待窗口对齐**：BMW iD9（IDC23）实测在交出 0x4301 后约 **27 秒**就拆蓝牙链（2026-10-29，连续 6 轮 25~28s）」。这段注释是给 `SPECIFIER_JOIN_TIMEOUT_MS` 写的，但它只约束了 ① 的第一段，②③ 后来叠上去时没有人重新核对总账 → **15+20 已经 > 27，第一轮必然超窗**，后面两层的重试全部发生在「车机已经不会再听」的时间里。
- **成功轮实测**：`-085705.txt` `08:54:44 STAGE: AirPlay 配对开始（上段 加入车机热点 用时 3.8s…）`、`-192522.txt` `19:15:29 … 用时 3.9s`。也就是说**正常情况 4 秒就够**，140 秒是纯失败放大。

**判定：缺陷（本项目 WiFi 侧最值得改的一处）。** `CONNECT_RETRY_BACKOFF_MS`（`:432`，3/10/30s）那段注释「三次用完还在失败就是车机侧真坏了…继续怼只会刷屏」的哲学是对的，但 WiFi 侧没有贯彻——它是「每层各自有上限」，不是「整轮有预算」。

**建议**：引入**一个贯穿的 deadline**，与车机窗口对齐：
```kotlin
/** 本轮入网总预算：车机交出 0x4301 后约 27s 拆链（见 SPECIFIER_JOIN_TIMEOUT_MS 注释），留 2s 收尾。 */
private const val WIFI_JOIN_TOTAL_BUDGET_MS = 25_000L
```
- 入口记 `val joinDeadline = SystemClock.uptimeMillis() + WIFI_JOIN_TOTAL_BUDGET_MS`，一路传到 `joinCarWifiSpecifier`；
- 每次 `joinCarPlayNetwork(timeoutMillis = min(SPECIFIER_JOIN_TIMEOUT_MS, joinDeadline - now), graceMillis = min(SPECIFIER_JOIN_GRACE_MS, …))`；
- 自动重试前判 `now < joinDeadline`，否则直接收尾并打结论行；
- `SPECIFIER_AUTO_RETRY_TIMES` 由「2 次」改为「在预算内尽量」（预计退化为 1 次）；
- 把 `ConnectBudget.WIFI` 的 `budgetMs` 改成同一个常量（现在 `ConnectBudget.kt:18` 写死 60_000，推导依据 `CAR_WIFI_BIND_WAIT_MS=45s` 只在 WEP 的 `waitForAssociationThenContinue`（`:7811`）还在用，早已不代表主路）。
- 附带：`ConnectBudget.overdue()`（`ConnectBudget.kt:129-137`）**全文无调用者**（grep 只命中定义），要么删，要么在 `wifi-join-on-endpoint` 的循环里每轮判一次并写日志（不拆链），否则 60s 预算永远不会出现在日志里。

---

#### Q15 「加入 BMW 热点中」在哪设置、什么时候清除、失败会不会卡住

先说一句：**源码里没有「加入 BMW 热点中」这个字面量**。UI 上那句话是 `phaseHint` 拼接渲染出来的：`SessionController.kt:3315 phaseHint = "正在加入车机热点 ${wifi.ssid}…（不影响移动数据）"`，BMW 的 ssid 是 `DIRECT-BMW 71280`，用户读作「加入 BMW 热点中」；渲染在 `MainActivity.kt:1978`（`heroSubline.text = SessionController.phaseHint ?: …`，每秒重读一次）、默认值 `MainActivity.kt:95`。

**设置点（会被 `HotspotHint.isJoining()` 判定为「正在加入」的 6 处）**

| 行号 | 文案 |
|---|---|
| `:3315` | 正在加入车机热点 ${ssid}…（不影响移动数据） |
| `:3535` | 正在自动加入车机热点 ${ssid}…（无需操作） |
| `:3651` | 正在重试加入车机热点 ${ssid}…（无需操作） |
| `:3702` / `:3721` | 正在自动加入 / 正在加入车机热点 ${ssid}…（旧路径，`shellReady` 恒 false，实际不可达） |
| `:7821` | 等待手机加入车机热点 ${ssid}…（无需操作） |

（`HotspotHint.kt:32-33` 的判据：必须同时含「正在」与「加入车机热点」，所以 `:3667` 的「未能加入…」这类结论文案不会被误判。）

**清除点**：成功 `:3324`/`:3788`；失败结论 `:3667`/`:3688`/`:3714`/`:3742`/`:3793`/`:7827`；用户取消 `:1999`；手动断开 `:2977 phaseHint = null`。另有一层**读时校正**：`:1973-1988` + `HotspotHint.ready()`，但只在 `connector.currentUsableNetwork() != null`（网络真的可用）时才改口。

**判定：会卡住，而且是本次最确定的成因。** 有两条路径会把「正在加入」永久留在界面上：

**(1) 车机拆链 → `connectCancelled = true` → 早退不改文案（主因）**

```kotlin
// SessionController.kt:2013-2032  onSenderLinkClosed
if (attemptGate.end("蓝牙链路断开，未进入推流")) {
    connectCancelled = true            // :2026  ← 注释 :2010 明写这是为了让卡在 WiFi 加入的线程 ≤200ms 退场
    ...
}
```
```kotlin
// SessionController.kt:3649-3656  retryJoinSpecifierSilently
for (attempt in 1..SPECIFIER_AUTO_RETRY_TIMES) {
    if (connectCancelled) return false              // :3650  ← 早退，不改 phaseHint
    phaseHint = "正在重试加入车机热点 …（无需操作）"      // :3651  ← 文案在这里被设置
    ...
    sleepQuietly(SPECIFIER_RETRY_GAP_MS)            // :3655  5 秒
    if (connectCancelled) return false              // :3656  ← 就在这句退出，也不改文案
}
```
时序完全对得上真机：车机 27 秒到点拆 BT（`SessionController.kt:263-266`）→ `connectCancelled=true` → 入网线程在 `:3656` 静默返回 → **`phaseHint` 永远停在「正在重试加入车机热点 DIRECT-BMW 71280…（无需操作）」**。这条路径既不写日志、也不调 `connectBudget.end()`，所以导出日志里连「本轮未连上」这行都没有。UI 侧只要 `attemptGate` 还 active（`sessionStarting`），`MainActivity.kt:1977-1978` 就每秒把它重刷到英雄卡副标题——**用户看到的就是「一直显示加入 BMW 热点中」**。

**(2) `joinCarWifiSpecifier` 的失败分支根本不改文案**（`:3328-3336`）：只 `appendLog("WIFI: 应用专属网络加入未成功（…）")`，然后 `return result`。文案完全靠调用方接手（`:3294`/`:7786` 进 `retryJoinSpecifierSilently`）——只要调用方那条链也早退（如 `:3274 if (!stillWanted()) return@run`、`:3279`、`:3284`），就没有第二个人负责收尾。

`HotspotHint` 帮不上忙：它没有时间维度，分不清「正在加入（3 秒）」和「正在加入（3 分钟）」；而且它只在网络**可用**时改口（`:1986`），真失败时原句原样输出。

**建议**：
1. 抽一个收尾函数，在所有入网出口调用（含早退）：
   ```kotlin
   /** 本轮入网无论怎么结束都要改口：不留「正在加入」的僵尸文案。 */
   private fun settleWifiHint(stage: String) {
       if (HotspotHint.isJoining(phaseHintValue)) {
           phaseHint = "未加入车机热点（$stage）——下一轮车机邀请时会自动重试"
           appendLog("WIFI: 入网线程退出（$stage）——文案已收尾；connectCancelled=$connectCancelled")
       }
   }
   ```
   调用点：`:3650`/`:3656` 两个早退前、`:3274`/`:3279`/`:3284` 三个早退前、`:3328-3336` 的 else 分支末尾、`:7758-7797` 的 `while` 退出后。
2. `onSenderLinkClosed` 在 `:2026` 置 `connectCancelled` 之后补一句 `settleWifiHint("蓝牙链路断开")`。
3. 给 `HotspotHint` 加一个纯函数 `stale(hint, elapsedMillis): String?`（`elapsedMillis > 45_000 && isJoining(hint)` 时返回结论文案），并在 `phaseHint` 的 getter（`:1973-1977`）里作为第三条校正——与既有的「读时校正」设计（注释 `:1965-1972`）一脉相承，不必给 UI 加定时器。现有 `app/src/test/.../HotspotHintTest.kt` 加一个用例即可覆盖。

---

#### Q16 `WifiNetworkSpecifier` / `WifiNetworkSuggestion` / `requestNetwork` 用法与小米兼容性

- **用了 `WifiNetworkSpecifier`**：`WifiCarPlayConnector.kt:10`（import）、`:399-410 buildSpecifier`、`:225-346 joinCarPlayNetwork`。
- **用了 `WifiNetworkSuggestion`**：`:503-560 registerSuggestion`、`:540-547 addNetworkSuggestions`，调用点 `SessionController.kt:3283`（入网前先登记）与 `:7764`。
- **`requestNetwork`**：`WifiCarPlayConnector.kt:306`（specifier 主路径）、`:466`（`bindCurrentWifi`）。回调处理：`onAvailable :263-274`、`onLinkPropertiesChanged :280-285`（DHCP 晚一拍的补正）、`onUnavailable :293-297`、`onLost :299-301`。
- **超时**：`timeoutMillis` 15s + `graceMillis` 20s（`SessionController.kt:3514-3515`），宽限逻辑 `WifiCarPlayConnector.kt:319-326`，注释 `:218-223` 明确这是为「一直在系统即将建好那一刻 release()，现场表现正是『一直卡在加入车机热点』」而加的（同机型 HONOR 实测 15.73s）。

**权限 / 小米兼容**

- `NEARBY_WIFI_DEVICES`：13+ 必需，代码已覆盖（`MainActivity.kt:329-332`），注释 `:318-320` 指出「缺了它 `requestNetwork` 会**静默挂起**，既不回调也不抛异常」——与现场「发起加入后一条日志都没有」完全对应。
- `ACCESS_FINE_LOCATION`：只在 **SDK < TIRAMISU** 要求（`MainActivity.kt:333-335`）；位置服务检查也只在 `< TIRAMISU`（`:554`）。**HyperOS（Android 14/15）不阻断、也不提示**，`ConnectionPreflight` 只把它记成 `WARNING`。

**判定：API 选型没有问题，但「位置服务关闭」这条比表面看起来重要得多 —— 它会把整个「复用层」废掉。**

链路是这样的：
1. `MainActivity.kt:554` 的阻断条件是 `SDK_INT < TIRAMISU`，所以 13+ 完全放行；
2. 但**读 SSID 仍然依赖位置服务**：`WifiCarPlayConnector.kt:602-604` 注释原文「读不到 SSID 时一律返回 null——Android 11+ 没给定位/附近设备权限时 `transportInfo.ssid` 是 `<unknown ssid>`，这时**不能**猜」；实现 `plainSsid()` `:782-785` 把 `<unknown ssid>` 统一成 null；
3. `findAssociatedNetwork()`（`:612-644`）、`adoptAssociated` `:658`、`adoptIfAlreadyConnected` `:800`、`adoptCurrentWifi` 全部依赖它 → **「系统已经连在车机热点上」这件事读不出来**；
4. 于是三层策略（类注释 `:19-37`：复用 → 建议 → specifier）的**第一层整层失效**，只剩 specifier 硬闯；而 specifier 在本项目自己的注释里是「每发一次，系统就要用户在一个确认框上点『连接』；**车上没人点屏**」（`:486-489`、`:790-792`）；
5. 结果：每轮等满 35 秒 → 车机 27 秒拆链 → 用户看到「卡在加入 BMW 热点中」。**与本次真机症状完全一致。**

注意这里代码内部有一处互相矛盾的说法，排查时必须先分清：`:486-489`/`:790-792` 说 specifier「每次都要人点」，而 `SessionController.kt:3491-3499` 说「既有授权/关联记录在时会被**静默满足、不弹框**（1.0.7 实测）」。两者都成立的情形是：**授权记录在 = 静默；授权记录没了（换 ROM / 重装 / 清数据）= 必须人点**。所以「读不到 SSID」叠加「授权记录丢失」就是死局。

**建议（这条优先级很高，而且是「不阻断 + 强提示」的折中）**：
1. 把位置服务从「13+ 完全不管」改成「13+ 也检查，但给『仍然继续』的出口」：
   ```kotlin
   // MainActivity.kt:554 附近
   if (!locationServiceEnabled()) {
       DarkMessageDialog.show(
           title = "建议开启位置服务",
           message = "读取车机热点名称需要位置服务（Android 13+ 仍用于 WLAN 判定，不会记录你的位置）。\n\n" +
               "不开也能连，但每一轮都会退化成「系统确认框」路径；车上无人点屏时必然超时失败。",
           // 两个按钮：「仍然继续」→ onReady()；「去开启」→ 跳设置
       )
   }
   ```
2. 在 `ConnectionPreflight` 增一项可判定的体检（比「位置服务」更贴近后果）：
   ```kotlin
   // Severity.WARNING，文案写明后果
   "SSID 可读"  ← connectivityManager.allNetworks 里任一条 WIFI 网络的 transportInfo.ssid != "<unknown ssid>"
   ```
   不通过时打一行：`"WIFI: 读不到 WiFi SSID（$reason）——本轮无法复用已连热点，只能走确认框路径"`。
3. 在 `joinCarWifiOnce`（`:3416`）入口补一行上下文日志，把「卡在哪一层」一次性写清：
   ```kotlin
   appendLog("WIFI: 入网上下文 ssid可读=${ssidReadable()} 位置服务=${locationServiceEnabled()} " +
       "nearbyWifi=${hasNearbyWifi()} 复用候选=${tryReuseCarNetwork != null}")
   ```
4. 顺带修掉两处「猜测式」文案：`WifiCarPlayConnector.kt:309` 的「可能需要位置权限或系统不支持」应带上 `nearbyWifi=` / `fineLocation=` / `sdk=` 的实际值；`:341` 把 `onUnavailable`（秒级回调）打成「等待加入超时（15s + 宽限 20s）」是错的——见下面补充。

**补充（已确认的文案缺陷，独立于上面的判定）**：`WifiCarPlayConnector.kt:293-297` 的 `onUnavailable` 会立刻 `countDown()`，宽限逻辑 `:318-320` 的进入条件是 `!unavailable.get()`，所以宽限根本不会跑；但 `:331-341` 组文案时无条件把 grace 拼进窗口字符串，于是打出
```
WiFi: 等待加入 "DIRECT-BMW 71280" 超时（15s + 宽限 20s）——系统已判定不可用
```
真实耗时可能只有 0.3 秒。建议拆成两行，并把耗时显式记出来：
```kotlin
val elapsed = SystemClock.uptimeMillis() - startedAt
if (unavailable.get()) log("WiFi: 系统拒绝加入 \"$ssid\"（耗时 ${elapsed}ms）——用户拒绝 / 密码错 / 热点不在范围；未进入宽限")
else                   log("WiFi: 等待加入 \"$ssid\" 超时（正式 ${timeoutMillis/1000}s + 宽限 ${graceMillis/1000}s，实耗 ${elapsed}ms）——确认框可能仍在前台")
```

---

### 五、其他可疑点

#### Q17 `连接 WF-SP700N 失败` 是不是 bug？

**是 bug。候选选择逻辑确实会遍历「几乎所有已配对设备」。**

- 入口 `candidateDevices()` `:834-887`。
  - **指定了目标**（真车常态）：`:844-857` 只返回目标；`targetSoftFallback` 置位后走 `:869-877` 的 `softPool`，其中非目标设备只要 `keepAsSoftCandidate(device)` 通过就会被拨。
  - **未指定目标**：`:879-886`，先试「uuids 含 …caff」的设备；若为空则 `bonded.filter { keepAsSoftCandidate(it) }` —— **全部已配对设备**。
- 闸门 `keepAsSoftCandidate()` `:897-909`：排除已判非车机者（`skippedNoService` / `iap2ServiceCache == false`），有 …caff 的一律放行，其余靠 `!looksLikePersonalAudio(device)`。
- `looksLikePersonalAudio()` `:319-329` → `PersonalAudioClassifier.looksLikePersonalAudio(deviceClass, name)`（`PersonalAudioClassifier.kt:55-58`）= `isAudioDeviceClass(deviceClass) || looksLikePersonalAudioName(name)`。
  - `AUDIO_DEVICE_CLASSES` 只含 5 个子类（`:22-29`）；
  - `NAME_KEYWORDS` = `buds / airpods / freebuds / headphone / headset / earphone / earbuds / speaker / soundbar / 耳机 / 耳塞 / 音箱 / 音响 / 降噪`（`:37-41`），注释 `:34-36` 刻意不放品牌名。

**Sony WF-SP700N 命中不了任何一条**：名字里没有关键词；CoD 拿不到时（`PersonalAudioClassifier.kt:8-11` 注释：「一加 Android 16（SDK 36）上这个字段经常是 `null`——尤其是 LE Audio 耳机」）就恒为「不是耳机」→ 进候选池 → 被拨 `…caff` → `read failed` → 还会被 `BtProfiles.disconnect()` 断一次音频（`Iap2BluetoothClient.kt:671-676`，虽然 `:672` 有 `looksLikePersonalAudio` 保护，但保护本身失效）。

日志实例（`-085705.txt`）：
```
08:48:19.535 BT-C: 连接 WF-SP700N 失败（read failed, socket might closed or timeout, read ret: -1）——车机未就绪或不在无线配对窗口，16 秒后重试…
08:49:32 / 08:50:02 / 08:52:05 / 08:53:06 / 08:54:08  同样的行
```
注意失败文案是「车机未就绪或不在无线配对窗口」——把一台耳机说成车机，排障时误导性极强。

**建议**（治本：从「排除法」改「正向证据」）：
1. `keepAsSoftCandidate` 增加一条：`CoD` 拿不到（null）且 `device.uuids` 里没有 …caff 时，**只有显式指定的目标或 PC 测试台（`TargetDevicePrefs` 命中）才允许直连**，其余推迟到下一轮先做 SDP 探测：
   ```kotlin
   if (deviceClass == null && !advertisesIap2 && !isExplicitTarget(device)) {
       log("BT-C: 「$name」拿不到 Class of Device 也没有 …caff——本轮不主动拨（避免误连耳机）")
       return false
   }
   ```
2. 失败文案按设备性质分流：对 `looksLikePersonalAudio` 为真或 SDP 明确无 …caff 的设备打 `"BT-C: 「$name」不是车机（SDP 无 …caff）——跳过"`，不要再用「车机未就绪」。
3. 不要往 `NAME_KEYWORDS` 里加 `wi-` / `wf-` 这类前缀——车机名里常含 `Wifi`/`Wireless`，会误杀真车机（`PersonalAudioClassifier.kt:34-36` 的理由仍然成立）。

---

#### Q18 `SDP 明确不含 iAP2 服务——非 CarPlay 设备` 的判据与误判风险

**实现**：`awaitIap2Service()` `:250-308`。判据三态 `Iap2Service { HAS, MISSING, UNKNOWN }`（`:238`，注释 `:230-237` 解释了为什么要把「问不出来」与「明确没有」分开，依据是 2026-10-26 凯迪拉克 CADILLAC_7731 日志）。

```kotlin
iap2ServiceCache[device.address]?.let { … }                       // :252 缓存优先
if (cachedHas()) { … return HAS }                                 // :262-265 读 device.uuids
repeat(SDP_PROBE_ATTEMPTS) {                                      // :267 两次
    … device.fetchUuidsWithSdp()                                  // :289
    answered = latch.await(SDP_PROBE_TIMEOUT_MILLIS, …)           // :293  2.5s
    if (answered) { val has = cachedHas(); iap2ServiceCache[addr] = has
        return if (has) HAS else MISSING }                        // :301-305
}
return Iap2Service.UNKNOWN                                        // :307
```

**判定：判据本身是稳的**（只有「广播到了 且 uuids 里确实没有 …caff」才 MISSING，两次探测都问不到就是 UNKNOWN——这个设计直接吸收了凯迪拉克那次误判）。

**但有三条残余误判路径：**

1. **`ACTION_UUID` 广播与 uuids 缓存的刷新竞态**。`device.uuids` 是系统缓存，广播到达 ≠ 缓存已更新（框架刷新顺序不保证）。此时 `cachedHas()` 读到旧值 → **MISSING**。虽有 `SDP_PROBE_ATTEMPTS = 2`（`:130`），但第一次已把结论写进 `iap2ServiceCache`（`:303`）并 `return`，第二次根本不会跑。
2. **`MISSING` 被写进两个「永久」结构**：`iap2ServiceCache[addr] = false`（`:303`/`:643`）**与** `skippedNoService.add(addr)`（`:624`/`:647`）。两者都只在 `start()` 里清空（`:393-394`），意味着一次误判会**让这台设备在本轮 300 秒里再也不会被拨**。
3. **车机的 …caff 可能只在无线 CarPlay 窗口开启时才注册**（这是无线 CarPlay 的常见实现）。窗口关闭时探测必然 MISSING——那是「现在没有」，不是「这台车没有」。

**影响面**：指定了目标的真车路径**不过闸**（`:614-622`：`targetProvider() == null || targetSoftFallback` 才探测），所以真车主路径不受影响；受影响的是「未指定目标」与「`targetSoftFallback` 之后的轮次」——恰好是「用户没选目标」和「目标连续 2 轮连不上」这两个最容易出问题的时刻。

**建议**：
- `MISSING` 不进 `skippedNoService`（那个集合只应记录**用户显式排除**或**明确非车机**的设备），改成一个带轮次的软缓存：`missCount[addr]++`，`missCount >= 2` 才跳过后续轮次；
- 缓存有效期限制为「本次 start 内 N 轮」，或至少 `start()` 之外再加「距上次探测 > 60s 就重查一次」；
- 补日志：`MISSING` 时打 `"BT-C: 「$name」SDP 明确不含 …caff（第 $n 次探测，2.5s 内收到广播）——本轮跳过"`，把「探测次数」写出来，便于事后区分「真没有」和「缓存脏」。

---

#### Q19 有没有「必须删配对才能连上」的痕迹

- **代码里没有任何 `removeBond()` / 取消配对逻辑**（`grep -rn "removeBond\|删除配对\|取消配对" --include=*.kt app/src/main` 零命中）。所以「必须删配对」不是代码故意做的动作，而是**状态机无法自愈**的副作用。
- **「已配对但会话已死」的处理现状**：
  - `sessionEverEstablished`（`Iap2BluetoothClient.kt:162-170`）是**进程级**的，注释自认「进程重启后回到 false」；
  - `CarPlayUplinkHealth`（`CarPlayUplinkHealth.kt:21-131`）也是全内存，`reset()` 在每次 `start()`（`Iap2BluetoothClient.kt:398`）清零；
  - `BtSessionGate.REACTIVATE_COOLDOWN_MILLIS`（`:1274`）只在**用户手动断开**后生效——`noteManualStop()` 全文只有一个调用点（`SessionController.kt:2961`，`disconnectNow`）。
- **这就是核心缺口**：车机侧靠 `mediaDisconnectTimeout(15s)` + `keepIAP2Session` 判定「设备还在不在」。我们只在「用户手动断开」这一条路给了 18 秒静默；**车机自己拆链、或我们主动重建的每一条路，都没有任何 ≥16s 的静默保证**（Q1 的 `failStreak` 归零 + Q4 的两条快路径把 16s 也吃掉了）。于是车机长期停在 `keepIAP2Session = 1`：

  > `[CDeviceDetection] IAP2 session already running. Waiting for required IAP2 information.`
  > （`Iap2BluetoothClient.kt:64-68`、`:1256-1259` 两处注释引用同一段固件原文）

  表现为「照常识别、照常下发 0x5703、但不启动 projection」——与今晚「卡在加入热点 / 拿不到密码」和「重连 8 轮不成功」两种症状都能对上。

**建议**：
1. `runSession()` 收尾（`:1040` 附近，`remoteLoss` 为真时）也记一次时刻，例如 `BtSessionGate.noteRemoteDisconnectAt()`；
2. 出站循环在 `:514` 之后加一道与手动断开同级的冷却：
   ```kotlin
   val sinceRemote = BtSessionGate.sinceRemoteDisconnect()
   if (sinceRemote < RETRY_DELAY_MILLIS) {
       log("BT-C: 车机 ${sinceRemote/1000}s 前刚拆链——补足 ${RETRY_DELAY_MILLIS/1000}s 静默再拨" +
           "（固件 mediaDisconnectTimeout=15s，早了它只重开旧会话）")
       sleepQuietly(RETRY_DELAY_MILLIS - sinceRemote + 200); continue
   }
   ```
3. 不建议自动 `removeBond()`（重配对要车机侧确认，且丢失 MFi 记忆）；「删配对才好」应该由上面的静默窗口彻底消灭。

---

## 3. 修复清单（按 优先级 / 改动量 / 风险 排序）

> 全部只改 `app/src/main/java/`，无新依赖。P0 五条建议同批提交——它们互相咬合（冷却、阶梯、可发现、快路径、守卫）。

| # | 优先级 | 改动量 | 风险 | 文件:行号 | 改法 |
|---|---|---|---|---|---|
| 1 | **P0** | 小（~15 行） | 低 | `Iap2BluetoothClient.kt:783-786` | 「有用会话」判据从「活过 3s」改成「本轮拿到过端点/0x4301」。由 `runSession` 把 `:1006` 的 `gotStartSession` 回填到 `@Volatile var lastRoundGotEndpoint`。未拿到 → `failStreak++` 照走阶梯。**这一步让 8 轮 5 分钟的等长变成 16/20/24/30/45/60** |
| 2 | **P0** | 小（~10 行） | 低 | `Iap2BluetoothClient.kt:96-102`、`:1094-1105` | `NUDGE_MAX_WAIT_MILLIS` 10_000 → **16_000**（`= RETRY_DELAY_MILLIS`），并在 `sleepBackoff` 里取 `max(NUDGE_MAX_WAIT_MILLIS, RETRY_DELAY_MILLIS)` 作硬下限。注释补一句：「车机 ACL 抖动时抢占空口，等于替车机续 `mediaDisconnectTimeout`」 |
| 3 | **P0** | 中（~40 行） | 低 | `Iap2BluetoothClient.kt:376-410`、`:442-480`、`:514` | 把 `interactiveStart` 存成字段 `private var interactiveStart = true`；`connectLoop` 轮首加「可发现续期」（剩 <60s 时重请求）与「后台连续 3 轮失败 → `needsUserStartupHint = true` + 一行明确日志」。新增常量 `DISCOVERABLE_RENEW_BEFORE_MILLIS = 60_000L`、`DISCOVERABLE_ESCALATE_ROUNDS = 3` |
| 4 | **P0** | 中（~25 行） | 中 | `Iap2BluetoothClient.kt:662-689`、`:634-641` | 「断开配置文件 → 重连」限制为每台设备每次 `start()` 只做一次（新增 `profileDropTried: HashSet<String>`，`start()` 清空）；其余轮次只直连。**消除同一轮里 <2s 的两次 `socket.connect()`** |
| 5 | **P0** | 小（~10 行） | 中 | `BtProfiles.kt:127-134`、`SessionController.kt:2320-2340` | 设备级 `disconnect()` 兜底前判 `BtSessionGate.outboundConnecting == false`；`shouldPause` 改为 `{ BtSessionGate.hasActiveControl \|\| BtSessionGate.outboundConnecting }`。日志原文：`"BT-PROFILE: 配置级断开未生效，但出站建链进行中——不做设备级 disconnect()（会拆掉刚建的 ACL）"` |
| 6 | **P1** | 小（~15 行） | 低 | `SessionController.kt:3649-3656`、`:3274`/`:3279`/`:3284`、`:3328-3336`、`:2026` | 新增 `settleWifiHint(stage)`，在所有入网出口（含 `connectCancelled` 早退）改口；`onSenderLinkClosed` 置 `connectCancelled` 后调一次。**这条直接消灭「一直显示加入 BMW 热点中」** |
| 7 | **P1** | 中（~25 行） | 低 | `SessionController.kt:3501-3553`、`:3645-3673`、`ConnectBudget.kt:18` | 引入 `WIFI_JOIN_TOTAL_BUDGET_MS = 25_000L`（对齐车机 27s 窗口）贯穿三层；`ConnectBudget.WIFI` 的 `budgetMs` 改用同一常量；各段 `timeout/grace` 取 `min(常量, deadline - now)`。当前上限 ≈144s |
| 8 | **P1** | 中（~30 行） | 低 | `SessionController.kt:3438-3451`、`:3472-3483`、`WifiJoinGate.kt:96-107` | `Skip` 改成「等剩余冷却后自动重发一次」；`Wait` 分支补进入/结果两行日志；`wifiJoinGate.finish()` 挪进 `try/finally`（异常时按 false 收尾），修「永久 Wait」漏洞 |
| 9 | **P1** | 小（~15 行） | 低 | `MainActivity.kt:554-576`、`ConnectionPreflight` | 位置服务从「仅 <13 阻断」改成「13+ 也检查、给『仍然继续』出口」；新增「SSID 可读」体检项（`transportInfo.ssid != "<unknown ssid>"`），不通过时明写后果「只能走确认框路径」 |
| 10 | **P1** | 小（~20 行） | 低 | `Iap2BluetoothClient.kt:738-747` | 方向兜底判据放宽：`sdpLookupFailures > 0 \|\| lastRoundGotEndpoint == false` 都计入 `passiveFailRounds`；`PASSIVE_WAIT_MILLIS` 60_000 → **30_000**（实测车机回连 9 秒内） |
| 11 | **P1** | 中（~30 行） | 中 | `Iap2BluetoothClient.kt:1040`、`:514` | 车机侧拆链后也补 ≥16s 静默（见 Q19 建议 1/2），把 `REACTIVATE_COOLDOWN_MILLIS` 的语义从「手动断开专用」扩成「任何一次链路终结后」 |
| 12 | **P1** | 小（~15 行） | 低 | `Iap2SenderSession.kt:~2340`、`:736`/`:2197` | 新增「本轮无 WiFi 凭据来源」快速失败（`ProtocolAudit.NO_WIFI_SOURCE`）；PSK 日志加 `len=` 与「凭据来源=0x5703/0x4301/无」（见 Q12、Q5.3） |
| 13 | **P1** | 小（~10 行） | 低 | `Iap2BluetoothClient.kt:704-712`、`:748-757` | 失败文案去掉写死的「16 秒后重试」，改由主循环用真实 `backoff` 打一行 |
| 14 | **P2** | 小（~10 行） | 低 | `WifiCarPlayConnector.kt:331-341`、`:309` | `onUnavailable`（秒级）不再打成「超时（15s + 宽限 20s）」；两处「猜测式」文案带上 `nearbyWifi/fineLocation/sdk` 实值与实耗 |
| 15 | **P2** | 小（~15 行） | 低 | `Iap2BluetoothClient.kt:897-909`、`PersonalAudioClassifier.kt:55-58` | 候选准入改「正向证据」：CoD 为 null 且无 …caff 且非显式目标 → 本轮不拨；失败文案按设备性质分流（见 Q17） |
| 16 | **P2** | 小（~15 行） | 低 | `Iap2BluetoothClient.kt:301-305`、`:624`/`:647` | `MISSING` 不进 `skippedNoService`，改软缓存（连续 2 次才跳过 + 60s 后重查），并打印探测次数 |
| 17 | **P2** | 小（~10 行） | 中 | `BtProfiles.kt:419` | `AudioProfileGuardPolicy.MAX_MILLIS` 300_000 → **180_000**。理由：300s 正好等于用户体感「卡死 5 分钟」；宝马 fork 实测抖动窗 2~4 分钟，180s 仍在窗口内。**这条会改变既有收敛结论，需实车复核** |
| 18 | **P3** | 极小 | 零 | `CarPlayUplinkHealth.kt:40-42`；`WifiJoinGate.kt:134-138`；`SessionController.kt:422` vs `WifiJoinGate.kt:138` | 注释与常量对齐（Q2）；`DEFAULT_DEDUPE_WINDOW_MS` 的注释把它写成了「并发去重」，实际是「失败冷却」（并发互斥由 `Wait` 分支负责）；`WIFI_JOIN_DEDUPE_MS` 与 `DEFAULT_DEDUPE_WINDOW_MS` 两份真源合并成一份 |

**建议的验证顺序**（每步都可单独回滚）：
1. 只上 #1、#2、#13 → 看日志：「第 n 轮间隔」是否真按阶梯增长、nudge 后是否仍 ≥16s；
2. 上 #4、#5 → 看 `BT-GUARD: 本次共断开 N 次` 是否降到 1~2 次；
3. 上 #3 → 断言后台恢复也能出现在车机的可连列表里；
4. 上 #6 → 车机拆链后界面是否立刻从「正在重试加入…」变成「未加入车机热点（蓝牙链路断开）——下一轮会自动重试」；
5. 上 #7、#8、#9 → 看「加入车机热点」停留时长是否收敛到 ≤25s，且失败必有一行结论；
6. 上 #10、#11 → 看「主动连 …caff」成功后车机是否更早回 0x4301。

---

## 4. 不确定项（代码判断不了，需实机验证）

1. **今晚（00:26:30→00:31:51）那份日志不在本地**（`/tmp/carlog/CarPToCarL/` 最新是 `20261123-192522`）。上面所有「与实测吻合」的推演用的是 11-23 的三份日志。请拿今晚日志核这三条 grep：
   - `grep "本机当前不可被发现"` —— 命中即证实 Q6 是主因（后台恢复且不可被发现）；
   - `grep "16 秒后重试"` 的相邻两行时间差 —— 若全是 ~38s 且 `failStreak` 从不增长，即证实 Q1；
   - `grep "BT-GUARD: 守卫线程结束"` 的 `共断开 N 次` —— 若 N≈8 且总时长 ≈300s，即证实 Q4-5/守卫为并发主因。
2. **`mediaDisconnectTimeout = 15000ms` 是否也适用于 MGU-55-J**。该值来自 4580 固件（`Iap2BluetoothClient.kt:61-70`、`:1254-1259`），MGU-55-J 的固件没有反编译证据。建议的做法：把 16s 做成 `BuildConfig` 可覆盖，实车分别试 16/20/25，看「车机是否重建会话」的差异。
3. **「连上 22 秒就被拆」到底是车机行为还是我们造成的**。日志 `19:13:01.842 GATE: 会话 #1 结束（22s）——蓝牙链路断开，未进入推流` 只有结果没有归因。需要同时抓 `logcat -b all | grep -i "bt_"`（Android 蓝牙栈侧）才能区分「车机主动 DISCONNECT」与「我们被守卫踢掉」。**这决定 #5/#17 的权重。**
4. **`19:13:11` 那轮「加入车机热点」用的是哪份凭据**。日志显示 `STAGE: 加入车机热点开始（上段 等车机连接信息（0x4301） 用时 0.0s）`，而该轮车机既不发 0x5703 也不发 0x4301。若 `wifiConfig.ssid` 为空/来自上轮缓存，则 Q12 的快速失败要落在 `onCarPlayEndpoint` 入口而不是 `Iap2SenderSession`；需要在 `joinCarWifiSpecifier` 入口加一行 `log("WIFI: 入网凭据 ssid=${wifi.ssid} 来源=${...}")` 才能定。
5. **`AudioProfileGuardPolicy.MAX_MILLIS` 能否降到 180s**。这取决于 MGU-55-J 把 HFP/A2DP 拉回来的持续时长；日志里 `19:12:35.992 BT-GUARD: RFCOMM 已连入 → 立即收工（本次共断开 4 次）` 说明守卫在正常路径上收得很快，300s 上限只在坏路径上生效——降上限的风险主要在「慢热车机」。
6. **`targetBondedAndEverConnected` 的语义是否该改**。目前要求「已配对 **且** 本进程曾成功连上」（`:490-498`）。若改成「已配对 **且** 车机侧曾成功过」（持久化到 prefs，与 `WifiGenMemory`/`MfiLengthMemory` 同型），进程重启后就不会白白多要一次可发现窗口——但会削弱「首配失败后重试」的窗口（`:436-438` 注释里的原始设计意图）。**这是产品取舍，不建议本轮动。**
7. **`Iap2BluetoothServer.SESSION_TIMEOUT_MILLIS = 60_000`（`:67`）与客户端 `90_000`（`:56`）不一致**是否刻意。入站会话 60s 超时可能早于车机的 `mediaDisconnectTimeout` 窗口，理论上会在「车机还在等」时先放弃。这条我没有足够证据判定对错，只记录。
8. **HyperOS 上 `WifiNetworkSpecifier` 到底弹不弹确认框。** 代码里两种说法都在：`WifiCarPlayConnector.kt:486-489`/`:790-792` 说「每次都要人点、车上没人点屏」，`SessionController.kt:3491-3499` 说「既有授权/关联记录在时静默满足、不弹框（1.0.7 实测）」。**这决定 #9（位置服务/SSID 可读）的权重**：若 HyperOS 静默满足，那「卡在加入热点」就只剩读不到 SSID 与文案不收尾两条；若必须人点，则位置服务是硬前提。验证方法：入网期间录屏看是否出现系统确认框，并把 `>>> 以应用专属网络加入` 与 `已加入应用专属网络` 两行的时间差量出来（成功轮应是 4 秒量级）。
9. **`onUnavailable` 的触发时机。** 我按「密码错/不在范围会立刻回调」推断（`SessionController.kt:281-282` 注释也这么写），但部分 ROM 会等系统确认框超时才回调（数十秒）。若如此，「35s 窗口」的构成要重算，`WIFI_JOIN_TOTAL_BUDGET_MS` 的取值也要跟着改。
10. **`0x4301` 无线组的布局 A/B 是否在本机被误判**。`parseWirelessAp`（`Iap2SenderSession.kt:363-401`）靠字段形态二选一，注释 `:350-361` 记过「信道 1..4 与安全类型区间重叠」的历史坑。若这台车机落在歧义区（`singles[2]` 与 `singles[3]` 同时存在），可能把 `securityType` 读成 0 → `buildSpecifier` 的 `0 -> Unit`（`WifiCarPlayConnector.kt:402`）→ **不带密码入网 → 必被 `onUnavailable` 拒绝**。这会给出「拿不到密码」的第三种解释（密码拿到了，但被映射丢了）。验证：把日志里 `iap2 0x4301 无线组参数: #0=… #1=… #2=… #3=… #4=…`（`Iap2SenderSession.kt:2186-2190`）与实际下发的 specifier 参数对齐。**这条建议优先排除**，因为它能同时解释「密码错」与「秒级 onUnavailable」。
11. **`linkReady()` 的判据在热点场景是否过早**。`WifiCarPlayConnector.kt:675-684` 只要有任意 `linkAddresses` 就算就绪，而 IPv6 链路本地地址通常比 DHCPv4 早出现——可能出现「已就绪但没有可连的 IPv4」，进而让 `onReady` 提前把文案改成「已连上车机热点（IP）」。这条只影响文案准确性，不影响连接，但会干扰现场判断。
