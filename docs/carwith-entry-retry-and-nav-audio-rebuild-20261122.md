# 直连入口静默拦截兜底 + 导航流重建（1.2.91 · feat/nav-opus-multi-variant）

> 起因：2026-11-22 分支实车反馈的两个问题——
> ① CarWith 有时启动失败；② 音频（导航播报）必须重连会话才恢复。
> 修复参照 `wangxso/mcarplay` 的实车结论（其 `CarLifeLauncher.kt` / `CarLifeChannelConnector.kt`）。

## 一、CarWith 启动失败：MIUI「后台弹出界面」静默拦截

### 1.1 mcarplay 的证据（逆向对照）

mcarplay `CarLifeLauncher.kt` 原注释（2026-10-26 小米实测）：

> 后台发起的 startActivity 可能被系统静默丢弃**且不抛异常**——MIUI「后台弹出界面」
> （私有 app-op 10021）拒绝时 `CarlifeAdbActivity` 从未创建，7240 一直 ECONNREFUSED。

也就是说：`startActivity` 返回 true **不能证明入口真的收到请求**。mcarplay 的处理分三层：
前台判定（`isAppInForeground`）→ 当场告警「需允许后台弹出界面」→ 失败后按
公开入口 → root 旧版服务 → 设置页自动回退重试。

### 1.2 CarPToCarL 原来的缺口

`CarLifeAutoCoordinator.launchInternal` 调起成功即记 LAUNCHED，武装窗口（25s）超时
只 `disarm` 打一行日志——静默拦截的表现正是「有时启动失败」，且日志看不出根因。

### 1.3 修复（按执行时序）

1. **调起时**：`CarLifeLauncher.isAppInForeground()`（`ActivityManager.getMyMemoryState`，
   importance=0 视为前台防误报）判定本次调起是否发生在后台；后台调起当场打一行
   ⚠ 日志点名「后台弹出界面」这个根因。
2. **守候窗口过半（8s）仍零握手**（`CarLifeHijack.handshakeSeen`，新暴露的只读口）→
   自动重发一次直连入口 Intent（每轮窗口最多一次）。判据刻意用「零握手」而不是
   「零帧」：一次握手都没有 = 入口八成没收到请求，重试有用；已有握手只是零帧 =
   引擎侧问题，重发入口无用。重试成功把窗口再延一半。
3. **仍失败**：超时后照旧 disarm 放行连接，日志给出手动路径。

只对直连入口（`CarlifeAdbActivity`）重试——无障碍路径有自己的点击节奏，不插队。

## 二、音频必须重建才正常：导航流换变体重建

### 2.1 根因

导航流（101 AltAudio/default）的多变体轮试（16k mono / 48k mono / 48k stereo Opus）
只在**首连** `startNavAudioChannel` 生效。「重启音频」（方向盘三连击）对导航流只做
`restartPipeline`（清缓冲、RTP 时间轴不动）。若本轮选中的变体是「SETUP 回 200、给端口，
但车机不排播」的档——bit30 位图不写采样率/声道，这种档只有实车放音才能分对错——
清缓冲救不了它，表现成「音频必须重建（重连会话）才正常」。

### 2.2 修复：`rebuildNavAudioChannel`（SessionController）

主音频早有同构的 `rebuildAudioChannel`；导航流现在补齐同款。步骤：

1. 前置检查：会话在线、`phoneOwnsAudio`、旧导航流在跑，不满足直接 false；
2. 取变体候选并按 `AudioProfileMemory.order` 用本车机记忆重排：本轮失败的变体已
   `recordRejected`，天然排到最后——重建一次等于自动换到下一个方案；working 变体排最前；
3. 逐变体 SETUP（3s 超时，`micPort=0`、`fixVolume=false`，与首连同一约束）；每个被拒
   变体即记 `recordRejected`（`markExhausted=false`，SETUP 被拒≠拆链，不拉黑整台车机）；
4. **新流 SETUP 成功才停旧流**——被拒时旧流仍在推，绝不把「有声音」变「没声音」；
5. 装 `NavigationDucking`（导航流建好后才允许压低指令）；
6. 补发会话级 RECORD + `nav.onRecorded()`：新流是会话激活后加入的，不补发只给端口
   不排播（与 `rebuildAudioChannel` 同款，PostRecordWatch 兜底归因）。

一个刻意的排序细节：还在推的同款变体在重建时**先跳过**（候选 >1 个时），重建的
意义就是「换一个」；只剩它一个时才原地重build。

### 2.3 接入点

`restartAudioNow`（三连击 / 「立即重启一次」按钮）主音频重建成功后，若导航流仍在跑，
接着调 `rebuildNavAudioChannel`——一次按键把两条流都换新，不再依赖重连会话。

## 三、验证

- `testDebugUnitTest`：**968 tests 0 failed**；
- `spotlessKotlinCheck`：本次 4 个改动文件全部合规（MainActivity.kt 为基线既有漂移，
  按本分支 fb8a966 的约定不触碰）。
