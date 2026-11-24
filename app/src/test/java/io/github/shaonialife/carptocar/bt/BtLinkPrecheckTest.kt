package io.github.shaonialife.carptocar.bt

import io.github.shaonialife.carptocar.transport.DuplexStream
import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0x5703 到手时的蓝牙链路预检（1.2.109 修复 1）：实车
 * `CarPToCarL-log-20261124-194036.txt` 19:32-19:35 多个 22-26s 短命会话——链路已死
 * 时再 join 只是白耗 25s WiFi 总预算，跳过后让车机尽快重连、重发凭据。
 * iAP2 已迁到 WiFi 隧道（sessionActive=true，`SESSION 结束——…继续推流`）不算链路死。
 */
class BtLinkPrecheckTest {
    @After
    fun tearDown() {
        BtSessionGate.sessionActive = false
        BtSessionGate.inboundActive = false
        BtSessionGate.iap2Ready = false
    }

    /** 对端已断 RFCOMM 的死通道（read -1 → 引擎终结 → isAlive=false）。 */
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
        BtSessionGate.markControlReady(channel)
        // 模拟对端关闭 RFCOMM（非本端主动 close：closedLocally 仍为 false）
        channel.recvControl(10)
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

    /** 链路死（EOF 已登记、通道已终结）→ controlLinkAlive 判死，跳过 join。 */
    @Test
    fun `链路死时判死跳过 join`() {
        val channel = deadChannel()
        try {
            assertFalse(channel.isAlive)
            assertFalse(Iap2BluetoothServer.controlLinkAlive(channel))
        } finally {
            channel.close()
        }
    }

    /** 链路活着（通道在、就绪、未终结）→ 预检放行，照常 join。 */
    @Test
    fun `链路活着时放行 join`() {
        val channel = aliveChannel()
        try {
            BtSessionGate.claimControl(channel, inbound = false)
            BtSessionGate.markControlReady(channel)
            assertTrue(channel.isAlive)
            assertTrue(Iap2BluetoothServer.controlLinkAlive(channel))
        } finally {
            channel.close()
        }
    }

    /** 没有活跃通道（车机尚未回连）→ 链路死，跳过 join。 */
    @Test
    fun `无活跃通道时判死`() {
        BtSessionGate.sessionActive = false
        assertFalse(Iap2BluetoothServer.controlLinkAlive(null))
    }

    /** iAP2 已迁到 WiFi 隧道（sessionActive=true）→ 不算链路死，预检放行。 */
    @Test
    fun `WiFi 隧道迁移场景放行不算链路死`() {
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

    // ---- 审计 E3（docs/flow-audit-20261125.md）：0x4301 路径补链路预检 ----
    // 1.2.109 的预检只守 0x5703 入口；0x4301 的 wifi-join-on-endpoint 已改为与 0x5703
    // 共用同一入口判据（`bluetoothLinkDeadForJoin` == `controlLinkAlive() == false`）。
    // 下面两条覆盖同一判据在 0x4301 场景（晚到端点/车机已退出 CarPlay）下的语义。

    /** 0x4301 晚到 + 链路死（对端已断 RFCOMM）→ 预检判死，跳过 join，不烧 25s 预算。 */
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

    /** 0x4301 场景链路活（控制通道就绪未终结）→ 预检放行，照常 join。 */
    @Test
    fun `0x4301 场景链路活时预检放行照常 join`() {
        val channel = aliveChannel()
        try {
            BtSessionGate.claimControl(channel, inbound = false)
            BtSessionGate.markControlReady(channel)
            assertTrue("链路活必须放行 join", Iap2BluetoothServer.controlLinkAlive(channel))
        } finally {
            channel.close()
        }
    }
}
