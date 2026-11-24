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
}
