package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.carlife.CarLifeAutoPrefs.LaunchTarget
import io.github.shaonialife.carptocar.carlife.CarLifeAutoTriggerPolicy.NodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeAutoTriggerPolicyTest {
    private fun node(
        id: Int,
        text: String,
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
        viewId: String? = null,
    ) = NodeSnapshot(
        id = id,
        text = text,
        contentDescription = null,
        viewId = viewId,
        clickable = clickable,
        enabled = enabled,
        visible = visible,
    )

    @Test
    fun idleOrDisarmedPolicyNeverClicks() {
        val policy = CarLifeAutoTriggerPolicy()
        val nodes = listOf(node(1, "开始连接"))
        assertNull(policy.nextClick(CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH, nodes, 1_000L, sourceActive = false))

        policy.arm(1_000L)
        policy.disarm()
        assertNull(policy.nextClick(CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH, nodes, 2_000L, sourceActive = false))
    }

    @Test
    fun windowExpiresAutomatically() {
        val policy = CarLifeAutoTriggerPolicy(windowMs = 10_000L)
        policy.arm(1_000L)
        assertTrue(policy.isArmed(11_000L))
        assertFalse(policy.isArmed(11_001L))
        assertNull(
            policy.nextClick(
                CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH,
                listOf(node(1, "开始连接")),
                11_001L,
                sourceActive = false,
            ),
        )
    }

    @Test
    fun sourceActiveImmediatelyDisarms() {
        val policy = CarLifeAutoTriggerPolicy()
        policy.arm(1_000L)
        val decision =
            policy.nextClick(
                CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH,
                listOf(node(1, "开始连接")),
                2_000L,
                sourceActive = true,
            )
        assertNull(decision)
        assertFalse(policy.isArmed(2_000L))
    }

    @Test
    fun neverClicksOutsideAllowedPackages() {
        val policy = CarLifeAutoTriggerPolicy()
        policy.arm(1_000L)
        val nodes = listOf(node(1, "开始连接"), node(2, "同意"))
        assertNull(policy.nextClick("com.tencent.mm", nodes, 2_000L, sourceActive = false))
        assertNull(policy.nextClick("com.android.settings", nodes, 2_000L, sourceActive = false))
        assertNull(policy.nextClick(null, nodes, 2_000L, sourceActive = false))
    }

    @Test
    fun walksFullXiaomiCarWithWirelessFlow() {
        val policy = CarLifeAutoTriggerPolicy()
        val pkg = CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH
        policy.arm(1_000L)

        // 第 1 页：CarWith 主页，同时有亿连/有线/百度CarLife+
        val step1 =
            policy.nextClick(
                pkg,
                listOf(
                    node(1, "亿连无线连接"),
                    node(2, "CarLife+ 插件设置"),
                    node(3, "百度CarLife+"),
                ),
                nowMs = 1_200L,
                sourceActive = false,
            )
        assertNotNull(step1)
        assertEquals(3, step1!!.nodeId)

        // 第 2 页：CarLife 连接方式（非可点击标题 + 有线 + 无线）
        val step2 =
            policy.nextClick(
                pkg,
                listOf(
                    node(10, "CarLife连接", clickable = false),
                    node(11, "CarLife有线连接"),
                    node(12, "CarLife无线连接"),
                ),
                nowMs = 2_200L,
                sourceActive = false,
            )
        assertNotNull(step2)
        assertEquals(12, step2!!.nodeId)

        // 第 3 页：无线子方式（严禁选手机热点，应选 WLAN直连）
        val step3 =
            policy.nextClick(
                pkg,
                listOf(
                    node(20, "开启手机热点"),
                    node(21, "WLAN直连"),
                    node(22, "手机连接车辆热点"),
                ),
                nowMs = 3_200L,
                sourceActive = false,
            )
        assertNotNull(step3)
        assertEquals(21, step3!!.nodeId)

        // 第 4 页：确认启动连接（按钮优先级高于列表标题）
        val step4 =
            policy.nextClick(
                pkg,
                listOf(
                    node(30, "WLAN直连"),
                    node(31, "取消"),
                    node(32, "开始连接"),
                ),
                nowMs = 4_200L,
                sourceActive = false,
            )
        assertNotNull(step4)
        assertEquals(32, step4!!.nodeId)
    }

    @Test
    fun allowsCarHotspotPathWhileBlockingPhoneHotspot() {
        val policy = CarLifeAutoTriggerPolicy()
        val pkg = CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH
        policy.arm(1_000L)

        val step1 =
            policy.nextClick(
                pkg,
                listOf(
                    node(1, "打开个人热点"),
                    node(2, "打开手机热点"),
                    node(3, "关闭WLAN"),
                    node(4, "手机连接车辆热点"),
                ),
                nowMs = 1_500L,
                sourceActive = false,
            )
        assertNotNull(step1)
        assertEquals(4, step1!!.nodeId)

        val step2 =
            policy.nextClick(
                pkg,
                listOf(node(5, "蓝牙设置"), node(6, "下一步")),
                nowMs = 2_500L,
                sourceActive = false,
            )
        assertNotNull(step2)
        assertEquals(6, step2!!.nodeId)
    }

    @Test
    fun enforcesCooldownAndClickCap() {
        val policy =
            CarLifeAutoTriggerPolicy(
                labelCooldownMs = 4_000L,
                minStepIntervalMs = 500L,
                maxClicksPerLabel = 2,
                maxTotalClicks = 3,
            )
        val pkg = CarLifeAutoTriggerPolicy.PACKAGE_BAIDU_CARLIFE
        policy.arm(1_000L)

        val first = policy.nextClick(pkg, listOf(node(1, "无线连接")), 1_200L, sourceActive = false)
        assertNotNull(first)
        // 冷却期内不再重复点同一个按钮
        assertNull(policy.nextClick(pkg, listOf(node(1, "无线连接")), 3_000L, sourceActive = false))
        // 超过冷却期允许补点一次
        assertNotNull(policy.nextClick(pkg, listOf(node(1, "无线连接")), 5_500L, sourceActive = false))
        // 达到单标签上限（2 次）后不再点
        assertNull(policy.nextClick(pkg, listOf(node(1, "无线连接")), 10_000L, sourceActive = false))
    }

    @Test
    fun launcherPrefersXiaomiCarWithInAutoModeAndRespectsExplicitChoice() {
        val carWith =
            CarLifeLauncher.DetectedApp(
                target = LaunchTarget.XIAOMI_CARWITH,
                packageName = CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH,
                displayName = "小米 CarWith",
                intent = null,
            )
        val baidu =
            CarLifeLauncher.DetectedApp(
                target = LaunchTarget.BAIDU_CARLIFE,
                packageName = CarLifeAutoTriggerPolicy.PACKAGE_BAIDU_CARLIFE,
                displayName = "百度 CarLife+",
                intent = null,
            )
        val installed = listOf(carWith, baidu)

        assertEquals(carWith, CarLifeLauncher.resolvePreferred(installed, LaunchTarget.AUTO))
        assertEquals(baidu, CarLifeLauncher.resolvePreferred(installed, LaunchTarget.BAIDU_CARLIFE))
        // 选了未安装的组件时自动回退到首选项
        assertEquals(carWith, CarLifeLauncher.resolvePreferred(installed, LaunchTarget.XIAOMI_CARLIFE_COMPONENT))
    }

    @Test
    fun snapshotReflectsLifecycleStatesClearly() {
        val carWith =
            CarLifeLauncher.DetectedApp(
                target = LaunchTarget.XIAOMI_CARWITH,
                packageName = CarLifeAutoTriggerPolicy.PACKAGE_XIAOMI_CARWITH,
                displayName = "小米 CarWith",
                intent = null,
            )
        val notStarted =
            CarLifeLauncher.buildSnapshot(
                hijackEnabled = true,
                videoFlowingToCar = false,
                isAttached = false,
                sourceActive = false,
                isActive = false,
                portOpenDetected = false,
                autoTriggerArmed = false,
                accessibilityEnabled = true,
                installedApps = listOf(carWith),
                preference = LaunchTarget.AUTO,
            )
        assertEquals(CarLifeLauncher.EngineState.NOT_STARTED, notStarted.state)
        assertEquals("未启动", notStarted.badgeText)

        val ready =
            CarLifeLauncher.buildSnapshot(
                hijackEnabled = true,
                videoFlowingToCar = false,
                isAttached = false,
                sourceActive = true,
                isActive = true,
                portOpenDetected = true,
                autoTriggerArmed = false,
                accessibilityEnabled = true,
                installedApps = listOf(carWith),
                preference = LaunchTarget.AUTO,
                streamSize = 1280 to 720,
            )
        assertEquals(CarLifeLauncher.EngineState.READY, ready.state)
        assertEquals("运行中", ready.badgeText)
        assertTrue(ready.shortLine.contains("1280×720"))

        val streaming =
            CarLifeLauncher.buildSnapshot(
                hijackEnabled = true,
                videoFlowingToCar = true,
                isAttached = true,
                sourceActive = true,
                isActive = true,
                portOpenDetected = true,
                autoTriggerArmed = false,
                accessibilityEnabled = true,
                installedApps = listOf(carWith),
                preference = LaunchTarget.AUTO,
                streamSize = 1280 to 720,
            )
        assertEquals(CarLifeLauncher.EngineState.STREAMING_TO_CAR, streaming.state)
        assertEquals("推流中", streaming.badgeText)

        val zeroFrameStalled =
            CarLifeLauncher.buildSnapshot(
                hijackEnabled = true,
                videoFlowingToCar = false,
                isAttached = false,
                sourceActive = false,
                isActive = true,
                portOpenDetected = true,
                autoTriggerArmed = false,
                accessibilityEnabled = true,
                installedApps = listOf(carWith),
                preference = LaunchTarget.AUTO,
                zeroFrameStalledSec = CarLifeHijack.ZERO_FRAME_STALL_SEC,
            )
        assertEquals(CarLifeLauncher.EngineState.ZERO_FRAME_STALLED, zeroFrameStalled.state)
        assertEquals("零帧占口", zeroFrameStalled.badgeText)
        assertTrue(zeroFrameStalled.shortLine.contains("清理占口"))
    }
}
