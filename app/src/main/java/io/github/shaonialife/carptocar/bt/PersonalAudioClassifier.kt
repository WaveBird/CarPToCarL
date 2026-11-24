package io.github.shaonialife.carptocar.bt

/**
 * 「这台设备像不像耳机/音箱」的判定（2026-11-14 抽出）。
 *
 * ## 为什么要单独抽出来
 *
 * 原来的判定只看 `BluetoothDevice.bluetoothClass`，拿不到就一律 `false`（不是耳机）。
 * 一加 Android 16（SDK 36）上这个字段经常是 `null`——尤其是 LE Audio 耳机，
 * 于是 Redmi Buds 5 Pro 被判成「可能是车机」，被反复拨 …caff，失败后还会被
 * `BtProfiles.disconnect()` 断掉 A2DP/HFP 再试一次。用户端的观感就是
 * 「蓝牙一直在断」。
 *
 * 抽成纯函数还有一个好处：姓名关键词表可以直接单测，不必连真蓝牙。
 */
object PersonalAudioClassifier {
    /**
     * Class of Device 里 AUDIO_VIDEO 主类的这几个子类，一律按个人音频设备处理。
     *
     * 车机常见的是 HANDSFREE / CAR_AUDIO / UNCATEGORIZED，都不在这里面。
     */
    private val AUDIO_DEVICE_CLASSES =
        setOf(
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
        )

    /**
     * 名字里出现这些词就当耳机/音箱——**只在拿不到 Class of Device 时**兜底。
     *
     * 刻意不放品牌名（JBL / Bose / Sony…）：车机名字里带音响品牌的情况不少，
     * 误判会把真车机排除掉，代价远大于多拨一次耳机。
     *
     * `wf-` / `wh-`（修复 #15，2026-11-24）：索尼 WF-/WH- 系列的型号前缀。今晚实车
     * WF-SP700N 在 Android 16 上 CoD 缺失、名字又不带任何关键词，被连拨 4 次
     * （L176/195/208/226）；这类「品牌+型号」形态只有前缀这一条稳定线索。
     * 保留不放品牌名的原则：前缀是型号语义，品牌是产品线语义——车机不会被命名为
     * 「wf-xxx」，但可能被命名为「Bose xxx」。
     */
    private val NAME_KEYWORDS =
        listOf(
            "buds",
            "airpods",
            "freebuds",
            "headphone",
            "headset",
            "earphone",
            "earbuds",
            "speaker",
            "soundbar",
            "耳机",
            "耳塞",
            "音箱",
            "音响",
            "降噪",
            "wf-",
            "wh-",
        )

    fun isAudioDeviceClass(deviceClass: Int?): Boolean = deviceClass != null && deviceClass in AUDIO_DEVICE_CLASSES

    /**
     * WiFi Direct 命名排除（1.2.107 修复 D）。
     *
     * `CX_*`（如 `CX_BTFC4BBCD57C61`）是车机 WiFi Direct（P2P）虚拟接口的命名，
     * 不是蓝牙 RFCOMM 目标——它出现在已配对列表里（部分车机把 P2P 口也写进 bond），
     * 但对它拨 `…caff` 只会得到 `read failed`。2026-11-24 实车 194036：19:36–19:38
     * `BT-C: 连接 CX_BTFC4BBCD57C61 失败` 反复出现约 4 次，白白消耗退避轮次。
     */
    const val WIFIDIRECT_NAME_PREFIX = "cx_"

    fun looksLikeWifiDirectInterface(name: String?): Boolean {
        val lower = name?.trim()?.lowercase().orEmpty()
        return lower.startsWith(WIFIDIRECT_NAME_PREFIX)
    }

    fun looksLikePersonalAudioName(name: String?): Boolean {
        val lower = name?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return false
        return NAME_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * @param deviceClass Class of Device 的 deviceClass（`null` = 系统没给，Android 16 上很常见）
     * @param name 设备名（可能是车机自定义的名字，也可能为空）
     */
    fun looksLikePersonalAudio(
        deviceClass: Int?,
        name: String?,
    ): Boolean = isAudioDeviceClass(deviceClass) || looksLikePersonalAudioName(name) || looksLikeWifiDirectInterface(name)
}
