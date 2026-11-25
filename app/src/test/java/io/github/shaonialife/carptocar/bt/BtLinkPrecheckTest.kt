package io.github.shaonialife.carptocar.bt

import io.github.shaonialife.carptocar.transport.DuplexStream
import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0x5703 到手时的蓝牙链路预检。
 *
 * 1.2.109 修复 1 的原始意图保留：链路**真终结**（releaseControl 已发生，车机 22s 拆链）
 * 时跳过 join，不白耗 25s WiFi 预算（实车 `CarPToCarL-log-20261124-194036.txt` 19:32-19:35）。
 *
 * 1.2.112 修复 1.2.109 引入的严重回归：旧判据 `channel.isAlive && iap2Ready` 会在
 * 「车机正常交凭据」的瞬间误杀——0x5703 到达时发送端 session.run() 尚未返回，
 * iap2Ready 要等 run() 返回后才置位，sessionActive 也要等 onEndpoint。实车日志
 * 2026-11-25 18:46:42 案例与 52 次「蓝牙链路已断——跳过本轮 join」判死时 LINK
 * tx/rx 完全正常，全是这种正常路径。新判据增加独立信号
 * [BtSessionGate.controlChannelEstablished]：claimControl 起即活（含 bring-up
 * 握手期），releaseControl（链路真终结）才判死。
 */
class BtLinkPrecheckTest {
    @After
    fun tearDown() {
        BtSessionGate.sessionActive = false
        BtSessionGate.inboundActive = false
        BtSessionGate.iap2Ready = false
        BtSessionGate.controlChannelEstablished = false
    }

    /** 对端已断 RFCOMM 且链路已终结（onLinkClosed → releaseControl）的死通道。 */
    private fun deadChannel(): Iap2LinkChannel {
        val channel =
            Iap2LinkChannel(
                object : DuplexStream {
                    override fun read(buffer: ByteArray): Int = -1

                    override fun write(bytes: ByteArray) = Unit

                    override fun close() = Unit
                },
                name = "iap2-link",
                log = {},
            )
        BtSessionGate.claimControl(channel, inbound = false)
        // 模拟对端关闭 RFCOMM（非本端主动 close：closedLocally 仍为 false）
        channel.recvControl(10)
        // EOF 终结由 reader 线程异步完成：等它真正落地，消除断言前的时序竞态
        var waited = 0L
        while (channel.isAlive && waited < 2000) {
            Thread.sleep(10)
            waited += 10
        }
        // 车机拆链 → onLinkClosed → releaseControl：这才是「链路真终结」
        BtSessionGate.releaseControl(channel)
        return channel
    }

    /**
     * 「链路活着」的通道：read 永久阻塞（真 RFCOMM 无数据时的常态）。
     *
     * 旧实现用 `read = -1` 造活通道——但 reader 线程是异步终结通道的，全量套件
     * 跑起来时序一变，断言前 `isAlive` 就翻成 false（本次修复过程中在干净 1.2.109
     * 工作树上也复现过一次）。阻塞 read 让通道稳定处于活态，消除这个非受测竞态。
     */
    private fun aliveChannel(): Iap2LinkChannel =
        Iap2LinkChannel(
            object : DuplexStream {
                override fun read(buffer: ByteArray): Int {
                    // 阻塞模拟「链路在、无数据」：reader 线程不会触发 EOF 终结。
                    try {
                        Thread.sleep(60_000)
                    } catch (_: InterruptedException) {
                    }
                    return -1
                }

                override fun write(bytes: ByteArray) = Unit

                override fun close() = Unit
            },
            name = "iap2-link",
            log = {},
        )

    /** 链路真终结（releaseControl 已发生）→ controlLinkAlive 判死，跳过 join。 */
    @Test
    fun `链路死时判死跳过 join`() {
        val channel = deadChannel()
        try {
            assertFalse(channel.isAlive)
            assertFalse("链路真终结必须判死", Iap2BluetoothServer.controlLinkAlive(channel))
        } finally {
            channel.close()
        }
    }

    /**
     * 回归修复核心案例：claimControl 后链路即活（含 bring-up 握手期）。
     * 车机正交凭据（0x5703 已到、run() 未返回）：iap2Ready=false、sessionActive=false，
     * 旧判据在这里误杀（2026-11-25 18:46:42 实车 52 次判死），新判据必须放行。
     */
    @Test
    fun `claimControl 后握手期判活即使 iap2Ready 与 sessionActive 均为 false`() {
        val channel = aliveChannel()
        try {
            BtSessionGate.claimControl(channel, inbound = false)
            // 不 markControlReady：模拟 run() 还在执行、iap2Ready 尚未置位
            assertFalse(BtSessionGate.iap2Ready)
            assertFalse(BtSessionGate.sessionActive)
            assertTrue(channel.isAlive)
            assertTrue(
                "车机正常交凭据的瞬间必须放行 join（2026-11-25 实车 52 次误杀回归）",
                Iap2BluetoothServer.controlLinkAlive(channel),
            )
        } finally {
            channel.close()
            BtSessionGate.releaseControl(channel)
        }
    }

    /** releaseControl（链路真终结）后 → 判死，恢复「死链不陪跑 join」的原始意图。 */
    @Test
    fun `releaseControl 后链路终结判死`() {
        val channel = aliveChannel()
        try {
            BtSessionGate.claimControl(channel, inbound = false)
            assertTrue(Iap2BluetoothServer.controlLinkAlive(channel))
            BtSessionGate.releaseControl(channel)
            assertFalse("链路终结后必须判死", Iap2BluetoothServer.controlLinkAlive(channel))
        } finally {
            channel.close()
        }
    }

    /** sessionActive=true（iAP2 已迁 WiFi 隧道）→ 无条件放行，不算链路死。 */
    @Test
    fun `sessionActive 为 true 时无条件放行`() {
        val channel = deadChannel()
        try {
            assertFalse(channel.isAlive)
            // RFCOMM 实际已断，但无线 CarPlay 已在 WiFi 上跑起来（正常交接）
            BtSessionGate.sessionActive = true
            assertTrue("隧道迁移必须放行 join", Iap2BluetoothServer.controlLinkAlive(channel))
        } finally {
            channel.close()
            BtSessionGate.sessionActive = false
        }
    }

    /** 没有活跃通道、控制通道也未建立（车机尚未回连）→ 链路死，跳过 join。 */
    @Test
    fun `无活跃通道时判死`() {
        BtSessionGate.sessionActive = false
        assertFalse(BtSessionGate.controlChannelEstablished)
        assertFalse(Iap2BluetoothServer.controlLinkAlive(null))
    }

    // ---- 审计 E3（docs/flow-audit-20261125.md）：0x4301 路径补链路预检 ----
    // 1.2.109 的预检只守 0x5703 入口；0x4301 的 wifi-join-on-endpoint 已改为与 0x5703
    // 共用同一入口判据（`bluetoothLinkDeadForJoin` == `controlLinkAlive() == false`）。
    // 下面两条覆盖同一判据在 0x4301 场景（晚到端点/车机已退出 CarPlay）下的语义。

    /** 0x4301 晚到 + 链路真终结（releaseControl 已发生）→ 预检判死，跳过 join。 */
    @Test
    fun `0x4301 场景链路死时预检判死跳过 join`() {
        val channel = deadChannel()
        try {
            // wifi-join-on-endpoint 入口的同一句判据：controlLinkAlive == false → skip
            assertFalse("链路死必须跳过 join", Iap2BluetoothServer.controlLinkAlive(channel))
        } finally {
            channel.close()
        }
    }

    /** 0x4301 场景链路活（控制通道已建立、握手期）→ 预检放行，照常 join。 */
    @Test
    fun `0x4301 场景链路活时预检放行照常 join`() {
        val channel = aliveChannel()
        try {
            BtSessionGate.claimControl(channel, inbound = false)
            assertTrue("链路活必须放行 join", Iap2BluetoothServer.controlLinkAlive(channel))
        } finally {
            channel.close()
            BtSessionGate.releaseControl(channel)
        }
    }
}
