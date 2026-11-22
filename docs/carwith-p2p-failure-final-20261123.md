# CarWith 直连「拉起但不出帧」终局定谳：WLAN 直连（P2P）失败（2026-11-23）

> 溯源链：三份实车日志（101447/235301/235939）→ CarWith 官方状态机日志
> （`/sdcard/Android/data/com.miui.carlink/cache/log/*.dump`，用户开日志开关后
> 共 138 个 dump）→ 官方 FAQ + 本机 adb A/B 实验。1.2.101 收口。

## 一、真实机制（CarWith 官方状态机日志定谳）

CarWith 直连入口（`CarlifeAdbActivity`）的连接走 **WirelessSM 无线状态机的
WiFi P2P（WLAN 直连）通道**，与「设置→CarWith→调试」页的「WLAN直连时长」项对应：

**成功轮**（CarlinkSM 4 条记录）：
```
DefaultState → DisconnectedState (what=10)
DisconnectedState → UsbConnectingState (what=11)
UsbConnectingState → WiredCastingState (MSG_CASTCONTROLLER_CAST_SUCCESS) ✅
WiredCastingState → DefaultState (CMD_DISCONNECT_CAST，用户停止)
```

**失败轮**（2 类指纹）：
```
CarlinkSM: DefaultState → <null> (MSG_CASTCONTROLLER_CAST_FAILURE) ❌
WirelessSM: WirelessConnManager.MSG_WIFI_P2P_CONNECT_FAILURE   ← P2P 连接失败
```

## 二、三层归因修正（逐层推翻重立）

| 轮次 | 当时结论 | 实际 |
|---|---|---|
| 1.2.92~97 | 编码器初始化偶发卡死 | 下游表现，非根因 |
| 1.2.98~100 | killBackgroundProcesses 回收进程 | **no-op**：CarlinkService 是前台服务（isForeground=true），kill 后 pid 不变（本机 adb 三次实验）；`am force-stop` 才有效但需系统权限——「已强制回收」日志均为假成功 |
| 1.2.101 | **WiFi P2P 连接失败** | 官方状态机 `MSG_WIFI_P2P_CONNECT_FAILURE`；WiFi 功能关闭时 P2P 必然失败；快速启停时 P2P 未释放完也会失败 |

## 三、官方依据（用户要求查文档，不盲试）

- 小米官方 FAQ 级说明（4.0.20 更新说明）：「一旦手动关掉 Wi-Fi，就算蓝牙配对正常，
  **无线投屏根本无法启动**。不需要连接任何热点，只需保持 Wi-Fi 功能处于打开状态」
  ——检查的是 **WiFi 开关状态**，不是连接状态；有线路径（UsbConnectingState）
  不走此检查，故「有线关 WiFi 正常」的反证成立且不矛盾。
- 小米无公开直连开发文档（隐私说明只列与阿波罗智联/卡比特的 IPC 投屏）；
  协议为逆向（mcarplay 作者：跟 CarLife 差不多）。
- CarWith 官方日志开关：设置→CarWith→1 秒连点 5 次「检查更新」→ 拨号
  `*#*#284#*#*` → 日志在 `/sdcard/Android/data/com.miui.carlink/cache/log`。

## 四、1.2.101 落地的三件事

1. **Preflight WIFI_ENABLED 文案**：补上 CarWith 直连语义——「WLAN 关闭时
   CarWith 直连走 WLAN 直连（P2P）会直接失败；无需连接任何热点，只要打开 WLAN 开关」；
2. **归因日志修正**：Coordinator/Hijack 的「编码器卡死」改为真实归因
   （WiFi 关→P2P 必败；WiFi 开→P2P 未释放完或失败，等 3~5s 再试）；
3. **kill 手段修正**：`restartCarWithProcess` 改为 su 可用时
   `su -c am force-stop`（root 机真正回收，500ms 后调起）；无 root 只重发入口
   （P2P 未释放完场景有效），不再打假「回收」日志。killBackgroundProcesses 调用删除。

## 五、给用户的使用建议

- **WLAN 开关保持打开**（不需要连任何热点）——CarWith 直连的硬前提；
- 失败时**等 3~5 秒再点启动**——P2P 通道释放需要时间；
- 反复失败时看日志归因行：点名 P2P 失败即按上面两条处理，不用再怀疑编码器。
