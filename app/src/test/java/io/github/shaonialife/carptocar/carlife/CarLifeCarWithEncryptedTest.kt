package io.github.shaonialife.carptocar.carlife

import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeCarWithEncryptedTest {
    @Test
    fun `2_0 MD5 身份证明——匹配真实设备字段通过，篡改任意字段拒绝`() {
        val challenge = CarLifeAuthentication.newChallenge()
        assertTrue(CarLifeAuthentication.isVersion2(challenge))
        val nonce = challenge.removePrefix("2.0;")
        val info = CarLifeProtocol.DeviceInfo(brand = "Xiaomi", model = "2509FPN0BC", sdk = "29")
        val md5 =
            MessageDigest
                .getInstance("MD5")
                .digest("CARLIFE${info.brand}${info.model}${info.sdk}$nonce".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val validPayload = CarLifeProtocol.pbString(1, md5)
        assertTrue(CarLifeAuthentication.verify(challenge, validPayload, info))
        assertFalse(CarLifeAuthentication.verify(challenge, validPayload, info.copy(model = "Other")))
        assertFalse(CarLifeAuthentication.verify("2.0;wrongnonce123", validPayload, info))
    }

    @Test
    fun `RSA 2048 种子协商与 AES ECB PKCS5 加解密——空载荷保持 0 字节`() {
        val exchange = CarLifeSeedExchange()
        val pubB64 = exchange.createPublicKey()
        val pubKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
        val rawSeed = ByteArray(16) { (it + 0x21).toByte() }
        val encryptedSeedB64 =
            Base64.getEncoder().encodeToString(
                Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                    init(Cipher.ENCRYPT_MODE, pubKey)
                    doFinal(rawSeed)
                },
            )
        val decryptedSeed = exchange.decryptSeed(encryptedSeedB64)
        assertNotNull(decryptedSeed)
        assertArrayEquals(rawSeed, decryptedSeed)

        val cipher = CarLifePayloadCipher(rawSeed)
        assertEquals(0, cipher.encrypt(ByteArray(0)).size)
        assertEquals(0, cipher.decrypt(ByteArray(0)).size)
        val sample = "CarWith-1920x720@30".toByteArray(Charsets.UTF_8)
        assertArrayEquals(sample, cipher.decrypt(cipher.encrypt(sample)))
    }

    @Test
    fun `XIAOMI_MIX 音频规则——INIT 与 TTS 走 AES 解密，9240 媒体 PCM 保持裸流`() {
        val cipher = CarLifePayloadCipher(ByteArray(16) { (it + 1).toByte() })
        val initPlain = CarLifeProtocol.pbVarint(1, 48000) + CarLifeProtocol.pbVarint(2, 2) + CarLifeProtocol.pbVarint(3, 16)
        val initEnc = cipher.encrypt(initPlain)
        assertArrayEquals(
            initPlain,
            CarLifeAudioPayload.decode(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MUSIC_PKG_INIT, initEnc, cipher),
        )
        val rawPcm = ByteArray(1920) { (it and 0x7F).toByte() }
        assertArrayEquals(
            rawPcm,
            CarLifeAudioPayload.decode(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MUSIC_PKG_NORMAL, rawPcm, cipher),
        )
        val ttsPcmEnc = cipher.encrypt(rawPcm)
        assertArrayEquals(
            rawPcm,
            CarLifeAudioPayload.decode(CarLifeProtocol.PORT_TTS, CarLifeProtocol.TTS_PKG_NORMAL, ttsPcmEnc, cipher),
        )
    }

    @Test
    fun `H264 AccessUnit 重组——跨包切分正确拼接，且无内联 SPS 的 IDR 自动前置缓存 SPS PPS`() {
        val assembler = CarLifeVideoAccessUnitAssembler()
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x28.toByte(), 0x95.toByte())
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        // first_mb_in_slice = 0 -> first bit = 1 (0x80)
        val idrSlice = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
        val pSlice = byteArrayOf(0x41, 0x9A.toByte(), 0x24, 0x01)
        val start = byteArrayOf(0, 0, 0, 1)

        val stream =
            start + sps + start + pps + start + idrSlice +
                start + pSlice +
                start + idrSlice // 第二个 IDR 不带 SPS/PPS
        // 切成两段推送以验证跨包重组，并在时间戳变化时冲刷尾帧
        val part1 = stream.copyOfRange(0, 15)
        val part2 = stream.copyOfRange(15, stream.size)
        val out = ArrayList<CarLifeVideoAccessUnitAssembler.AccessUnit>()
        out += assembler.push(1000L, part1)
        out += assembler.push(1000L, part2)
        out += assembler.flush()

        assertEquals(3, out.size)
        assertTrue(out[0].keyFrame)
        assertFalse(out[1].keyFrame)
        assertTrue(out[2].keyFrame)
        // 第二个裸 IDR 必须自动前置缓存的 SPS+PPS，保证自包含
        assertArrayEquals(start + sps + start + pps + start + idrSlice, out[2].data)
    }

    @Test
    fun `98386 画布守卫——无画布时不声明 DISABLE_RESOLUTION_OPTION，有画布时声明`() {
        val withoutCanvas = CarLifeProtocol.carWithFeatureResponse(preferVehicleResolution = false)
        val withCanvas = CarLifeProtocol.carWithFeatureResponse(preferVehicleResolution = true)
        assertFalse(String(withoutCanvas, Charsets.ISO_8859_1).contains("DISABLE_RESOLUTION_OPTION"))
        assertTrue(String(withCanvas, Charsets.ISO_8859_1).contains("DISABLE_RESOLUTION_OPTION"))
        assertTrue(String(withoutCanvas, Charsets.ISO_8859_1).contains("CONTENT_ENCRYPTION"))
    }

    @Test
    fun `七路回环端到端握手与加密推流——完整跑通小米 CarWith 4_1 全流程`() {
        val carLifePorts =
            intArrayOf(
                CarLifeProtocol.PORT_CMD,
                CarLifeProtocol.PORT_VIDEO,
                CarLifeProtocol.PORT_MEDIA,
                CarLifeProtocol.PORT_TTS,
                CarLifeProtocol.PORT_VR,
                CarLifeProtocol.PORT_TOUCH,
                CarLifeProtocol.PORT_DATA,
            )
        val servers = carLifePorts.associateWith { ServerSocket(0) }
        val negotiatedParams = AtomicReference<Triple<Int, Int, Int>>()
        val receivedFrames = ArrayList<Pair<ByteArray, Boolean>>()
        val frameLatch = CountDownLatch(2)
        val touchLatch = CountDownLatch(1)
        val resetLatch = CountDownLatch(1)
        val returnToCarCalled = AtomicBoolean(false)
        val returnLatch = CountDownLatch(1)
        val decryptedTouch = AtomicReference<ByteArray>()

        val serverThread =
            Thread {
                val accepted = servers.mapValues { (_, srv) -> srv.accept() }
                val cmdSock = accepted.getValue(CarLifeProtocol.PORT_CMD)
                val videoSock = accepted.getValue(CarLifeProtocol.PORT_VIDEO)
                val touchSock = accepted.getValue(CarLifeProtocol.PORT_TOUCH)
                val cmdIn = DataInputStream(cmdSock.getInputStream())
                val cmdOut = cmdSock.getOutputStream()

                fun readCmd(): Pair<Int, ByteArray> {
                    val h = ByteArray(8)
                    cmdIn.readFully(h)
                    val len = ((h[0].toInt() and 0xFF) shl 8) or (h[1].toInt() and 0xFF)
                    val type =
                        ((h[4].toInt() and 0xFF) shl 24) or
                            ((h[5].toInt() and 0xFF) shl 16) or
                            ((h[6].toInt() and 0xFF) shl 8) or
                            (h[7].toInt() and 0xFF)
                    val body = ByteArray(len)
                    if (len > 0) cmdIn.readFully(body)
                    return type to body
                }

                fun writeCmd(
                    out: OutputStream,
                    type: Int,
                    body: ByteArray = ByteArray(0),
                ) {
                    out.write(CarLifeProtocol.packCommand(type, body))
                    out.flush()
                }

                // 1. 收到 0x18001 (4.1) -> 回 0x10002 + 0x1001B + 0x10004 + 0x10051
                val (vType, vBody) = readCmd()
                assertEquals(CarLifeProtocol.HU_PROTOCOL_VERSION, vType)
                assertEquals(4, CarLifeProtocol.firstIntField(vBody, 1))
                assertEquals(1, CarLifeProtocol.firstIntField(vBody, 2))
                writeCmd(cmdOut, CarLifeProtocol.MD_VERSION_MATCH, CarLifeProtocol.pbVarint(1, 1))

                val (statType, _) = readCmd()
                assertEquals(CarLifeProtocol.HU_STATISTICS, statType)

                writeCmd(cmdOut, CarLifeProtocol.MD_FOREGROUND)
                val (fgType, fgBody) = readCmd()
                assertEquals(CarLifeProtocol.HU_GO_TO_FOREGROUND, fgType)
                assertEquals(0, fgBody.size)

                val mdInfo =
                    CarLifeProtocol.pbString(4, "Xiaomi") +
                        CarLifeProtocol.pbString(14, "2509FPN0BC") +
                        CarLifeProtocol.pbString(20, "29")
                writeCmd(cmdOut, CarLifeProtocol.MD_INFO, mdInfo)

                val (huInfoType, _) = readCmd()
                assertEquals(CarLifeProtocol.HU_INFO, huInfoType)
                val (authReqType, authReqBody) = readCmd()
                assertEquals(CarLifeProtocol.HU_AUTH_REQUEST, authReqType)
                val challenge = CarLifeProtocol.firstStringField(authReqBody, 1)!!
                val nonce = challenge.removePrefix("2.0;")
                val proof =
                    MessageDigest
                        .getInstance("MD5")
                        .digest("CARLIFEXiaomi2509FPN0BC29$nonce".toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }

                writeCmd(cmdOut, CarLifeProtocol.MD_FEATURE_CONFIG)
                writeCmd(cmdOut, CarLifeProtocol.MD_AUTH_RESPONSE, CarLifeProtocol.pbString(1, proof))

                val (authResType, _) = readCmd()
                assertEquals(CarLifeProtocol.HU_AUTH_RESULT, authResType)
                val (featType, featBody) = readCmd()
                assertEquals(CarLifeProtocol.HU_FEATURE_CONFIG, featType)
                assertTrue(String(featBody, Charsets.ISO_8859_1).contains("DISABLE_RESOLUTION_OPTION"))

                // 2. RSA / AES 协商
                writeCmd(cmdOut, CarLifeProtocol.MD_ENCRYPT_SETUP)
                val (rsaType, rsaBody) = readCmd()
                assertEquals(CarLifeProtocol.HU_RSA_PUBLIC_KEY, rsaType)
                val pubB64 = CarLifeProtocol.firstStringField(rsaBody, 1)!!
                val pubKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
                val aesKey = ByteArray(16) { (0x41 + it).toByte() }
                val cipher = CarLifePayloadCipher(aesKey)
                val encSeedB64 =
                    Base64.getEncoder().encodeToString(
                        Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                            init(Cipher.ENCRYPT_MODE, pubKey)
                            doFinal(aesKey)
                        },
                    )
                writeCmd(cmdOut, CarLifeProtocol.MD_AES_SEED, CarLifeProtocol.pbString(1, encSeedB64))
                val (aesAckType, aesAckBody) = readCmd()
                assertEquals(CarLifeProtocol.HU_AES_ACK, aesAckType)
                assertEquals(0, aesAckBody.size)

                writeCmd(cmdOut, CarLifeProtocol.MD_ENCRYPT_READY)
                writeCmd(cmdOut, CarLifeProtocol.MD_AUTH_RESULT, cipher.encrypt(CarLifeProtocol.pbVarint(1, 1)))

                val (peerAckType, peerAckBody) = readCmd()
                assertEquals(CarLifeProtocol.HU_AUTHEN_ACK, peerAckType)
                assertEquals(0, peerAckBody.size)

                // 3. 收到加密的 0x18007 (1920x720@30) -> 回加密的 0x10008 -> 收到 0x18009 (0B)
                val (vidInitType, vidInitEnc) = readCmd()
                assertEquals(CarLifeProtocol.HU_VIDEO_ENC_INIT, vidInitType)
                val vidInitPlain = cipher.decrypt(vidInitEnc)
                assertEquals(1920, CarLifeProtocol.firstIntField(vidInitPlain, 1))
                assertEquals(720, CarLifeProtocol.firstIntField(vidInitPlain, 2))
                writeCmd(
                    cmdOut,
                    CarLifeProtocol.MD_VIDEO_ENC_INIT,
                    cipher.encrypt(CarLifeProtocol.carWithVideoInit(1920, 720, 30)),
                )
                val (vidStartType, vidStartBody) = readCmd()
                assertEquals(CarLifeProtocol.VIDEO_ENC_START, vidStartType)
                assertEquals(0, vidStartBody.size)

                // 4. 推送两帧加密的 H.264 视频帧（IDR + P）+ 0x10021 返回原车
                val startCode = byteArrayOf(0, 0, 0, 1)
                val idrAu = startCode + byteArrayOf(0x67, 0x42, 0x00, 0x28) + startCode + byteArrayOf(0x68, 0xCE.toByte()) + startCode + byteArrayOf(0x65, 0x88.toByte(), 0x01)
                val pAu = startCode + byteArrayOf(0x41, 0x9A.toByte(), 0x02)
                val tail = startCode + byteArrayOf(0x09, 0x10) // AUD 冲刷前一帧
                val vOut = videoSock.getOutputStream()
                vOut.write(CarLifeProtocol.packStream(1795278603, CarLifeProtocol.VIDEO_DATA, cipher.encrypt(idrAu)))
                vOut.write(CarLifeProtocol.packStream(1795278603, CarLifeProtocol.VIDEO_DATA, cipher.encrypt(pAu + tail)))
                vOut.flush()

                // 读取加密触摸
                val tIn = DataInputStream(touchSock.getInputStream())
                val th = ByteArray(8)
                tIn.readFully(th)
                val tLen = ((th[0].toInt() and 0xFF) shl 8) or (th[1].toInt() and 0xFF)
                val tBody = ByteArray(tLen)
                tIn.readFully(tBody)
                decryptedTouch.set(cipher.decrypt(tBody))
                touchLatch.countDown()

                writeCmd(cmdOut, CarLifeProtocol.MD_RETURN_TO_CAR)
                val (resetType, resetBody) = readCmd()
                assertEquals(CarLifeProtocol.HU_VIDEO_ENC_RESET, resetType)
                assertEquals(0, resetBody.size)
                resetLatch.countDown()
                Thread.sleep(150)
                accepted.values.forEach { runCatching { it.close() } }
            }.apply {
                isDaemon = true
                start()
            }

        var fakeClock = 1_000L
        val client =
            CarLifeHuClient(
                log = {},
                onVideoFrame = { data, key, ts ->
                    assertEquals(0L, ts)
                    synchronized(receivedFrames) { receivedFrames += data to key }
                    frameLatch.countDown()
                },
                onVideoParams = { w, h, f -> negotiatedParams.set(Triple(w, h, f)) },
                requestedFps = 30,
                initialWidth = 1920,
                initialHeight = 720,
                encryptedCarWith = true,
                hasTrustedCanvas = true,
                onReturnToCar = {
                    returnToCarCalled.set(true)
                    returnLatch.countDown()
                },
                monotonicMillis = {
                    fakeClock += 16L
                    fakeClock
                },
                connectSocket = { port ->
                    val localPort = servers.getValue(port).localPort
                    java.net.Socket("127.0.0.1", localPort)
                },
            )

        try {
            assertTrue(client.start())
            assertTrue("应收到两帧解密后的完整 H.264 AU", frameLatch.await(5, TimeUnit.SECONDS))
            assertEquals(Triple(1920, 720, 30), negotiatedParams.get())
            synchronized(receivedFrames) {
                assertTrue(receivedFrames[0].second)
                assertFalse(receivedFrames[1].second)
            }
            client.touchDown(960, 360)
            assertTrue("触控包应送达并可由 AES 解密", touchLatch.await(3, TimeUnit.SECONDS))
            assertEquals(960, CarLifeProtocol.firstIntField(decryptedTouch.get()!!, 1))
            assertEquals(360, CarLifeProtocol.firstIntField(decryptedTouch.get()!!, 2))
            assertTrue("返回原车 0x10021 应触发回调", returnLatch.await(3, TimeUnit.SECONDS))
            assertTrue(returnToCarCalled.get())
            assertTrue(client.requestKeyFrame())
            assertTrue("forceKeyFrame 应下发 0x1800B VIDEO_ENCODER_RESET", resetLatch.await(3, TimeUnit.SECONDS))
        } finally {
            client.stop()
            servers.values.forEach { runCatching { it.close() } }
            serverThread.join(2_000)
        }
    }
}
