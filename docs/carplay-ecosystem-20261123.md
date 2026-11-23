# CarLife → CarPlay 的开源生态调研与差距分析（2026-11-23）

调研对象：GitHub 上与「Android 手机把 CarLife 码流投成 CarPlay 发送端」相关的全部已知项目。
结论先行：**我们缺的不是 CarLife，是 CarPlay 侧按固件对齐的协议栈；而这一块已有开源实现可借鉴。**

---

## 1. 生态全景（方向必须分清）

| 项目 | 方向 | 角色 | 成熟度 | 关键事实 |
|---|---|---|---|---|
| **CarPToCarL**（本仓库） | 手机 → 车机 | CarPlay **发送端** + CarLife 劫持 | 实车跑通（BMW MGU-55-J） | CarLife 7 路端口/4.1 加密/码流直通是**独有资产**；CarPlay 侧靠抓包试错 |
| **CarVeX**<br/>[研究库](https://github.com/iamr0s/carvex-carplay-research) · [实现库](https://github.com/iamr0s/carvex-android) | 手机 → 车机 | CarPlay **发送端**（多协议目标） | 研究预览，**未实车验收** | 按 iPhone 固件逐字段复现；`protocol-carplay` 246 文件 / 27404 行；`protocol-carlife` 仅 43 行 |
| **DiPlay**<br/>[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) | 车机 ← 手机 | CarPlay **接收端** | 上千 star，BYD 实车可用 | 从 Carlinkit C2Air 固件提取 MFi 证书/私钥；装在安卓车机上 |
| **xcertplay**<br/>[shilapi/xcertplay](https://github.com/shilapi/xcertplay) | 车机 ← 手机 | CarPlay **接收端** | 1622 star | CH341 MFi 芯片桥接；DiPlay 的上游 |
| **Carlink**<br/>[lvalen91/Carlink](https://github.com/lvalen91/Carlink) | 车机 ← 盒子 | Carlinkit CPC200-CCPA 适配器控制 | 工具类 | 控制外置 dongle，不含协议实现 |

**关键洞察一**：DiPlay / xcertplay 是**反方向**（让安卓车机当 CarPlay 接收端），它们的 iAP2 知识是「配件侧」视角，对我们「发送端」的直接复用价值有限——但它们的 MFi 证书/身份处理可参考。

**关键洞察二**：CarVeX 与我们是**同一方向**，而且它的模块划分把问题切得很干净（见 §3）。它的 `protocol-carlife` 只有 43 行——**说明 CarLife 侧几乎没人做，这是我们的护城河**。

---

## 2. CarVeX 的模块分工（架构范本）

```
protocol-api        协议契约
protocol-carplay    CarPlay 发送端协议栈（246 文件 / 27404 行）
protocol-carlife    CarLife 协议（2 文件 / 43 行 ← 基本空缺）
host-api            宿主抽象
host-carlife-mixing 宿主适配：CarWith / OPPO CAR+ 接入（19 文件 / 1029 行）
pcm-core            音频
core / app / bridge / platform-android
```

依赖方向被明确写死：**`firmware → research → android`**，且「实现不得当作协议证据」。

对照我们的现状：CarPToCarL 是**单模块耦合**——`carlife/`（劫持）+ `airplay/`（协议）互相调用，`SessionController` 6000+ 行同时知道两边。这就是为什么一个 CarPlay 侧的小缺陷（0x5001 推送门禁）会藏在链路深处查不出来。

---

## 3. CarVeX 覆盖了我们哪些痛点（逐条对应）

| 我们的问题 | CarVeX 对应研究/实现 | 它怎么做的 |
|---|---|---|
| **1 连接不顺畅** | [spec 86/87](https://github.com/iamr0s/carvex-carplay-research/blob/main/research/connection/spec/87-reconnect-lifecycle-audit.md) ACK-only 与重连生命周期；[spec 83](https://github.com/iamr0s/carvex-carplay-research/blob/main/research/connection/spec/83-network-handoff-implementation.md) 网络交接 | 按 handle/endpointUUID 归属状态机；单消费者处理迟到 0x4301；提交后冻结与取消/世代保护 |
| **2 首页无音乐信息** | [spec 85](https://github.com/iamr0s/carvex-carplay-research/blob/main/research/connection/spec/85-runtime-control-recovery.md) Runtime iAP 控制事务 | **普通 iAP 命令用 TransportControlStream 并等待结果**；NULL reply ≠ 单向发送已成功；补有界失效与一次性失败通知 |
| **4/5 主屏仲裁、点了没反应** | `protocol-carplay/core/ScreenResourceModes.kt` | 严格 transferType 状态机（1=take 2=release 3=borrow 4=unborrow），带状态守卫；**requestUI 只应答不动资源** |
| **5 车机退出后无法恢复** | [spec 87/88](https://github.com/iamr0s/carvex-carplay-research/blob/main/research/connection/spec/88-normal-disconnect-implementation.md) 整会话 TEARDOWN | 原固件有**整会话 TEARDOWN** 分支；我们只拆单流再关本地连接，且音频拆流是异步的、不等确认 |
| **3 不自动进首页** | 无对应实现 | CarVeX 也发 `appStates=1:0,2:0,3:0`，`requestUI` 仅 log —— **印证我们的勘误**：这不是 appStates 问题 |

### 3.1 关于问题 3 的新认识（重要）

`ScreenResourceModes.kt` 的 `params()` 生成的就是：

```kotlin
"appStates" to listOf(
  mapOf("appStateID" to 1, "entity" to 0, "speechMode" to -1),
  mapOf("appStateID" to 2, "entity" to 0),
  mapOf("appStateID" to 3, "entity" to 0))
```

即 **appStates 全 0 是正常形态**（与我们的日志一致，也与我们的勘误一致）。
CarVeX 的 `AirSession.kt:947` 处理 `requestUI` 时**只记日志、只应答、不动资源**。

所以「车机不自动进 UI」**不是**协议字段问题。剩下两种可能：
- 车机侧配置（iDrive 里未把本机登记为 CarPlay 设备）；
- 或 BMW 本就要求用户在车机菜单点一次（原版也一样）。

**验证成本极低**：用真 iPhone 连一次，看是否也要手点。这条应该先做，而不是继续改代码。

### 3.2 我们确实缺的三块

1. **iAP 控制事务的「等 reply」语义**（问题 2 的正确方向）
   我们 `postControlIap` 是 `Unit`（1.2.105 才补了失效可见化）。CarVeX 的结论是：普通 iAP 命令必须等结果，`NULL reply` 不代表发送成功。这解释了为什么我们的 0x5001「发出去了」但车机没反应。
2. **整会话 TEARDOWN**（问题 5 的正确方向）
   我们手动停止时是「拆单流 + 关 socket」，没有整会话停止合同，音频拆流还是异步不等确认。车机侧会保留「CarPlay 仍亮着」的残留状态。
3. **主屏仲裁的状态守卫**（问题 4 的 hack 来源）
   我们的 `retakeScreenForUiRequest()` 是「先让出再拿回」的 hack；正确做法是维护 `owner/permanent/borrowed` 三元组，按 transferType 严格迁移，**不做主动让出**。

---

## 4. 路线建议

### 路线 A（推荐）：移植三个具体点，不动架构

成本最低，直接对应问题 2/4/5。逐项：

1. `ScreenResourceModes` 的三元组状态机 → 替换 `AirPlayResourceState` 的让出跳变；`requestUI` 只应答不动资源。
2. iAP 控制事务等 reply → `postControlIap` 改为等待并回传结果，失败通知 `VirtualIap2Link`。
3. 整会话 TEARDOWN → 手动停止时先发整会话 TEARDOWN，等音频拆流确认后再关连接。

### 路线 B（中期）：分层重构

把 `carlife/`（劫持）与 `airplay/`（协议）解耦成 CarVeX 那样的 `host-*` / `protocol-*`，让 CarPlay 协议栈可以独立替换、独立测试。这是"长期跟原生一致"的唯一可持续路径。

### 路线 C（不推荐）：整体替换为 CarVeX

它的 CarLife 侧是空的（43 行），换过去等于放弃我们最核心的实车资产，且它自己声明「不声称真实车机互通」。

---

## 5. 立即可做的验证（零代码）

用真 iPhone 连同一台 MGU，观察**是否也需要在车机菜单手点 CarPlay 图标**：

- 若也需要手点 → 问题 3 是 BMW 固有行为，我们的「不自动」不是缺陷，只需把「点一次后卡住/多次点击」修掉（问题 4/5）；
- 若 iPhone 自动进 → 才值得继续找协议信号。

这一条决定了后续是否要投入抓包对照。
