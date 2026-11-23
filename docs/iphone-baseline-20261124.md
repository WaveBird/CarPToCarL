# 真 iPhone CarPlay 行为基线（2026-11-24 采集）

> 本文件是 **Android 端缺陷修复的唯一事实依据**。
> 每条结论后标注数据来源（文件 + 包序号 `#n` / 日志行号 `Lnnn` / 时刻）。
> 凡解析不出来的写「无法解析」，凡加密的写「加密」，不做推测性补全；推断性内容单独标 **[推断]**。

---

## 0. 元信息与角色判定

### 0.1 数据源

| 会话 | 目录 | T0（epoch / 墙钟） | 采集窗口 | pcap 包数 | oslog 行数 |
|---|---|---|---|---|---|
| P1 首次配对 | `/tmp/cap/p1/20261124-001244/` | `1795450364.265522` / `2026-11-24T00:12:44.265+0800` | 00:12:44 – 00:19:58（7m14s） | 3502 | 1,257,347 |
| P2 断开重连 | `/tmp/cap/p2/20261124-002247/` | `1795450967.125964` / `2026-11-24T00:22:47.126+0800` | 00:22:47 – 00:25:01（2m14s） | 2956 | 166,104 |

补充源：
- `.../meta.txt`：T0 / LIMIT / FILTER / `PCAP_PACKETS` 校验值
- `.../summary.txt`：采集自述（**其中关于 oslog 关键字的说法与实测不符，见 §C.0**）
- `.../snap/01-interfaces.txt`、`snap-stop/interfaces.txt`：接口快照（只有 `en0 [Up, Running, Wireless, Associated]`，**不含 IP 地址**）

### 0.2 两端角色判定（不是猜的，有报文依据）

| 端点 | 角色 | 判定依据 |
|---|---|---|
| `172.16.222.97` | **车机**（MGU-55-J，接收端） | 它是 7000 / 5001 / 6030 的监听侧；应答 `Server: AirTunes/365.10.3`（P1 #94） |
| `172.16.222.106` | **iPhone**（发送端） | 它主动连 `:7000`；请求带 `User-Agent: AirPlay/635.87.3` + `X-Apple-Client-Name: 12`（P1 #92） |
| `fe80::1848:f8aa:1f49:5188` / `fe80::1026:633a:6e5:2477` | **无法确定** | IPv6 link-local 一条 TCP 流，payload 加密，无明文可判身份（见 §A.1 注） |

> 跨会话连续性已核实：P1 的 3 条主连接（源端口 50562 / 50563 / 52146）在 P2 开头仍然存活，是同一条 TCP 会话继续（P2 中这三条都"无 SYN"）。序列号连续性证明见 §A.6。

### 0.3 时间基准说明

- pcap 时间戳为微秒精度，与 T0 同一时钟，**已精确到毫秒（下表给到 µs）**。
- oslog 行首时间只到**秒**；`kernel_task` 行另带 uptime（如 `000475.951171`），可用于亚秒定位，但本文件未把 uptime 换算成绝对时刻（缺 boot 时刻锚点），凡引用只用其秒级时刻。

---

## 1. 结论摘要

1. **真 iPhone 自己也要重试 4 次才连上**：P1 里车机的 Bluetooth Classic 链路在 00:13:20 到 00:16:47 之间**断了 4 次**（`00:13:47 / 00:15:10 / 00:15:37 / 00:16:08`），iAP2 端点被建了又拆 6 次，稳定连接在 00:16:47 才建立 —— 从首次连上到稳定共 **3 分 27 秒**。所以「要重试好几次」在真机上是**复现的**，不是 Android 独有现象（§C.2、§C.3）。

2. **重连没有"少步骤"**：P1（首次）与 P2（重连）**协议步骤完全一致** —— 都是 `GET /ctrl-int/1/connect` → 连 7000 → `POST /pair-verify` 四报文 → 加密 setup 突发 → 5001 → 6030。两次的 pair-verify 报文长度逐字节相同（255/275/344/117）。差别**只在时序**（§A.6）。

3. **明文 RTSP 只有 12 个包**：整个两个会话里能读出方法名的只有 `GET /ctrl-int/1/connect`（HTTP，车机发起）和 `POST /pair-verify`（RTSP，CSeq 0/1）共 2 轮 4 报文。**`GET /info` / `SETUP` / `RECORD` / `SET_PARAMETER` / `TEARDOWN` 一个都没有以明文出现**；`timingPort` / `eventPort` / `display` 参数**取不到**（全在加密段内，§B.3）。

4. **"不自动进 CarPlay"在真机上的形态是：AirPlay 会话早就通了，但视频通道几乎不传数据**。P1 里 7000/5001/6030 三条通道在 `00:16:50.419` 全部建立、pair-verify 已成功，但 6030 在接下来 **130.9 秒只送了 788,925 B（平均 48.2 kbit/s）**。真正的码流突发（2.64 MB / 3.3 s ≈ 6.5 Mbit/s）出现在 `00:22:48`，即 **6 分钟后**——所以"点一次才进"对应的不是连接失败，而是**视频起流被推迟**（§A.4、§A.6）。

5. **首页音乐信息走的是 iAP2，真机发了海量 0x5001**：`accessoryd` 在 P1 里收到车机 **`0x5000` 仅 1 次**（00:14:09，186 B），却**主动发出 `0x5001` 273 次**（P1 134+8+7+125）。注意 `0x5000` 只出现在**第一次（短命的）iAP2 连接**上，之后车机再没请求过，iPhone 仍然继续发 —— 这是「不依赖 0x5000 请求也推送 NowPlaying」的直接证据（§C.5）。

---

## 2. A 节：WiFi / AirPlay 会话时序

### A.1 每条 TCP 连接的建立（三次握手）

**P1 首次配对**（时间均含 µs，取自 `wifi.pcap`）

| # | 连接（客户端 → 服务器） | SYN | SYN-ACK | RTT | 首个 payload | 结束 | 说明 |
|---|---|---|---|---|---|---|---|
| 1 | `.97:59030` → `.106:50560` | 00:16:48.161371 `#77` | 00:16:48.164202 `#78` | 2.831 ms | 00:16:48.197606 `#81` | FIN/ACK 00:16:48.202836 `#85`，RST 00:16:48.204167 `#87` | **车机反连 iPhone 的 50560**，全程 42.8 ms |
| 2 | `.106:50562` → `.97:7000` | 00:16:48.692981 `#89`（flags `SEC`） | 00:16:48.697188 `#90` | 4.207 ms | 00:16:48.710378 `#92` | 抓包结束时仍开 | **控制通道** |
| 3 | `.106:50563` → `.97:5001` | 00:16:49.155785 `#111` | 00:16:49.157958 `#112` | 2.173 ms | 00:16:49.481340 `#149` | 抓包结束时仍开 | **事件通道** |
| 4 | `.106:50692` → `.97:6030` | 00:16:50.327707 `#231` | 00:16:50.330691 `#232` | 2.984 ms | 00:16:50.419185 `#245` | FIN/ACK 00:18:21.645668 `#3300` | **视频通道第 1 条**（寿命 131.3 s） |
| 5 | `.106:52146` → `.97:6030` | 00:18:33.218977 `#3318` | 00:18:33.222427 `#3319` | 3.450 ms | 00:18:33.317481 `#3325` | 抓包结束时仍开 | **视频通道第 2 条**（重建） |
| 6 | `fe80::…5188:50462` ↔ `fe80::…2477:49156` | **无 SYN**（抓包前已建立） | — | — | 00:14:38.497434 `#5` | FIN/ACK 00:17:46.887871 `#75`，RST `#76` | 76 包，双向加密，身份无法判定 |

**P2 断开重连**

| # | 连接 | SYN | SYN-ACK | RTT | 首个 payload | 结束 |
|---|---|---|---|---|---|---|
| a | `.106:50562` → `.97:7000`（P1 遗留） | **无 SYN** | — | — | 00:22:49.128619 `#5` | FIN/ACK 00:23:10.030893 `#913` |
| b | `.106:50563` → `.97:5001`（P1 遗留） | **无 SYN** | — | — | 00:22:49.234860 `#12` | FIN/ACK 00:23:10.031234 `#915` |
| c | `.106:52146` → `.97:6030`（P1 遗留） | **无 SYN** | — | — | 00:22:48.231065 `#3` | FIN/ACK 00:22:52.019145 `#907` |
| d | `.97:59222` → `.106:50560` | 00:23:24.158345 `#921` | 00:23:24.158624 `#922` | **0.279 ms** | 00:23:24.161757 `#925` | FIN 00:23:24.165310 `#929`（全程 11.0 ms） |
| e | `.106:53710` → `.97:7000` | 00:23:24.273886 `#933` | 00:23:24.277888 `#934` | 4.002 ms | 00:23:24.309021 `#936` | 抓包结束时仍开 |
| f | `.106:53712` → `.97:5001` | 00:23:24.775187 `#957` | 00:23:24.777521 `#958` | 2.334 ms | 00:23:25.102652 `#995` | 抓包结束时仍开 |
| g | `.106:54123` → `.97:6030` | 00:24:14.267872 `#1937` | 00:24:14.270567 `#1938` | 2.695 ms | 00:24:14.353433 `#1968` | 抓包结束时仍开 |

> 注（#6，IPv6 流）：两端都是 `fe80::` link-local，源/目的端口 50462 / 49156 都是高位端口，payload 高熵（无明文 banner），无法判定是哪个进程。**它比 AirPlay 会话早 2 分 10 秒结束（00:17:46）**，与 CarPlay 时序无耦合。**[推断]** 可能是采集工具自身的远程会话通道，但无证据，仅作记录。

### A.2 7000 控制通道：每一次请求/应答

结构：严格「一请求一应答」交替，共 **676 个 burst**（P1）/ **268 个**（P2）。完整逐条清单见
`docs/data/iphone-baseline-20261124/P1_ct7000_bursts.csv`、`P2_ct7000_bursts.csv`。

**明文段（P1，可读方法名）—— 全部 4 个报文，一个不漏：**

| 序 | 时刻 | 方向 | 长度 | 内容 |
|---|---|---|---|---|
| 1 | 00:16:48.710378 `#92` | C→S | 255 B | `POST /pair-verify` CSeq 0 |
| 2 | 00:16:48.715310 `#94` | S→C | 275 B | `RTSP/1.0 200 OK` CSeq 0（**+4.932 ms**） |
| 3 | 00:16:48.717910 `#96` | C→S | 344 B | `POST /pair-verify` CSeq 1（+2.600 ms） |
| 4 | 00:16:48.721681 `#97` | S→C | 117 B | `RTSP/1.0 200 OK` CSeq 1（+3.771 ms） |

pair-verify 全轮 **11.303 ms**（00:16:48.710378 → 00:16:48.721681）。

**加密段（紧随其后，只能给长度）：** 首个加密请求 `#99` 在 00:16:48.722490（上一条应答后 **0.8 ms**），此后全部为「加密，长度 N，无法解析」：

| 序 | 时刻 | 方向 | 长度 | 备注 |
|---|---|---|---|---|
| 5 | 00:16:48.722490 `#99` | C→S | 179 B | 加密，无法解析 |
| 6 | 00:16:49.096849 `#101` | S→C | 1042 B ×1 + 187 B ×2（burst 合计 1229 B） | 加密，无法解析 |
| 7 | 00:16:49.114051 `#107` | C→S | 675 B | 加密，无法解析 |
| 8 | 00:16:49.154104 `#109` | S→C | 275 B | 加密，无法解析 |
| 9 | 00:16:49.159334 `#114` | C→S | 107 B（0.282 s 后重传同内容） | 加密，无法解析 |
| 10 | 00:16:49.290745 `#118` | S→C | 11 包 / 10,697 B | 加密，无法解析 |
| 11 | 00:16:49.297392 `#137` | C→S | 118 B | 加密，无法解析 |
| 12 | 00:16:49.301355 `#139` | S→C | 73 B | 加密，无法解析 |

**据此可得的节奏事实（全部逐条可复核，见 CSV）：**

| 指标 | P1 | P2（新会话） |
|---|---|---|
| burst 总数 | 676 | 268 |
| 建立后前 8 s 内 | **216 burst / 406,286 B**（00:16:48.7–00:16:56.7） | 180 burst / 452,863 B（00:23:24.3–00:23:32.3）；其中 **10 个 23–37 KB 的大 burst 全部挤在 00:23:25.806–26.202 这 0.4 s 内** |
| 8–60 s | 52 burst / 11,339 B | 78 burst / 18,767 B |
| >60 s | 408 burst / 193,944 B | — |
| 稳态心跳 | **约 1 次/秒**（每 10 s 窗口稳定 10 个 burst） | 同 |
| 请求体典型长度 | 107 / 118 / 245–300 / 385–474 B | 同 |
| 应答体典型长度 | 73 / 183 / 184 B | 同 |
| 累计字节 C→S | 519,632 B（实抓 518,798，被滤掉 1,448） | 473,774 B（实抓 433,409，被滤掉 40,544） |
| 累计字节 S→C | 106,417 B（实抓 93,572，被滤掉 13,032） | 51,432 B（实抓 38,587，被滤掉 13,032） |

> 「建立后第 4 秒出现约 358 KB 的应答确认量」是 P1 控制通道最大的单秒毛刺（ACK 计量，见附录命令）；其应用层语义**无法判定**（加密）。

### A.3 5001 事件通道

| 指标 | P1 | P2 |
|---|---|---|
| TCP 建立 | 00:16:49.155785（7000 SYN 后 **462.8 ms**） | 00:23:24.775187（7000 SYN 后 **501.3 ms**） |
| 首个 payload | 00:16:49.481340，93 B | 00:23:25.102652，93 B |
| **有数据** | 有 | 有 |
| C→S 包数 / 字节 | 59 / 6,027 B | 59 / 5,487 B |
| S→C 包数 / 字节 | 61 / 14,807 B | 60 / 14,607 B |
| payload 长度范围 | **83 – 1042 B**（均值 176.1） | 同量级 |
| 典型长度 | 93 / 205–233 / 1042 | 同 |
| 结束 | 抓包结束时仍开 | FIN 00:23:10.031234（上会话） |

事件通道是**双向小报文**：C→S 以 93 B 为主（心跳/请求），S→C 为 205–375 B 的应答 + 偶发 1042 B 大包（MTU 满载）。全程**加密**，无明文。

### A.4 6030 视频通道（首包 / 码率）

> **口径说明**：6030 全程加密，因此只能报「首个 payload 的时刻与长度」和「接收端 ACK 推进量」。
> 「首帧」是应用层语义，**无法从密文证明**，本文件不声称"首帧"，只声称"首个 payload"。

| 指标 | P1 第 1 条（`50692`） | P1 第 2 条（`52146`） | P2 遗留（`52146`） | P2 新（`54123`） |
|---|---|---|---|---|
| SYN | 00:16:50.327707 | 00:18:33.218977 | —（P1 遗留） | 00:24:14.267872 |
| **首个 payload** | **00:16:50.419185** `#245` | 00:18:33.317481 `#3325` | 00:22:48.231065 `#3` | 00:24:14.353433 `#1968` |
| **首包大小** | **692 B**（wire 758） | 174 B | 322 B | 996 B |
| 存活 | 131.3 s（至 00:18:21.646） | P1 内数据窗口 39.9 s；连接本身一直活到 P2 的 00:22:52.019 | 3.3 s（至 00:22:52.019） | ≥ 30.9 s |
| 送达字节（ACK 计量） | **788,925 B** | 33,966 B | **2,642,806 B** | 1,697,748 B |
| 平均码率 | **48.2 kbit/s** | 6.8 kbit/s | **6,468 kbit/s** | **439.3 kbit/s** |
| 峰值单秒 | 153,025 B（1.22 Mbit/s，建立首秒） | 21,086 B | **1,305,860 B（10.45 Mbit/s）** | 467,464 B（3.74 Mbit/s） |
| 活跃秒数（>2 KB） | 54 / 131 s | 1 / 40 s | 3 / 3.3 s | 6 / 31 s |
| 方向 | **仅 C→S**（车机侧 S→C payload = **0 B**） | 同 | 同 | 同 |

**要点（可直接引用）**：
- 6030 是**单向**通道：四条连接的车机→手机方向 payload 均为 0 B。
- 同一条 P1 视频通道（`52146`）在 P1 里 40 s 只送 33,966 B，在 P2 里 3.3 s 送 2,642,806 B。**同一 socket、同一会话**（序列号连续性见 §A.6）。即：**视频通道先"挂了 4 分钟"（每秒几十 kbit/s），随后才有真正的码流突发**。
- 码率不是恒定值：安静期约 **4–48 kbit/s**，突发期 **6.5–10.45 Mbit/s**，跨度 200 倍以上。任何「CarPlay 码率是多少」的单一数字都会误导。

### A.5 关键时间点

| 事件 | 时刻 | 来源 |
|---|---|---|
| BT Classic 首次连上（车机 `BMW 71280` / `88:5A:85:13:47:05`） | **00:13:20** | oslog `Update devices: connected, classic` 由 `0` → `1` |
| BT 断开 ×4 | 00:13:47 / 00:15:10 / 00:15:37 / 00:16:08 | 同上（`1` → `0`） |
| **BT 稳定连上**（此后不再断） | **00:16:47** | 同上（`0` → `1`） |
| 车机反连 `50560`（`GET /ctrl-int/1/connect`） | 00:16:48.161371 | P1 `#77` |
| **7000 首个 RTSP 请求** | **00:16:48.710378** | P1 `#92` |
| pair-verify 成功 | 00:16:48.721681 | P1 `#97`；oslog `Control pair-verify CU-AP success` L992042 |
| 5001 建立 | 00:16:49.155785 | P1 `#111` |
| 6030 建立 + 首个 payload | 00:16:50.327707 / **00:16:50.419185** | P1 `#231` / `#245` |

**题目要的两段延迟：**

- **「蓝牙连上」→「7000 首个 RTSP」**
  - 以**首次**连上（00:13:20）计：**208.7 s**（含 4 次断连重试）
  - 以**稳定**连上（00:16:47）计：**1.71 s** ← 这才是"链路一旦稳定后"的真实延迟
- **「首个 RTSP」→「6030 首个 payload」：1.709 s**（00:16:48.710378 → 00:16:50.419185）
  - 分解：RTSP→6030 SYN 1.617 s，6030 SYN→首 payload 91.5 ms
- 车机反连 `50560` → 7000 SYN：P1 **531.6 ms**，P2 **115.5 ms**
- 7000 SYN → 首个 RTSP：P1 **17.4 ms**（SYN-ACK 后 13.2 ms）

### A.6 首次配对 vs 重连：差异对比

**先说结论：协议步骤一个没少。** 两次是同一套流程：

| 步骤 | P1 首次 | P2 重连 | 是否相同 |
|---|---|---|---|
| 车机反连 iPhone `50560` + `GET /ctrl-int/1/connect` | 有 | 有 | ✅ 相同 |
| 连车机 `7000` + `POST /pair-verify` ×2 | 有（255/275/344/117 B） | 有（**255/275/344/117 B，逐字节同长**） | ✅ 相同 |
| 加密 setup 突发 | 216 burst / 406 KB | 180 burst / 453 KB | ✅ 相同 |
| 建 `5001` | 有（+462.8 ms） | 有（+501.3 ms） | ✅ 相同 |
| 建 `6030` | 有（+1.64 s） | 有（**+62.99 s**） | ⚠️ 时序差 38 倍 |
| `/pair-setup`（首次配对才该有） | **无** | **无** | ➖ 两次都没有 |

> `/pair-setup` 在两个会话里都不存在（全 pcap 明文搜索 + oslog 搜索 `pair-setup` = 0 命中）。说明**真正的配对（pair-setup）发生在两个抓包之前**，这两段抓的只是 pair-verify。这一点很重要：不能用这两份数据研究首次配对的 pair-setup。

**耗时差异：**

| 指标 | P1 | P2 | 差 |
|---|---|---|---|
| 反连 → 7000 SYN | 531.6 ms | 115.5 ms | P2 快 **4.6×** |
| 7000 SYN → 5001 SYN | 462.8 ms | 501.3 ms | 基本相同 |
| 7000 SYN → 6030 SYN | **1.635 s** | **62.994 s** | P2 慢 **38.5×** |
| 7000 → pair-verify 完成 | 11.3 ms | 13.3 ms | 基本相同 |
| 会话前 BT 断连次数 | **4 次**（00:13:47~00:16:08） | **0 次**（P2 内未见断连） | — |

**P2 的"断开"其实是 P1 那条会话的收尾**，时序完整（这是本文档最硬的证据链之一）：

| 时刻 | 事件 | 来源 |
|---|---|---|
| 00:22:48.231 | 视频通道（P1 遗留 socket）开始大量送数据 | P2 `#3` |
| 00:22:48–00:22:51 | 3.3 s 内送达 2,642,806 B（6.47 Mbit/s，峰值 10.45 Mbit/s） | ACK 计量 |
| 00:22:49 | oslog：`CarPlay[3609]` 启动 app、`stark mode fsm_requestModeChange: Screen: iOS Take UserInitiated` | P2 L157 / L~ |
| **00:22:52.019** | **6030 FIN/ACK**（视频通道关） | P2 `#907` |
| 00:23:10.031 | 7000 与 5001 同时 FIN/ACK | P2 `#913` / `#915` |
| 00:23:23 | oslog：`Expected BluetoothConnectionStarted Event ... Creating new SessionUUID`、`Handling CarPlay attach` | P2 L~ |
| 00:23:24.158 | 车机反连 `50560`（新一轮） | P2 `#921` |
| 00:23:24.274 | 新 7000 SYN | P2 `#933` |
| 00:23:24.309 | 新 pair-verify 开始 | P2 `#936` |
| 00:24:14.268 | 新 6030 SYN（**63 s 后**） | P2 `#1937` |

**序列号连续性验证**（证明 P1/P2 的 52146、50562、50563 是同一批 TCP 连接）：

| 端口 | P1 末包 seq | P2 首包 seq | 差值 | 判定 |
|---|---|---|---|---|
| 5001 / 50563 | 4,082,696,038 | 4,082,696,401 | **363 = 上包 plen** | **完全连续** |
| 6030 / 52146 | 810,468,791 | 810,565,954 | 97,163 | 同一 socket，未抓窗口内推进 97,163 B |
| 7000 / 50562 | 1,824,731,465 | 1,824,739,033 | 7,568 | 同一 socket，未抓窗口内推进 7,568 B |

> 推论（**有据**）：未抓的 175 s 窗口（00:19:53 → 00:22:47）里，视频通道只推进了 **97 KB**（≈4.4 kbit/s），控制通道只有 7.6 KB。**所以「视频是在未抓窗口里开始跑的」这个假设被数据否掉了** —— 视频真正上量就在 00:22:48 那 3 秒。

---

## 3. B 节：RTSP 参数全集

### B.1 明文报文全量（12 个包，P1 与 P2 各 6 个，逐字抄录）

**① 车机 → iPhone：`GET /ctrl-int/1/connect`（P1 #81，00:16:48.197606，128 B，**HTTP 不是 RTSP**）**

```
GET /ctrl-int/1/connect HTTP/1.1
Host: 12.local.
User-Agent: AirPlay/365.10.3
AirPlay-Receiver-Device-ID: 149922361067269
```
（无 `Content-Length`，请求体 0 B）

**② iPhone → 车机：200 OK（P1 #83，00:16:48.199775，75 B，+2.2 ms）**

```
HTTP/1.1 200 OK
Date: Mon, 23 Nov 2026 16:16:48 GMT
Content-Length: 0
```
> 注意：`Date` 是 GMT，对应本地 00:16:48（+0800），与包时间戳自洽。**没有** `Connection: close`。

**③ iPhone → 车机：pair-verify 第一轮（P1 #92，00:16:48.710378，255 B）**

```
POST /pair-verify RTSP/1.0
X-Apple-AbsoluteTime: 817143408
X-Apple-HKP: 2
X-Apple-Client-Name: 12
X-Apple-PD: 1
Content-Length: 37
Content-Type: application/octet-stream
CSeq: 0
User-Agent: AirPlay/635.87.3
```
请求体 37 B：`06 01 01 03 20 91 95 12 7d 76 78 d6 e1 9c db d6 a0 c3 d8 79 ee f2 a8 25 f6 32 8d 9b e7 8b df 8b 3b 2d 5e ca 7d`
（TLV：`06 01 01` = state=1，`03 20` = 32 字节公钥）

**④ 车机 → iPhone：200 OK（P1 #94，00:16:48.715310，275 B，+4.932 ms）**

```
RTSP/1.0 200 OK
Content-Length: 159
Content-Type: application/octet-stream
Server: AirTunes/365.10.3
CSeq: 0
```
请求体 159 B（`05` + 32 B 车机公钥 + `06 01 02 03 20` + 32 B 签名 + …），首 16 字节 `05 78 e9 79 e2 d0 14 b1 2e 2c a8 f2 a0 bd f4 97`

**⑤ iPhone → 车机：pair-verify 第二轮（P1 #96，00:16:48.717910，344 B）**

```
POST /pair-verify RTSP/1.0
X-Apple-AbsoluteTime: 817143408
X-Apple-HKP: 2
X-Apple-Client-Name: 12
X-Apple-PD: 1
Content-Length: 125
Content-Type: application/octet-stream
CSeq: 1
User-Agent: AirPlay/635.87.3
```
（比第一轮多 88 B 头部 —— 差异全在请求体：37 → 125 B）

**⑥ 车机 → iPhone：200 OK（P1 #97，00:16:48.721681，117 B，+3.771 ms）**

```
RTSP/1.0 200 OK
Content-Length: 3
Content-Type: application/octet-stream
Server: AirTunes/365.10.3
CSeq: 1
```
请求体 3 B：`06 01 04`（TLV state=4）

**P2 的 6 个包与此完全同构**（`#925`/`#927`/`#936`/`#938`/`#940`/`#941`，长度 **128/75/255/275/344/117**，逐字节同长），仅 `Date` 与时间戳不同。**重连不换参数。**

### B.2 所有能提取的头字段汇总

| 头字段 | 出现处 | 值 | 备注 |
|---|---|---|---|
| `X-Apple-AbsoluteTime` | pair-verify 两轮 | **817143408** | ⭐**自校验通过**：817143408 + 978307200（2001-01-01 起的 Apple epoch 偏移）= **1795450608** = 包时间戳秒，误差 0 |
| `X-Apple-HKP` | pair-verify 两轮 | **2** | |
| `X-Apple-Client-Name` | pair-verify 两轮 | **12** | 与 Apple ID 名 "12" 一致 |
| `X-Apple-PD` | pair-verify 两轮 | **1** | |
| `User-Agent`（手机） | pair-verify 两轮 | **`AirPlay/635.87.3`** | |
| `User-Agent`（车机） | ctrl-int GET | **`AirPlay/365.10.3`** | |
| `Server`（车机） | 两个 200 OK | **`AirTunes/365.10.3`** | |
| `CSeq` | 明文段 | **0, 1**（从 0 起） | |
| `Content-Type` | pair-verify | **`application/octet-stream`** | |
| `Content-Length` | 明文段 | 37 / 159 / 125 / 3 | |
| `AirPlay-Receiver-Device-ID` | ctrl-int GET | **149922361067269** | 车机的 receiver ID |
| `Host` | ctrl-int GET | **`12.local.`** | 手机名 + `.local` |
| `Date` | 手机上行的 HTTP 响应 | `Mon, 23 Nov 2026 16:16:48 GMT` | |

**未出现的头**（明确声明，避免"以为有"）：
- `X-Apple-Device-*`：**0 个**
- `X-Apple-Session-*`：**0 个**
- `X-Apple-ET` / `X-Apple-ProtocolVersion` / `X-Apple-SSID`：**0 个**（这些在真机上通常属 pair-setup 或加密段）

### B.3 方法名识别与"取不到"的部分

| 方法 | 是否以明文出现 | 说明 |
|---|---|---|
| `GET /ctrl-int/1/connect` | ✅ 明文 | 走 HTTP/1.1，车机发起 |
| `POST /pair-verify` | ✅ 明文 | 走 RTSP/1.0，CSeq 0 与 1 两轮 |
| `POST /pair-setup` | ❌ **不存在** | 两会话全文搜索 0 命中 |
| `GET /info` | ❌ 明文不可见 | 可能在加密段，也可能未发送 —— **无法区分** |
| `SETUP` | ❌ 明文不可见 | 加密，无法解析 |
| `RECORD` | ❌ 明文不可见 | 加密，无法解析 |
| `SET_PARAMETER` | ❌ 明文不可见 | 加密，无法解析 |
| `FLUSH` / `TEARDOWN` | ❌ 明文不可见 | 加密，无法解析 |

**关于 `timingPort` / `eventPort` / `display` 参数**：
> **取不到。** 这三类参数只可能出现在 `SETUP` 的 `application/x-apple-binary-plist` 请求体里，而 SETUP 发生在 pair-verify 成功之后（00:16:48.722 起），**该段全部加密**。本文档不给出任何推测值。
> 可确证的相关事实只有两条，且是从 TCP 层直接看到的（不依赖解密）：
> - 事件通道 = **车机 5001**（手机主动连）
> - 视频通道 = **车机 6030**（手机主动连，单向）
> 这与「SETUP 里会携带 eventPort / timingPort」的常识一致，但**具体的 port 号来自端口观测，不是从 SETUP 参数读出的**。

### B.4 加密边界（精确定位）

- **加密起点**：`#99`（00:16:48.722490），即 CSeq 1 的 200 OK 之后 **0.8 ms**。
- 此后 7000 上所有 payload 的 Shannon 熵 ≈ **7.6–7.9 bit/byte**（满值 8.0），可打印字符占比 0.31–0.42（相当于随机）。
- 判定方法：对全部 1636（P1）/ 941（P2）个 7000 报文做 `RTSP/1.0|POST |GET |SETUP|RECORD|SET_PARAMETER|OPTIONS|TEARDOWN|FLUSH|X-Apple|AirPlay|AirTunes|Content-|CSeq|Server:` 全文匹配 —— **只有 6 个命中**（就是 B.1 那 6 个）。
- 5001 / 6030 通道**从第一个 payload 起就是密文**，无任何明文阶段。

---

## 4. C 节：oslog 关键状态

### C.0 先纠正一条：`summary.txt` 的说法与实测不符

`summary.txt` 称：*"iAP2 / RTSP / NowPlaying / requestUI 关键字命中为 0"*。**这条是错的**，实测（P1）：

| 关键字 | `grep -ci` 命中 |
|---|---|
| `NowPlaying` | **5638** |
| `iAP2` | **1461** |
| `0x5001` | **273** |
| `0x5000` | **1** |
| `pair-verify` | **13** |
| `requestUI` | **0** ✅（这条确实为 0） |
| `changeModes` | **0** ✅（这条确实为 0） |
| `pair-setup` | **0** |

> 影响：如果之前有人依据 summary 认定「oslog 里没有协议信息」，会漏掉 0x5000/0x5001 这条最关键的线索。**本章数据推翻该结论。**

### C.1 `CarPlay active` 状态翻转

全 P1 **只有 1 行**（**没有** 1→0 的翻转被记到）：

```
L916883  <Notice> 00:16:24 kernel_task[0]: 000475.951171 InterfaceLogs.A[58] [dk]
         clearTrafficRegistrationState@7073: CarPlay active 1, low latency 0, AirPlay 0, SideCar 0
```

- 唯一翻转点：**00:16:24**，`CarPlay active` **0 → 1**（其余三项 `low latency`/`AirPlay`/`SideCar` 均为 0）。
- 该时刻**早于** 7000 首个 RTSP（00:16:48.710）**24.7 秒**，也早于 BT 稳定连上（00:16:47）。
- **1 → 0 的翻转没有出现**（P1 抓包在会话仍活时结束）。
- P2（166,104 行）中 `CarPlay active` 命中 **0 次** —— 所以 P2 无法提供激活翻转证据。

### C.2 Bluetooth Classic 链路状态翻转（重试问题的核心证据）

`bluetoothd` 的 `Update devices: connected, classic, N total` 计数翻转（P1）：

| 时刻 | 值 | 间隔 | 对应 iAP2 端点事件 |
|---|---|---|---|
| 00:13:20 | **0 → 1** | — | 首次连上；`Creating endpoint B26B18AD`（00:13:20） |
| 00:13:47 | 1 → **0** | 存活 27 s | `Destroyed endpoint B26B18AD` |
| 00:13:58 | 0 → **1** | 断 11 s | `Creating endpoint ACBE8C8C`（00:14:07） |
| 00:15:10 | 1 → **0** | 存活 72 s | `Destroyed endpoint ACBE8C8C` |
| 00:15:11 | 0 → **1** | 断 **1 s** | `Creating endpoint 89BCE317`（00:15:11） |
| 00:15:37 | 1 → **0** | 存活 26 s | `Destroyed endpoint 89BCE317` |
| 00:15:42 | 0 → **1** | 断 5 s | `Creating endpoint 9D63D450`（00:15:42） |
| 00:16:08 | 1 → **0** | 存活 26 s | `Destroyed endpoint 9D63D450` |
| **00:16:47** | 0 → **1** | 断 39 s | `Creating endpoint 243808B2`（00:16:47）、`F4442C3A`（00:16:49）→ **稳定到抓包结束** |

- **结论：4 次断连 + 5 次连接尝试，03 分 27 秒才稳定。** 这是真 iPhone 的实测，不是 Android 的行为。
- P2 中：`00:23:23 → 1`、`00:23:25 → 0`（仅 2 次翻转）。
- 车机设备身份：`BDA 88:5A:85:13:47:05, Nm 'BMW 71280', VID 0x05EB, PID 0x0118`（L12567 起，与用户提供的 BT MAC 一致）。
- 车机 WiFi BSSID：`DIRECT-BMW 71280(8a:5a:85:93:47:3…)`，RSSI **−44 dBm**，**channel 157，20 MHz**，单频 AP。

> 相关：00:16:49 mediaserverd 日志 `Disable bluetooth for endpoint 'CarPlay' with device address '44:90:bb:a1:a5:16'` —— iOS 在 AirPlay 通道起来后**主动下发关闭蓝牙**的命令。注意 `44:90:bb:a1:a5:16` **不等于**车机 BT MAC `88:5A:85:13:47:05`（该地址在 P1 日志中共 3 次命中，均为本条上下文）。地址归属**无法从本次数据判定**，仅记录。

### C.3 iAP2 端点建/拆全表（`accessoryd`）

| 序号 | endpoint UUID | connection UUID | 建立 | 销毁 | 寿命 |
|---|---|---|---|---|---|
| 1 | `B26B18AD-C408-4786-B7F9-584787B0C7F3` | `E2D66E69-…-C91A85D3D4AA` | 00:13:20 | 00:13:47 | 27 s |
| 2 | `ACBE8C8C-42A7-4AB2-96D6-C2BE45945588` | `B4814C51-…-BCB01D24287F` | 00:14:07 | 00:15:10 | 63 s |
| 3 | `89BCE317-0925-400F-B71A-58C291835DC8` | `2395C1B2-…-F28B3F90407D` | 00:15:11 | 00:15:37 | 26 s |
| 4 | `9D63D450-1D21-439B-95C9-55E86C213858` | `65A16C09-…-EAEFBDC91714` | 00:15:42 | 00:16:08 | 26 s |
| 5 | `243808B2-A221-43E0-9690-6909DE08F4DD` | `AE4327E7-…-913F6745368D` | 00:16:47 | （未拆除） | — |
| 6 | `F4442C3A-615B-4C8D-B196-FD87D6FAAB0D` | `8859D637-…-346986EAC370` | 00:16:49 | P2 00:23:10 | — |

P2：`F4442C3A` 于 **00:23:10** 销毁；新建 `24270BFE-7D2E-4E0C-BF46-C74AEB9B4F0D`（00:23:23）、`AACC2546-97F7-4AF2-842E-4C8AF669FEEA`（00:23:25），两者均 `connection published`。

> 附带旁证：`iAP2 Link Timer` 被反复重设，取值分布 —— `1500ms` 32 次、`73ms` 32 次、`1422–1498ms` 共 17 次、`1000ms` 5 次、`20/43/45ms` 各 1 次。这是 iAP2 链路的心跳/看门狗节拍，可作为「链路存活判定」的旁证。

### C.4 错误 / 警告

`Error` 级日志在采集窗口内有大量命中，但**绝大多数与 CarPlay 无关**（`searchpartyd` / `identityservicesd` / `contextstored` / `CMFSyncAgent` 等系统噪声）。与蓝牙/WiFi/CarPlay 相关、且值得记录的只有：

| 时刻 | 内容 | 判定 |
|---|---|---|
| 00:12:46 | `wifid: AUTO-JOIN: Auto-join aborted (requeued=0, cancelled=0, error=(37 'Already associated, and network transition not permitted'))` | 见 §C.6 |
| 00:12:46 | `wifid: -[WiFiTimeDeferredNetworksAJManager setScanResultsWithAutoJoinSessionCompletion:complete:]: no scan results` | 扫描无结果的噪声 |
| 00:16:49 | `backboardd: ERROR: no samples (rear: …)` | 屏幕亮度采样，与 CarPlay 无关 |

> **没有**发现任何「iAP2 认证失败 / pair-verify 失败 / 通道被拒」级别的错误 —— 也就是说：**4 次断连不是被明确拒绝，日志里没有给出原因**。这一点必须如实记录，不能编原因。

### C.5 `requestUI` / `changeModes` / `NowPlaying` / `0x5000` / `0x5001`

| 关键字 | P1 | P2 | 结论 |
|---|---|---|---|
| `requestUI` | **0** | **0** | **没有痕迹** |
| `changeModes` | **0** | **0** | **没有痕迹** |
| `NowPlaying` | 5638 | — | 有，但主要是 `mediaremoted`/`accessoryd` 的普通媒体事件 |
| `0x5000` | **1** | **1** | 车机→手机，**仅 1 次** |
| `0x5001` | **273** | **20** | 手机→车机 |

**0x5000 / 0x5001 明细（P1）：**

```
L129102 00:14:09 accessoryd[534]: Incoming Message ID: 0x5000 (186 bytes) for endpoint ACBE8C8C-…
L129121 00:14:09 accessoryd[534]: Outgoing Message ID: 0x5001 (214 bytes) for endpoint ACBE8C8C-…
L129339 00:14:09 accessoryd[534]: Outgoing Message ID: 0x5001 (105 bytes) for endpoint ACBE8C8C-…
L129383 00:14:09 accessoryd[534]: Outgoing Message ID: 0x5001 ( 15 bytes) for endpoint ACBE8C8C-…
```

- `0x5000` 方向：**Incoming 1 次 / Outgoing 0 次** → 车机发起。
- `0x5001` 方向：**Outgoing 273 次 / Incoming 0 次** → 手机发起。
- 按分钟分布（`0x5000`+`0x5001` 合计 274 条）：`00:14` **134** 条、`00:16` 8 条、`00:17` 7 条、`00:18` **125** 条；最后一条 00:18:54（L1216608，23 B）。
- **端点归属有切换**：134 条 → `ACBE8C8C`（第 1、2 次 iAP2 连接期间），140 条 → `F4442C3A`（00:16:49 之后）。
- ⭐ **关键**：`0x5000` 只出现在 `00:14:09`（第 2 个端点 `ACBE8C8C` 上）。**00:16:49 之后的新端点 `F4442C3A` 上，车机再没发过 `0x5000`，而 iPhone 照样发了 140 条 `0x5001`。**
- P2：`0x5000` 1 次、`0x5001` 20 次（`00:23` 3 条、`00:24` 18 条）。

### C.6 WiFi 关联 / DHCP

- **关联时刻：抓包开始前就已完成，本次数据无法给出。** 依据：T0 后第 2 秒（00:12:46）`wifid` 已在打 `__WiFiLQAMgrLogStats(DIRECT-BMW 71280:Stationary): Rssi: -44 … Channel: 157`；`snap/01-interfaces.txt` 也已是 `en0 [… Associated]`。
- **关联由蓝牙触发，且被系统判定为"已关联"而中止**：

```
00:12:46 wifid: WiFiDeviceManagerStartAutoJoin: AUTO-JOIN: Triggered (trigger=24)
00:12:46 wifid: [corewifi] AUTO-JOIN: Auto-join triggered (… params=trigger=bluetooth, mode=default, target=((null)), autoHotspot=0)
00:12:46 wifid: [corewifi] AUTO-JOIN: Auto-join aborted (… error=(37 'Already associated, and network transition not permitted'))
```
- **DHCP**：`grep -ci DHCP` = 128，但均为系统其他模块提及；**没有**找到本次关联的 DHCP 租约时刻（关联早于 T0，DHCP 自然也在 T0 之前）。
- pcap 侧同样无法补：**抓包过滤器只有 `tcp`，0 个 UDP/ARP/ICMP 包**（§附录 D.2），DHCP 根本不在包里。

> ⭐ 这条对 Android 端很重要（**只陈述事实**）：真 iPhone 加入车机热点的触发源是 **`trigger=bluetooth`**，且该网络是**已知网络**（auto-join 直接命中）。用户"首次配对必须手点热点"的现象，**在本次数据里没有对应记录**（因为抓包开始时已经关联好了）。

### C.7 "车机点一次 CarPlay 就进"的确切时刻

**该时刻在两次会话里都能定位，且 oslog 与 pcap 对得上：**

**P1（首次配对）**
```
00:16:48 mediaserverd[356]: -FigRoutingManager- FigRoutingManagerLogEndpointID:
         Found an endpoint with name= CarPlay车载 to autoconnect for CarPlay, going to autoconnect to it
00:16:48 mediaserverd[356]: -FigRoutingManager_iOS- FigRoutingManagerPickEndpointForContext:
         User picked CarPlay route; reset the flag doNotMakeStarkAudioPortRoutable
00:16:48 kernel_task[0]: /System/Library/CoreServices/CarPlay.app/CarPlay[3609] ==> (apply-protobox)
00:16:48 CarPlay[3609]: Initializing connection
00:16:48 mediaserverd[356]: [0xBFA4] Control pair-verify CU-AP
00:16:48 mediaserverd[356]: Pair-verify client M1 -- start request   ← 对应 pcap #92
00:16:48 mediaserverd[356]: Pair-verify client M2 -- start response  ← 对应 pcap #94
00:16:48 mediaserverd[356]: Pair-verify client M3 -- finish request  ← 对应 pcap #96
00:16:48 mediaserverd[356]: Pair-verify client M4 -- finish response ← 对应 pcap #97
00:16:48 mediaserverd[356]: [0xBFA4] Control pair-verify CU-AP success
00:16:49 mediaserverd[356]: -MXSessionManager- nowPlayingAppShouldPlayOnCarPlayConnect:
         Going to resume NowPlaying app - com.soda.music
```

**P2（重连）** —— 同构，时刻为 00:23:24：
```
00:23:23 carkitd[472]: Expected BluetoothConnectionStarted Event, observed <private>. Creating new SessionUUID
00:23:23 carkitd[472]: starting CarPlay Control advertising for Wi-Fi UUID: <private>
00:23:23 carkitd[472]: Handling CarPlay attach
00:23:24 mediaserverd[356]: Found an endpoint with name= CarPlay车载 to autoconnect for CarPlay …
00:23:24 mediaserverd[356]: FigRoutingManagerPickEndpointForContext: User picked CarPlay route …
00:23:24 mediaserverd[356]: Control pair-verify CU-AP  → M1/M2/M3/M4 → CU-AP success
00:23:24 mediaserverd[356]: FigRoutingManager_iOSActivateEndpoint: Activating endpoint 'CarPlay车载' with features 3
```

**要点（避免误读）**：
- `User picked CarPlay route` **不是用户手点**：它在 P1/P2 的**会话建立瞬间都出现**，是 `FigRoutingManager` 内部的选路日志。**不能**把它当作"用户操作"的证据。
- 真正与"用户手动切到 CarPlay"相关的可观测证据是 **6030 视频通道开始上量**（P2 的 00:22:48 那 3.3 秒 / 6.5 Mbit/s，伴随 `CarPlay[3609]` 的 app 启动与 `stark mode … Screen: iOS Take UserInitiated`）。
- **P1 的"手动切一次"发生在 P1 抓包结束（00:19:58）之后**，因此**没有**被 P1 的 oslog 或 pcap 记录到。这是本次数据的明确覆盖死角。

---

## 5. D 节：与 Android 端的对照（只列差异事实）

Android 侧取值均来自当前工作树，行号为复核用。

| # | 项目 | 真 iPhone（本次实测） | Android 现状 | 差异 |
|---|---|---|---|---|
| 1 | RTSP `User-Agent` | `AirPlay/635.87.3`（P1 #92/#96、P2 #936/#940） | `AirPlay/566.25.21`（`airplay/AirPlayClient.kt:2742` 定义，`:1785` `:4262` 使用） | **不同**：635.87.3 vs 566.25.21 |
| 2 | pair-verify 首包 `CSeq` | **0**（然后 1） | 从 **1** 起（`AirPlayClient.kt:109` `cSeq = 0`，`:4254` `++cSeq`） | **不同**：iPhone 从 0 起 |
| 3 | pair-verify `Content-Type` | `application/octet-stream` | `application/pairing+tlv8`（`AirPlayClient.kt:4229`） | **不同** |
| 4 | `X-Apple-HKP: 2` | 有 | 有（`:4217`） | 一致 |
| 5 | `X-Apple-PD: 1` | 有 | 有（`:4218`，仅 pair-verify） | 一致 |
| 6 | `X-Apple-AbsoluteTime` | 有 = `817143408`（= 包时刻的 Apple epoch 秒） | **未发送**（全仓 `grep X-Apple-AbsoluteTime` 仅命中 BinaryPlist 注释） | **差异：Android 缺此头** |
| 7 | `X-Apple-Client-Name: 12` | 有（设备名） | **未发送**（`grep X-Apple-Client-Name` 0 命中） | **差异：Android 缺此头** |
| 8 | 头部顺序 | `X-Apple-*` → `Content-Length` → `Content-Type` → `CSeq` → `User-Agent` | `CSeq` → `User-Agent` → `Content-Type` → extras → `Content-Length`（`:4260-4268`） | **顺序不同** |
| 9 | `GET /info` 探测 | 本次**明文未出现**（是否发送无法判定） | 主动发 `GET /info`（`AirPlayClient.kt:1780` 起，`CSeq: 1`） | **行为不同**（iPhone 未观测到） |
| 10 | 车机反连端口 | **50560**，且 P1/P2 两次**同一端口** | `CarPlayCtrlAdvertiser` 复用持久化 `lastPort`，失败退回随机（`net/CarPlayCtrlAdvertiser.kt:75-96`） | 机制不同（iPhone 表现为端口固定） |
| 11 | ctrl-int 应答内容 | `HTTP/1.1 200 OK` + `Date: …` + `Content-Length: 0`（P1 #83） | `HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n`（`:389-392`） | **差异：Android 多 `Connection: close`、无 `Date`** |
| 12 | ctrl-int 请求里车机自报 | `AirPlay-Receiver-Device-ID: 149922361067269`、`Host: 12.local.`、`User-Agent: AirPlay/365.10.3` | 解析 `AirPlay-Receiver-Device-ID`（`:370-377`） | 解析一致；`Host`/车机 UA 未使用 |
| 13 | mDNS `srcvers` | **本次数据无法判定**（pcap 无 UDP，见附录 D.2） | 广播 `srcvers=950.7.1`（`CarPlayCtrlAdvertiser.kt:41`） | **不可比**：iPhone 值不可得 |
| 14 | 服务实例名 | **本次数据无法判定**（同上） | `name=sender`（`:49`） | **不可比** |
| 15 | iAP2 NowPlaying | 收 `0x5000` **1 次**，发 `0x5001` **273 次**；`0x5000` 缺失时仍持续发 | 依赖 CarLife/MediaSession 桥接补推 `0x5001`（`SessionController.kt:739-791`） | 触发模型不同（iPhone 不需要 0x5000 才发） |
| 16 | 自动恢复播放 | 连接时 `nowPlayingAppShouldPlayOnCarPlayConnect: Going to resume NowPlaying app - com.soda.music`（00:16:49） | 未见对应实现（本表未做全仓确认） | **iPhone 有该行为** |
| 17 | 关闭蓝牙 | 00:16:49 `Disable bluetooth for endpoint 'CarPlay' …` | 未见对应实现 | **iPhone 有该动作** |
| 18 | 视频通道对端端口 | 车机 **6030**（单向，手机→车机） | 未见 6030 常量（全仓 grep `6030` 仅命中注释/日志文本） | **端口未在 Android 侧固化** |

> 说明：本表**只列差异事实**，不含"影响分析"。第 13/14 项之所以写"不可比"，是因为本次 pcap 的过滤器 `tcp and less 1400` 把 mDNS（UDP/5353）整类排除了 —— 这是数据边界，不是 iPhone 没广播。

---

## 6. 附录

### D.1 可复现命令

**A. 环境**
```bash
/usr/sbin/tcpdump --version     # tcpdump 4.99.1 / libpcap 1.10.1（注意在 /usr/sbin，不在 /usr/bin）
python3 -V                      # Python 3.11.1（系统自带即可；本次未用 scapy/dpkt，两者均未安装）
```

**B. 包级时间范围与总量**
```bash
/usr/sbin/tcpdump -r wifi.pcap -nn -tttt 2>/dev/null | head -3
/usr/sbin/tcpdump -r wifi.pcap -nn -tttt 2>/dev/null | tail -3
/usr/sbin/tcpdump -r wifi.pcap -nn -tttt 2>/dev/null | wc -l     # P1=3502 P2=2956，与 meta.txt 一致
```

**C. 自带 pcap 解析器**（无第三方依赖；已随文档保存为 `docs/data/iphone-baseline-20261124/pcap.py`）
```bash
python3 docs/data/iphone-baseline-20261124/pcap.py /tmp/cap/p1/20261124-001244/wifi.pcap
# 逐包解码 Ethernet/IPv4/IPv6/TCP，输出 ts / 五元组 / flags / seq / ack / payload
```

**D. 提取明文 RTSP（本文件 §B.1 的来源）**
```bash
python3 - <<'PY'
import sys; sys.path.insert(0,'docs/data/iphone-baseline-20261124')
from pcap import parse
P,recs=parse('/tmp/cap/p1/20261124-001244/wifi.pcap')
import re
MARK=re.compile(rb'(RTSP/1\.0|POST |GET |SETUP|RECORD|SET_PARAMETER|OPTIONS|TEARDOWN|FLUSH|X-Apple|AirPlay|AirTunes|Content-|CSeq|Server:)')
for r in recs:
    if r['kind']!='tcp' or not r['payload']: continue
    if MARK.search(r['payload']):
        print('#%d t=%.6f %s:%d->%s:%d len=%d'%(r['i'],r['t'],r['src'],r['sp'],r['dst'],r['dp'],r['plen']))
PY
```

**E. 字节量核算（用 ACK 推进量，不受过滤器影响）**
> 原理：接收方向的 ACK 数字只增不减，`ack` 增量 = 对端真实送达字节数。ACK 包 wire 只有 66 B，**永远不会被 `less 1400` 滤掉**，因此这是唯一可信的码率口径。
```bash
# 见 docs/data/iphone-baseline-20261124/pcap.py 中的 parse()，按 (src,sport,dst,dport) 分组后
# 对每个方向取 ACK 差分并累加；结果见 §A.2 / §A.4 表格
```

**F. 序列号连续性（跨两个 pcap）**
```bash
python3 - <<'PY'
import sys; sys.path.insert(0,'docs/data/iphone-baseline-20261124')
from pcap import parse
for port,cport in [(6030,52146),(7000,50562),(5001,50563)]:
    _,r1=parse('/tmp/cap/p1/20261124-001244/wifi.pcap')
    _,r2=parse('/tmp/cap/p2/20261124-002247/wifi.pcap')
    a=[r for r in r1 if r['kind']=='tcp' and r['dp']==port and r['sp']==cport and r['plen']>0]
    b=[r for r in r2 if r['kind']=='tcp' and r['dp']==port and r['sp']==cport and r['plen']>0]
    print(port, a[-1]['seq'], a[-1]['plen'], b[0]['seq'], (b[0]['seq']-a[-1]['seq'])%(2**32))
PY
```

**G. oslog 关键提取（全部用 grep，未通读）**
```bash
cd /tmp/cap/p1/20261124-001244
grep -n  'CarPlay active'                         carplay.log          # 1 行
grep -nE 'Update devices: connected, classic'     carplay.log | awk '{print $2,$(NF-1)}' | uniq   # BT 翻转
grep -nE 'accessoryd.*(Creating|Destroyed) endpoint' carplay.log       # iAP2 端点 6 次
grep -n  'Message ID: 0x5000'                     carplay.log          # 1
grep -n  'Message ID: 0x5001'                     carplay.log | wc -l  # 273
grep -nE 'User picked|autoconnect for CarPlay'    carplay.log          # 选路时刻
grep -n  'AUTO-JOIN'                              carplay.log | head   # WiFi 触发源 = bluetooth
grep -in 'pair.verify'                            carplay.log          # mediaserverd M1–M4
grep -c  'requestUI'   carplay.log ; grep -c 'changeModes' carplay.log # 均为 0
```

### D.2 数据完整性说明（必读）

**1) 抓包过滤器丢弃了大包 —— 这是本次数据最大的坑**

`meta.txt` 记录：`FILTER tcp and less 1400`。即**所有 wire length ≥ 1400 B 的包都没被录**。

证据（两条独立互证）：
- 实抓到的最大 wire length：P1 = **1360 B**，P2 = **1396 B**，都刚好卡在 1400 以下。
- 用 TCP 序列号空间反推的缺口，**几乎全是 1448 的整数倍**：P1 7000 缺口 `1448`（=1×1448）、`13032`（=9×1448）；P1 6030 缺口 `2896`（2×）、`4344`（3×）、`7240`（5×）。

**被丢掉的量（序列号空间 vs 实抓）：**

| 会话 / 通道 | 实抓字节 | 序列号空间真实字节 | 丢失比例 |
|---|---|---|---|
| P1 7000 C→S | 518,798 | 519,632 | 0.3% |
| P1 7000 S→C | 93,572 | 106,417 | **12.2%** |
| P1 6030（50692）C→S | 634,069 | 788,924 | **19.6%** |
| P1 6030（52146）C→S | 13,726 | 19,486 | **29.6%** |
| P2 6030（52146）C→S | 69,350 | 2,624,797 | **97.4%** ⚠️ |
| P2 6030（54123）C→S | 108,044 | 1,649,964 | **93.5%** ⚠️ |
| P1/P2 5001（双向） | — | — | 0%（小包，无丢失；仅 P1 S→C 有 299 B 重传重叠） |
| P1/P2 50560 | 203 | 203 | 0% |

> ⚠️ 若直接用 `tcpdump | wc` 数包估码率，P2 视频码率会被**低估约 15 倍**。本文件所有码率一律用 **ACK 推进量**（§D.1-E），已避开此坑。

**2) 抓包里完全没有的协议层**

| 缺失内容 | 原因 | 影响 |
|---|---|---|
| UDP / mDNS（5353）/ Bonjour | 过滤器只有 `tcp` | **iPhone 的 `srcvers`、服务实例名、TXT 记录全部不可得** → D 节第 13/14 项无法比较 |
| ARP / DHCP / ICMP / DNS | 同上（全 pcap **0 个非 TCP 记录**） | 无法给 DHCP 时刻、无法给 IP 分配时刻 |
| 802.11 管理帧（关联/认证/EAPOL） | 抓的是 `en0` 网络层，非 monitor 模式 | 无法给「iPhone 关联热点」的射频时刻 |
| `/pair-setup` 全流程 | 发生在两个抓包之前 | 首次配对的配对阶段**不在本数据内** |
| 蓝牙 HCI / iAP2 报文 | `summary.txt`：`bt SKIP reason=BT_CMD 未配置` | **0x5000/0x5001 只能看到「消息 ID + 字节数」，看不到 payload 内容** |

**3) 加密与不可解析清单**

| 对象 | 状态 | 可给的信息上限 |
|---|---|---|
| 7000 上 `#99` 起全部报文（P1 1632 个 / P2 937 个） | **加密** | 时刻、方向、长度；**方法名/头/参数一律无法解析** |
| 5001 全部 payload（P1 120 个 / P2 123 个） | **加密** | 时刻、方向、长度 |
| 6030 全部 payload（P1 743 个 / P2 317 个） | **加密** | 时刻、方向、长度；**无法证明"首帧"语义** |
| IPv6 link-local 流（76 包） | **加密** | 仅时刻/长度；**端点身份无法判定** |
| oslog 中 `<private>` 字段 | 系统脱敏（约 1/4 行） | 设备名、UUID、URL、显示器尺寸等被遮 |
| 蓝牙 iAP2 payload | 未采集 | 只有消息 ID 与长度 |

**4) 已知的日志噪声 / 需注意的陷阱**

- `Stark` 关键字：`grep -i Stark` 大量命中 **`Starkey`（助听器品牌）**，与 CarPlay 的 `Stark` 场景**无关**。真正的 CarPlay 场景证据来自 `CarPlay[3609]` / `CarPlayTemplateUIHost[3613]` / `-stark mode-`。
- `RECORD` / `SETUP` / `display` 等关键字在 oslog 里命中很多（如 `RECORD` 11,968 次），但**绝大多数是无关上下文**（`display` 9485 次基本是屏幕/显示器日志）。**不要**把这些计数当作 RTSP 方法被观测到的证据 —— RTSP 方法名只在 pcap 明文段里认过，结论见 §B.3。
- oslog 时间只到秒，同秒内的事件**无法排序**；凡需要亚秒顺序的，本文件一律以 pcap 为准。
- P1 的 oslog 行数：`wc -l` = **1,257,347**（与用户提供一致），而 `meta.txt` 写 `ANSI_STRIPPED carplay.log lines=1090690` —— 两者口径不同（疑为不同处理阶段计数），引用时以 `wc -l` 为准。

### D.3 交付物清单

| 文件 | 内容 |
|---|---|
| `docs/iphone-baseline-20261124.md` | 本文件 |
| `docs/data/iphone-baseline-20261124/P1_ct7000_bursts.csv` | P1 控制通道 676 个请求/应答 burst 全量（时刻/方向/包数/字节/首包长） |
| `docs/data/iphone-baseline-20261124/P2_ct7000_bursts.csv` | P2 控制通道 268 个 burst 全量 |
| `docs/data/iphone-baseline-20261124/P1_ev5001_bursts.csv` | P1 事件通道 118 个 burst 全量 |
| `docs/data/iphone-baseline-20261124/P2_ev5001_bursts.csv` | P2 事件通道 126 个 burst 全量 |
| `docs/data/iphone-baseline-20261124/pcap.py` | 无依赖 pcap 解析器（复现 §D.1 全部数据） |

> 本文件仅新增文档与数据，**未修改任何源码**。
