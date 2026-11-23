package io.github.shaonialife.carptocar.iap2

import android.content.Context

/**
 * 「这台车机走旧代 WiFi 凭据协议（0x5702 → 0x5703）」的**按车机持久记忆**。
 *
 * ## 为什么必须记忆，而不是每轮现判
 *
 * 代际判定的唯一输入是 0x1D01 的两份消息清单（[ReceiverProfile.classify]）。但清单是
 * **会抖动的**：同一台 BMW MGU-55-J 在 2026-11-23 晚间日志里，`车机可收清单` 有 5 轮
 * 是 13 项（含 `0x5702`），有 1 轮是 12 项（**仅仅少了 `0x5702` 这一项**）：
 *
 * ```
 * 19:12:36.310  iap2 0x1d01 车机可收清单(13)=… 0xea01 0x5702     ← LEGACY，成功拿到 0x5703
 * 19:13:11.178  iap2 0x1d01 车机可收清单(12)=… 0xea01            ← 少了 0x5702
 * 19:13:28.955  iap2 0x1d01 车机可收清单(13)=… 0xea01 0x5702     ← LEGACY，又成功
 * ```
 *
 * 12 项那一轮连带的三个后果（19:13:11 → 19:13:19 整轮报废）：
 *  1. `清单完整（28 项）仍无 0x5703/0x5702` → 画像判 `wifiGen=NEW`；
 *  2. NEW 分支**不发 0x5702**（`本轮不发 0x5702`）→ 车机永远不会回 0x5703；
 *  3. NEW 同时开**严格模式**，把 0x5702 裁在发送之前（双保险地再堵一次）。
 *
 * 真 iPhone 不会因为清单一项漏列就放弃请求热点配置。清单只能证明「有」，不能证明
 * 「没有」——[ReceiverProfile] 的注释里本来就写着这条铁律，只是当时没有记忆可用，
 * 只能靠单轮清单硬判。有了这份记忆，判定就能以**这台车机的真实历史**为准：
 * 只要它回过一次 0x5703，此后清单再怎么漏项都不改判。
 *
 * ## 存储
 *
 * 与 [MfiLengthMemory] / [AudioProfileMemory] 同源：按车机身份键（model+manufacturer
 * 归一化）存一个布尔。无 context 时退化为进程内表，便于单测。
 */
object WifiGenMemory {
    private const val FILE = "wifi_gen_memory"
    private const val KEY_LEGACY = "legacy_"

    /** 进程内兜底（单测 / context 缺失）。 */
    private val memory = HashMap<String, Boolean>()

    /** 车机身份 → 存储键（与 [MfiLengthMemory.receiverKey] 同一套归一化规则）。 */
    fun receiverKey(identityText: String): String = MfiLengthMemory.receiverKey(identityText)

    private fun prefs(context: Context?): android.content.SharedPreferences? =
        context?.applicationContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 该车机是否**曾经**回过 0x5703（= 确认走旧代协议）。 */
    fun isKnownLegacy(
        context: Context?,
        identityText: String,
    ): Boolean {
        val key = receiverKey(identityText)
        val p = prefs(context) ?: return synchronized(memory) { memory[key] == true }
        return p.getBoolean(KEY_LEGACY + key, false)
    }

    /**
     * 记录「这台车机回过 0x5703」。
     *
     * 只写一次（幂等）：值不会回退——车机固件升级可能换代，但那时新一轮会重新走到
     * `wifiGen=NEW` 分支并被 `0x5703` 缺席证实；把历史标记保留住比反复清空更安全，
     * 因为「多发一条 0x5702」在真 NEW 代际车机上的代价只是
     * [Iap2SenderSession.LEGACY_WIFI_FIRST_WAIT_NANOS] 的短窗口（随后并行发 0x4300）。
     *
     * @return true = 这次是新写入（调用方可据此打一行「已记忆」日志）
     */
    fun markLegacy(
        context: Context?,
        identityText: String,
    ): Boolean {
        val key = receiverKey(identityText)
        val p = prefs(context) ?: return synchronized(memory) { memory.put(key, true) == null }
        if (p.getBoolean(KEY_LEGACY + key, false)) return false
        p.edit().putBoolean(KEY_LEGACY + key, true).apply()
        return true
    }

    /** 清空某车机的记忆（仅供测试/排障）。 */
    fun forget(
        context: Context?,
        identityText: String,
    ) {
        val key = receiverKey(identityText)
        val p = prefs(context)
        if (p == null) {
            synchronized(memory) { memory.remove(key) }
            return
        }
        p.edit().remove(KEY_LEGACY + key).apply()
    }
}
