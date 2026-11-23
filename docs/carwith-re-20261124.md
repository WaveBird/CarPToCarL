# CarWith (com.miui.carlink) 逆向报告 — 「CarPlay 设备」判定条件
日期：2026-11-24 · 分析对象：13 Pro (nuwa) /product/app/CarWith/CarWith.apk（CarWith 4.0.1）
产物：/tmp/carwith-re/（jadx-out、apktool-out），设备未做任何修改。

## 1. 结论摘要
1. **CarWith 手机端 App 里没有任何「判定 CarPlay 设备」的逻辑**。全包（8 个 dex + 全部 native .so）搜 `carplay/CarPlay/IAP2/iPhone/apple/spoof/masquerade/2d8d2466/0000000d/EIR/writeEir`，唯一命中是 XiaoAI 车机 API 的 DTO 字段（`AutoController$VehicleSwitchStatus.car_play`、`VehicleConnectDeviceInfo.carplay_device_infos`、`AutoPropertyName.CAR_PLAY(5)`），且**在整个 APK 中没有任何调用方**——这些是车机下发状态的透传结构。判定发生在车机固件（HU）侧，CarWith 只是消费结果。
2. CarWith 手机侧的互联栈是亿联（EasyConn/CarLife+）SDK：BT SDP 走 RFcomm UUID `9f03b326-5d75-46f1-9a39-b71f144d1d97`，建网响应由车机 JSON 指定（mode 1/2=join 车机热点 `WifiNetworkSpecifier`，mode 8=Wi-Fi P2P），再经 mDNS `_EasyConn._tcp.local.`（TXT: packagename/ec_name/huid/channel/flavor）找到数据端口并做 flavor 白名单校验。**没有 CarPlay 专用的 SDP/EIR 广播路径。**
3. 推论：车机把一台已配对 BT 设备显示为「CarPlay 入口」的依据是其自身固件对**手机侧 SDP/EIR 中 CarPlay 服务 UUID（前缀 2d8d2466…，SPP-like，uuid16 0x000D 段）** 的扫描，这与 CarWith App 无关。要在 Android 端伪装，方向是让系统蓝牙栈暴露 CarPlay SDP/EIR 记录（root 下 bluedroid/bt_stack 层注入），CarWith 侧无事可做。

## 2. CarWith 判定「CarPlay 设备」的确切条件
伪代码（基于反编译，CarWith 手机端视角）：

```java
// 不存在判定代码。等价于：
carPlayVisible = vehicleSwitchStatus.car_play   // 由车机经 XiaoAI AutoController API 下发
// AutoController.java:4539/4590/4705 (classes5 dex)
```

车机侧（本 APK 不可见）的判定，结合 IAP2/CarPlay 公开行为推断：
```
pairedDeviceDetected(device) =
    bondState(device) == BONDED
 && sdpRecordOrEirContains(device, CarPlayServiceUUID /* 2d8d2466-xxxx-xxxx-xxxx-xxxxxxxxxxxx, uuid16 ~0x000D */)
 && (a2dpSink || hfpAg) 可用        // 车机可消费其音频
=> UI 显示 CarPlay 入口；IAP2 会话建立后进入 carplay_mode
```
⚠️ 第 2 个条件的确切字段/长度未在本 APK 中验证（车机固件未逆向），属推断。

## 3. 相关常量表（CarWith 手机端，均实证）
| 项 | 值 | 位置 |
|---|---|---|
| EasyConn BT SDP/RFcomm UUID | `9f03b326-5d75-46f1-9a39-b71f144d1d97` | `net.easyconn.carman.sdk_communication.SDP.SdpBluetoothUtil` (classes8) |
| EBT 协议头 | 16 字节 LE：`{cmdType(u32), totalLen(u32), cmdType^totalLen(u32), pad}`；校验 `len ^ cmdType == magic` | `CmdBaseHead.read()/write()` |
| EBT_CONN_TYPE_P2C | 524288 (0x80000) | SDP/P2C/EBT_CONN_TYPE_P2C |
| EBT_P2C_REQUEST_BUILD_NET | 524320 (0x80020)，resp 为 JSON `{status, phoneApFrequency, carNetDeviceInfo{mode,ssid,pwd,auth,mac,name}}` | SDP/P2C/EBT_P2C_REQUEST_BUILD_NET |
| EBT_P2C_NOTIFY_AP_INFO | 524352 (0x80040) | SDP/P2C/EBT_P2C_NOTIFY_AP_INFO |
| 建网 mode | 1/2 = join 车机 AP（WPA2，`WifiNetworkSpecifier`+`bindProcessToNetwork`）；8 = Wi-Fi P2P | `SdpBluetoothUtil.m()/s()` |
| mDNS 服务 | `_EasyConn._tcp.local.`，TXT keys: `packagename, ec_name, huid, channel, flavor`；连接端口即 ServiceInfo.port；`ECP_PXC_MDNS_RESPOND=0x70000010` JSON `{phoneType:"Android", packageName}` | `MDNSClient` |
| flavor 白名单样例 | 16=GWM, 17=ORA, 65585=CFMOTO_RIDESYNC, 1=LIFAN, 0=通用； CarWith 主包走 `Constant.PACKAGE_NAME_*` 匹配，debug 模式全放行 | `ECP_C2P_CLIENT_INFO.checkFlavor/b()` |
| 连接重试 | socket connect/soTimeout 3000ms（mDNS 直连 500ms×3），SDP 层 `f30539d=5` 次重试 | `MDNSClient.tryConnectToServer`, `SdpBluetoothUtil` |
| **mediaDisconnectTimeout / 15000** | **未出现**（Java + 全 .so 均无）——是车机固件侧写死/广播的，非 CarWith App 常量 | — |
| CarPlay UUID 前缀 2d8d2466 / 0000000d | **未出现** | — |

## 4. App 端「通告自己是 CarPlay 设备」需要做的事
CarWith 内**没有可引用的实现**（无 iPhone 伪装/spoof 逻辑）。需要做的是 Android 系统蓝牙层面：
1. 向本机 SDP server 注册 CarPlay 服务 UUID（2d8d2466-…），公开 API 无此能力 → 需 root 直接操作 bluedroid（`bt_stack` 的 SDP 注册，或 vendor lib 注入），或确认目标车机只扫 **EIR 里的 128-bit UUID 列表**（部分车机只看 EIR，不发起 SDP 查询）——两者需上车实测确定。
2. Android `BluetoothAdapter.setName()` 不影响 SDP；EIR 的 UUID 列表由 bluedroid 根据**已注册的 SDP 服务**自动生成，因此注册 SDP 记录是唯一正规途径。
3. 佐证项目资产：CarPToCarL 已走 `Iap2BluetoothClient`（RFcomm 直连），说明与车机的 BT L2CAP/RFcomm 通道已通；缺的只是「让车机在配对列表里认出你」，即第 1/2 点。
4. 上车验证手段：车机配对页观察是否出现 CarPlay 入口；`hcidump`/`btmon` 抓车机发起的 SDP `ServiceSearchAttribute` 请求即可确认车机查询的确切 UUID，免去猜。

## 5. 不确定项
- 车机固件（HU）侧判定逻辑未逆向（车机端代码不在本 APK）：CarPlay UUID 是走 SDP 查询还是仅 EIR 扫描未知；`0x4300/0x4301/0x5702/0x5703` 端口号在 CarWith 中不存在，应属车机固件协议。
- `mediaDisconnectTimeout`/15000 不在 CarWith 内，来源（车机固件编译期常量 or 固件→App 广播字段）未知。
- iPhone EIR 中确切广播内容（完整 128-bit UUID、Cod、名称格式）未实测抓包，建议在下次上车前用 13 Pro `btmon` 对 iPhone 做一次 inq reply 采集以定锚。
- `AutoController$VehicleSwitchStatus` 的下发通道（XiaoAI aivs vs. 车机私有 binder）未追踪到底，但与 CarPlay 判定方向无关。
