# 全流程代码审计：已保存目标场景下的剩余浪费/绕路/多余动作（→ 1.2.110 修复清单）

- 审计对象：CarPToCarL 1.2.109（commit 7b20da2，工作树干净）
- 审计日期：2026-11-25
- 判定标准：**已保存目标（蓝牙 MAC + WiFi SSID 均已知）+ 上次会话成功**的前提下，一个动作还会提供新信息吗？不会 → 浪费/绕路；会 → 必要，保留。
- 范围：bt / net / session / SessionController / iap2 / airplay（逐行走读，非 grep 式扫描）

优先级定义：P0 = 高收益、小改动、低回归风险；P1 = 收益明确但需要设计；P2 = 低收益或需实车验证。每条标注【专属】（仅已保存目标场景受益）或【通用】。

---

## A. 蓝牙链路

### A1.【专属·P0】已保存目标 + 已配对时，connectLoop 每轮仍续期/请求可发现窗口
- 文件：`app/src/main/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothClient.kt:659-685`（轮首续期）、`580-624`（ensureDiscoverableWindow）
- 现状：续期条件是 `targetProvider() != null || !sessionEverEstablished`。已保存目标时**恒真**，于是每轮（16~60s 一次）检查 `discoverableWindowUntilMillis`，剩余 <60s 就重新走 `ensureDiscoverableWindow`。弹窗路径只有 `interactiveStart=true` 才触发，但「已配对且回连」的免弹窗豁免（`targetBondedAndEverConnected`，L596）要求 `sessionEverEstablished` —— **进程重启后它回到 false**，于是已保存目标 + 已配对 + 上次会话成功的场景，后台第一轮会打「请手动点启动连接」的升级提示（L601-605 / L676-681），甚至 interactiveStart 时多弹一次可跳过的系统窗。
- 为什么是浪费：已保存目标 + 已配对 + bond 存在时，CONNECTABLE 就够回连（车机从不扫到我们才连——它回连 …cafe 或接受我们的 …caff 拨号）。可发现窗口对这个场景**不提供任何新信息**。1.2.93 的「已配对在场时复用窗口」思路没有完整继承到直达路径。
- 建议：把豁免条件从 `bonded && sessionEverEstablished` 放宽为 `bonded && (sessionEverEstablished || lastRoundGotEndpoint 等持久化的「上次成功」证据)`；更简单的版本：**已保存目标且 bondedDevices 命中时直接免窗口**（首配场景 bond 必然不在列表，不会误伤）。同时续期判定加同一个条件。
- 改动量：小（一个判定函数 + 续期 if 各一行）。单测：DirectConnectPolicy 加 `shouldRequestDiscoverable(bonded, everConnected)`。

### A2.【专属·P0】直达第 2 轮起每轮仍执行 `adapter.cancelDiscovery()` 与 `device.name`
- 文件：`Iap2BluetoothClient.kt:806-812`
- 现状：每轮对每个候选先读 `device.name`（一次跨进程 binder 调用）再 `adapter.cancelDiscovery()`（binder + 栈内操作）。直达路径候选是 `getRemoteDevice(target.address)` 单元素，名字只用于日志；cancelDiscovery 在系统未扫描时是空操作但仍有 IPC 成本，且在已保存目标 + 车机就在场的场景下每轮重复。
- 建议：直达轮次首只在「第 1 轮」cancelDiscovery + 读名字缓存到字段；第 2 轮起用缓存的 name、跳过 cancelDiscovery（或仅在 `adapter.isDiscovering` 为真时 cancel）。
- 改动量：很小。风险：`isDiscovering` 部分机型不可靠，保留 runCatching。

### A3.【通用·P1】SDP 首轮结论不做跨 start() 持久化，且 UNKNOWN 结论浪费 2×2.5s 探测窗
- 文件：`Iap2BluetoothClient.kt:136-141`（SDP_PROBE_TIMEOUT/ATTEMPTS）、`345-402`（awaitIap2Service）、`513-518`（start() 清缓存）
- 现状：`iap2ServiceCache` 与 `directPolicy` 都随每次 start() 清零。已保存目标场景下每次点「启动连接」都重做一轮 SDP（最多 2 次 fetchUuidsWithSdp × 2.5s 等待）。而**显式目标本就豁免 MISSING**（照常拨号），这轮 SDP 唯一产出是一行诊断日志。对 UNKNOWN（广播没来，真车常见），2 次 × 2.5s = 5s 纯等待后照样拨。
- 为什么是浪费：已保存目标 + 上次会话成功 ⇒ 目标必然有 …caff；首轮 SDP 在这条场景提供的新信息 ≈ 0。诊断价值可由「拨号失败原因分类」覆盖。
- 建议：直达路径把首轮 SDP 降为「仅在拨号曾以 SDP 层失败后补做一次」（诊断后置），或持久化到 prefs（地址→HAS/MISSING，TTL 7 天）。UNKNOWN 时不等满 2 次：首次超时即拨（拨号失败本身就是结论）。
- 改动量：中。需与「2026-11-23 误拉黑」教训对照——保留「显式目标永不因 SDP 跳过」不变。

### A4.【专属·P1】BT-GUARD 在已保存目标 + 车机配置文件在线时可预判「无守卫必要」，但仍在起线程轮询
- 文件：`bt/BtProfiles.kt:190-300`（guardAudioProfiles）、`SessionController.kt:2394`（启动点）
- 现状：每次 startConnection 都起守卫线程，5s 一轮 `connectedNames()`（proxy 获取 + binder）。守卫的使命是「车机看到 HFP/A2DP 在线就不开 iAP2 窗口时反复断」。但已保存目标 + 上轮成功 + 本轮车机配置文件**本来就没连**（`connectedNames()` 为空）时，守卫只是在空转（有 NO_PEER_GIVE_UP 的 60s 上限兜底，但那是针对「没对端」的；有目标地址时它对空列表每 5s 白查一轮直到 90s 上限）。
- 建议：目标设备 `connectedNames()` 连续 3 次为空即提前收工（比 90s 上限早 ~75s 结束一个必然无产的线程）；或在「上轮成功且本轮 RFCOMM 连入」时根本不起守卫（controlConnected 已是收工条件，可前移成不起条件之一）。
- 改动量：小。回归风险低（提前收工只影响诊断日志行数）。

### A5.【专属·P1】被动窗（PASSIVE_WAIT 30s）与直达拨号在「上轮刚成功拆链」场景重复等待
- 文件：`Iap2BluetoothClient.kt:686-699`（remoteDisconnect 冷却 16s）、`720-730`（outboundPassiveWaitMillis 15s）、`1026-1037`（PASSIVE_FALLBACK 切被动）
- 现状：三层等待可叠加：车机拆链后 `sinceRemote < RETRY_DELAY` 等 16s；若对端主动结束会话另有 15s 被动避让窗；主动拨 2 轮 SDP 失败（或没拿到端点）再切 30s 被动窗。已保存目标 + 上轮成功的典型回连场景里，车机 9 秒内就回连（注释里的实测：L150-157），三层等待中只有第一层必要，第二、三层把下一轮拨号推后 30~45s。
- 建议：上轮 `lastRoundGotEndpoint == true` 且本次失败原因不是 SDP 层时，跳过 passiveFailRounds 累计（链路层失败≠车机不广播服务）；`outboundPassiveWaitMillis` 与 remoteDisconnect 冷却取 max 而不是各自生效。
- 改动量：小-中。判断标准即「已保存目标+上次成功 ⇒ 这些等待不再提供新信息」。

### A6.【通用·P2】`sleepBackoff` 的 NUDGE 截断在已保存目标场景已无意义但机制保留
- 文件：`Iap2BluetoothClient.kt:1471-1487`
- 现状：nudge 来自 CarApproachReceiver（车机靠近信号）。已保存目标 + 车机常在场的场景，退避几乎总在第一档（16s），NUDGE 截断很少触发——非浪费，只是记录：**不要**在 1.2.110 动它。
- 结论：必要机制，保留（列入报告以免误报为浪费）。

---

## B. WiFi 链路

### B1.【专属·P0】已保存 SSID 时 `registerSuggestion` + specifier 双通道同轮并发（日志两条「已登记热点建议」）
- 文件：`SessionController.kt:3353`（0x5703 路径）、`7963`（0x4301 路径）、`WifiCarPlayConnector.kt:503-589`
- 现状：`autoJoinCarWifi` 先 `registerSuggestion`，随后 `joinCarWifiSpecifier → joinCarWifiOnce → joinSpecifierThenSuggestion`；specifier 失败时又走 `joinSilentlyBySuggestion`，其内部**再次** `registerSuggestion`。0x5703 与 0x4301 两条路也各登记一次（WifiJoinGate 只互斥 specifier 本身，不互斥建议登记）。系统虽对重复建议返回 ADD_DUPLICATE，但每轮多条 binder 调用 + 多行日志；更重要的是**建议网络是系统级自动连接**，一旦授权，specifier 弹框路径在已保存目标场景永远不该是首选。
- 建议：`(SSID,密码)` 建议登记结果缓存到 connector 字段（成功一次后本进程不再重复登记，SSID 变更才清）；已保存目标 + 建议已授权（可用「系统已关联过该 SSID 历史」粗判）时入网顺序改为 复用 → 等建议关联(短窗) → specifier，缩短无谓的 specifier 15s 预算占用。
- 改动量：中。单测：WifiJoinGate 不变，新增 connector 的建议去重纯函数。

### B2.【专属·P0】`joinCarWifiOnce` 复用判定成功后仍强制重注册 ctrl 广播并打「已就位」日志——重复但无害；真正的浪费在 specifier 失败后 `retryJoinSpecifierSilently` 的自动重试 N 次
- 文件：`SessionController.kt:3780-3820`（retryJoinSpecifierSilently，SPECIFIER_AUTO_RETRY_TIMES 次）、`WifiJoinGate` 冷却 5s
- 现状：已保存目标 + 建议通道已授权时，specifier Unavailable 通常意味着「确认框无人点」——这在本场景是**注定失败**的路径（车上没人点屏）。当前仍自动重试 N 次（每次绕过 5s 去重 force=true），把 WiFi 总预算（25s）全烧在一条已被判死的通道上。
- 建议：`silentJoinPolicy` 已有「连续 2 次未成停用建议通道」的对称逻辑，给 specifier 也加一层：建议已授权/已关联过该 SSID 的会话里，specifier 失败 1 次即改走 `awaitAssociation` 短窗，不再 force 重试。
- 改动量：中。收益：省 10~15s/轮。

### B3.【通用·P1】`shellWifiStatus()` 在保活观察循环里每 5s 同步执行 `cmd wifi status`（5s 超时的 shell 调用）
- 文件：`SessionController.kt:4000-4023`、调用点 `4205`（watchForPendingStartSession 每 5s）、`3839`、`8013`
- 现状：Shizuku 已移除后 `SysShell.exec("cmd wifi status")` 恒失败——每 5s 白跑一次 5s 超时的 shell（失败返回也要等进程 spawn），然后才落到 frameworkWifiStatus。保活期 5 分钟 = 60 次无效 shell。
- 为什么在已保存目标场景更浪费：0x4301 未到但 SSID 已保存（lastGoodEndpoint/上轮凭据在内存）时，事件驱动路径（registerNetworkCallback 的 onCapabilitiesChanged，WifiLinkWatchdog 已有）完全可回答「现在连的是什么」。
- 建议：watchForPendingStartSession 的 WiFi 状态查询改用 `wifiConnector.networkSnapshot(savedSsid)`（纯内存 binder，毫秒级）；`cmd wifi status` 路径整体删除或仅在 shell 身份真实存在时调用（首次探测一次并缓存布尔）。
- 改动量：小（shellWifiStatus 加一个「shell 恒不可用」记忆位 + 保活循环换 snapshot）。

### B4.【通用·P1】NSD「服务消失 CarPlay」后无脑重启全量发现——已保存 IP 时应先快连
- 文件：`net/CarPlayServiceBrowser.kt:118-162`（start 时 discovering=true 即 restartDiscovery，5 次重试）、`SessionController.kt:5847-5900`（端点解析）、`205-208`（onServiceLost 只打日志）
- 现状：车机 mDNS 记录闪断（onServiceLost "服务消失 CarPlay"）会触发上层在下一轮解析时 `awaitEndpoint` 等满 `ENDPOINT_RESOLVE_WAIT_MS`，期间 browser 可能 restartDiscovery。但 lastKnown 缓存（跨轮保留）与 ctrl 邀请来源地址（`CarPlayCtrlAdvertiser.lastInvitePeer`）在已保存目标场景几乎总是可用，且车机 IP 在同一热点会话内不变。
- 建议：`resolveEndpointHost` 在 mDNS 等待**之前**先做一次 `lastKnown`/`lastInvitePeer` 的零等待 TCP 探测（probeEndpoint 已有，800ms）：可连即直接用，不可连再进入 mDNS 等待。代码里 5847 已取 `awaitEndpoint(0)` 合并，但没有先探测可连性就决定等 mDNS。
- 改动量：小（把探测挪到等待前 + 失败再等待）。收益：mDNS 不稳定机型每轮省 6~18s。

### B5.【通用·P1】WifiLinkWatchdog 5s 轮询签名 + NetworkCallback 双通道，轮询在事件齐全时冗余
- 文件：`net/WifiLinkWatchdog.kt:138`（scheduleWithFixedDelay 5s）、`155-158`（poll）
- 现状：注释自认「5 秒轮询签名」是给 `bindProcessToNetwork` 被顶掉这种回调不报的场景。已保存目标场景下进程网络绑定变化本身有 `boundNetworkForProcess` 对比可做事件化（connector 的 handleNetworkLost/onAvailable 都已知）。5s 轮询本身零日志输出（签名不变不打印），开销主要是每 5s 一次 binder 枚举。
- 建议：轮询间隔放宽到 15s（诊断用途不需要 5s 粒度——断链前后 3s 的答案可由回调时间戳补齐），或仅在「本轮已发生过事件」后收紧为 5s、平时 30s。P2 也可接受「不动」。
- 改动量：一行。

### B6.【通用·P2】ctrl 广播「跳过重复的强制重注册」出现多次：ensureForNetwork 的 force 冷却已生效，但 6 个调用点仍有同秒三连发
- 文件：`CarPlayCtrlAdvertiser.kt:170-209`；调用点 SessionController.kt:3265/3409/4449/6524/6600/6633
- 现状：冷却 2s 只拦「同 SSID 同网络同 MAC」的 force；但 0x5703 对齐（3265）→ join 成功 force（3409）→ 端点交接 force（6524）在正常链路上是 3 次注册语义不同的必要动作（换网络后必须重注册）。真正的冗余是 6600/6633（wakeCarPlayWindow 每次叫醒都 ensureForNetwork(force=true)）在 2s 冷却内被跳过——行为正确，日志噪音。
- 建议：把「跳过重复」日志降为 debug 级（或 15s 节流）；wake 路径在 `registeredSsid == ssid && sameNetwork && !macPlaceholder` 时直接不发 force（提前返回，省一次函数调用+日志）。
- 改动量：很小。

---

## C. 会话与仲裁

### C1.【通用·P1】requestUI 四层防护（3s 去重→永久归属复核→1.5s 节流→连续收回熔断）存在空隙：`lastHandledByUrl` 上限 8 条直接 clear 会把熔断外的所有 URL 冷却一并清掉
- 文件：`airplay/RequestUiFightingGuard.kt:76-80`
- 现状：URL 超过 8 种时 `lastHandledByUrl.clear()` 重来——防御本身合理，但 clear 后同一 HMI 重绘风暴里第 9 种 URL 又能立即触发让出拿回（节流层 1.5s 能兜住一部分）。已保存目标场景下 fighting 是「车机永久持有 + 反复 requestUI」的固定形态，熔断（surrenderRetakeBanned）是终态防护，四层之间真正的空隙是：**去重层清空 + 节流层靠时间**，两层都以时间窗为主，无事件层。
- 建议：clear 改为 LRU 淘汰（保 8 条最近的），不整表清空；一行改动。
- 改动量：很小。另外注意：guard 实例 `SessionController.kt:8068`（requestUiDedupGuard）跨会话复用，而 CarPlayResourceStreams 内另一个 guard 实例随会话重建——surrenderRetakeBanned **不跨会话**。已保存目标场景（每轮都同一台车、同一种 fighting 形态）跨会话保留熔断位能直接跳过第一轮的试探性让出拿回。建议：SessionController 的 dedup guard 把 ban 位也持久化到会话外（进程内存即可），retireSession 不清。
- 改动量：小。

### C2.【通用·P1】0x5001 NowPlaying：内容去重已存在（title|artist 键），但推送时机仍由「变化驱动 + 主动补推」双通道，且补推无节流
- 文件：`iap2/Iap2SenderSession.kt:1645-1727`（pushNowPlaying / sendNowPlayingUpdate，重试延迟 1.5/4/8/15/25s）、`SessionController.kt:860-900`（媒体会话 publish 去重）
- 现状：逐行歌词被 KeepAliveMediaFilter 排掉、title|artist 去重键在位——「媒体信息未变不重发」这条已经成立。剩余问题：0x5001 失败重试阶梯最长 25s×5 次，若链路已死重试也无效（但 Iap2SenderSession 内链路死亡会停泵，风险有限）。已保存目标场景无需改动。
- 结论：必要机制，1.2.110 不动（防误报）。

### C3.【通用·P2】资源归属状态机「状态已知却重新询问」：changeModes 复述后 reconcile 全量重算
- 文件：`airplay/CarPlayResourceStreams.kt:71-135`（reconcile/applyScreen/onCarEcho）
- 现状：每次 changeModes 都 reconcile（读全部流状态再决定启停），没有增量。流数量少（音/视频/反馈各一），成本可忽略。
- 结论：非浪费，保留。真正值得做的是 C1 的跨会话 ban 位。

### C4.【通用·P0】control EOF → 被动等待 → 车机邀请链路上，`awaitCtrlInvite` 50ms 轮询 + 1.2s 一条日志可事件化
- 文件：`SessionController.kt:4351-4460`（awaitCtrlInvite）
- 现状：循环每 50ms 读 `inviteCount`（volatile 读 + 多个分支），1.2s 打一条「等待车机 ctrl 邀请…」。已保存目标场景车机邀请通常 1~3s 内到（mDNS 命中时），50ms 轮询是热循环；更重要的是 `endpointBrowser?.awaitEndpoint(0)` 每圈调用一次（100ms 内部无 sleep 版本即立即返回）。
- 建议：inviteCount 用 CountDownLatch/回调唤醒（onInvite 已有回调钩子，挂一个 latch 即可）；日志节流拉长到 2s。CPU 收益小、日志噪音收益中。
- 改动量：小。

### C5.【专属·P1】EOF 恢复路径里 `pendingWaitGaveUp` 置位后，同一次「启动连接」内车机恢复时不再有观察者，但 BT 侧仍在拨号——状态不同步造成「蓝牙活着、WiFi 侧已放弃」的空转轮
- 文件：`SessionController.kt:4293-4310`（give up 拆保活链）、`Iap2BluetoothClient.kt:1100-1098`（失败后退避继续拨）
- 现状：0x4301 5 分钟预算耗尽 → `detachActiveBtSessions()` 拆保活链 + `pendingWaitGaveUp = true`。但 connectLoop 的失败退避线程仍在跑（除非 detach 生效于所有通道）。detachCurrentSession 只退役当前通道，客户端循环继续——之后车机若自行恢复并回连 …cafe，服务端能接住，这是对的。风险在于 `pendingWaitGaveUp` 只在用户重新点「启动连接」时复位（2887/2951），期间晚到的 0x4301（onLateEndpoint → onCarPlayEndpoint）会被旧预算挡住。
- 建议：车机主动回连成功（onPeerConnected）时清 `pendingWaitGaveUp` 并重置预算——事件驱动复位，不需要等用户。
- 改动量：小。

---

## D. 引擎与前置

### D1.【通用·P1】ConnectionPreflight 每次启动连接重跑全部 14 项，仅有 20s 日志去重，无结果缓存
- 文件：`session/ConnectionPreflight.kt:220-243`（log：20s quiet）、`245-`（inspect 全量只读）
- 现状：自动补连接路径也调 `ConnectionPreflight.log`（SessionController startConnection L2883）。inspect 是只读、多数项毫秒级，但其中 CarWith/画面源体检（L485 起）可能触及引擎状态。20s 去重只压日志、不省计算。
- 建议：把「签名相同 → 直接复用上次 Report」升级为缓存层（inspect 前先比对系统状态快速签名：蓝牙开关、权限位——这些本来就是 inspect 的前几项），签名相同就不跑后面的 CarWith 项。收益中等，风险低。
- 改动量：小-中。

### D2.【通用·P2】CarWith 有线虚拟屏首帧等待已有 GOP 缓存路径（FirstFramePolicy.useCachedGop 默认 true），不重复等首帧
- 文件：`video/FirstFramePolicy.kt`
- 结论：已实现缓存复用，1.2.110 不动（防误报）。

### D3.【通用·P1】`noteSavedNetworkIfAny` 的 `SysShell.exec("cmd wifi list-networks")` 每次入网尝试执行且失败也要等 5s——shell 恒不可用（Shizuku 已移除）
- 文件：`SessionController.kt:3700-3715`、调用点 `joinCarWifiSpecifier` L3394（shellReady 时才调——已门控）
- 现状：调用点有 `if (shellReady)` 门控，shellReady 恒 false，实际不执行。
- 结论：已规避，无浪费（记录防误报）。同 B3：`shellWifiStatus` **没有**这个门控，是 B3 的问题。

---

## E. 跨链路协同与会话清理

### E1.【专属·P0】会话结束清理遗漏：`NetworkReusePolicy.lastSuccessfulJoinPrefix` 与 `forgetPromptHandled` 跨会话永不失效——换车后网段记忆变成错误兜底
- 文件：`net/NetworkReusePolicy.kt:31-52`、`SessionController.kt:1435/3700`
- 现状：1.2.109 的网段兜底记忆只在 `rememberSuccessfulJoinPrefix(ip)` 覆盖时更新，进程内不随会话结束/目标更换失效。用户换一台车（不同网段热点）且新热点 SSID 不可读时，旧网段兜底会**拒绝正确复用**（gateway 不匹配旧前缀 → Rejoin，白 join 一次后才在新 join 成功时更新）。forgetPromptHandled 同理：按 SSID 记忆，换车后自然不同 SSID，无实际影响。
- 建议：`retireSession` / 换目标（TargetDevicePrefs 变更）时清 `lastSuccessfulJoinPrefix`；或兜底命中时校验「网段记忆的龄期 < N 小时」。
- 改动量：很小。注意：进程重启本来就清（易失），影响面只在同进程跨车。

### E2.【专属·P0】会话结束后 `directPolicy.directSdpChecked`、iap2ServiceCache 已随 start() 重置——无需处理（防误报）；但 `BtSessionGate.targetSoftFallback` 在 stop() 不复位
- 文件：`Iap2BluetoothClient.kt:529`（start() 清）、`BtSessionGate:1609`
- 现状：有保存目标时软回退永不启用（1.2.108），targetSoftFallback 实际恒 false，无影响。
- 结论：已闭环，记录防误报。

### E3.【通用·P1】蓝牙死链预检（1.2.109）只守 0x5703 一个入口；0x4301 端点交接路径的 `wifi-join-on-endpoint`（SessionController.kt:7954 起）没有对称预检
- 文件：`SessionController.kt:7966`（直接进 join 循环，无 controlLinkAlive 检查）
- 现状：0x4301 到达时若 BT 控制链路已死（车机拆链后补发的晚到端点、或车机已退出 CarPlay），`wifi-join-on-endpoint` 仍会烧 25s 入网预算——与 1.2.109 修的形态同源。
- 建议：`wifi-join-on-endpoint` 入口加同一句 `bluetoothServer?.controlLinkAlive()` 预检（iAP2 已迁 WiFi 隧道时放行的语义不变）。改动 5 行。
- 改动量：很小。这是 1.2.109 修复的直接补漏，P0。

### E4.【通用·P2】会话结束清理核对清单（本轮走读确认已复位的，防误报）
- 已复位：wifiLinkLost、pendingWaitBudgetStartedAt、streamingInviteCount、KnobRouter、pendingAudioRebuild、resourceStreams、CarLifeHijack.onActualStreamSize、ctrlAdvertiser.resetInviteCount（startConnection 时）、resetNowPlayingDedupe。
- 未复位（有意）：lastGoodEndpoint（解冻恢复要用）、lastInvitePeer（跨轮候选）、lastKnown（mDNS 缓存兜底）、lastPort（ctrl 端口稳定）、NetworkReusePolicy 网段（见 E1，唯一建议改的）、pendingWaitGaveUp（见 C5）。

---

## 1.2.110 建议打包范围

**打包一（P0，互相咬合，一次提交）：**
- E3（0x4301 路径补 BT 预检）—— 1.2.109 语义的直接延伸
- A1（已配对+已保存目标免可发现窗口/续期）
- E1（网段记忆跨车失效）

三条都是「已保存目标场景专属」的小改动，单测各配一个纯函数用例（DirectConnectPolicy.shouldRequestDiscoverable / NetworkReusePolicy 失效时机 / controlLinkAlive 复用现有测试），互不触碰协议层，assembleDebug + 现有 1024 条单测回归即可。

**打包二（P1，第二个提交）：**
- B1/B2（建议通道登记去重 + specifier 失败改短窗等待）
- B3（shellWifiStatus 去 5s 无效 shell + 保活循环改 snapshot）
- B4（端点解析先探测 lastKnown/lastInvitePeer 可连性再等 mDNS）
- A5（passive 等待去叠加）
- C5（车机回连清 pendingWaitGaveUp）

B1/B2 咬合（都在入网三层策略上），B3/B4 咬合（都是「已保存 IP/SSID 时少绕路」），建议一起改、一起用测试台 + 实车各验一轮。

**暂不动（本轮确认为必要机制，防误报）：** A6（NUDGE）、A3 中的显式目标 SDP 诊断、B5（可选放宽）、C2（0x5001）、C3（reconcile）、D2（GOP 缓存已存在）、D3（已门控）、E4 各有意保留项。

**验证清单：** ①已保存目标冷启动回连：确认无「请手动点启动连接」、无多余可发现弹窗；②车机拆链后 16s 冷却内不拨；③换一台车后首轮 join 正常（网段兜底不误挡）；④0x4301 晚到 + BT 已死时不进 join；⑤specifier 失败时建议通道短窗接住，总预算不再烧满。
