package io.github.shaonialife.carptocar.carlife

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 小米 CarWith / CarLife 4.1 身份挑战与证明校验（源自 MIT `wangxso/mcarplay` 实机验证）。
 *
 * `2.0;<nonce>` 分支校验 `MD5("CARLIFE" + brand + model + sdk + nonce)`；
 * 旧挑战回落到小米组件内置公钥的 PKCS#1 v1.5 原始 RSA 验签。
 */
internal object CarLifeAuthentication {
    private const val PREFIX = "2.0;"
    private val noncePattern = Regex("[A-Za-z0-9_-]{6,128}")
    private val proofPattern = Regex("[0-9a-f]{32}")

    private const val XIAOMI_CARLIFE_PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC86AUdyKOcMUB/iDMtHpFBOdLPWThDziP8t20sF0mRKlNBIAxlUZxQWIa/" +
            "DEhJL9kx05+RyrFDN4B3d66UIURtia3GhWPoiBCbNornl73ynfFKk1BUDma3O8Qs9Z6YOCkXrGOFQBDfltfXs/w7Cog" +
            "kuS2sZsBPOOMVom04I7KWPQIDAQAB"

    fun newChallenge(random: SecureRandom = SecureRandom()): String {
        val nonce = ByteArray(16).also(random::nextBytes)
        return PREFIX + nonce.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    fun isVersion2(challenge: String): Boolean = challenge.startsWith(PREFIX)

    fun verify(
        challenge: String,
        payload: ByteArray,
        deviceInfo: CarLifeProtocol.DeviceInfo?,
    ): Boolean {
        val proof = CarLifeProtocol.firstStringField(payload, 1) ?: return false
        if (isVersion2(challenge)) {
            return verifyVersion2(challenge, deviceInfo, proof)
        }
        return runCatching {
            val sigBytes = Base64.getDecoder().decode(proof)
            val keyBytes = Base64.getDecoder().decode(XIAOMI_CARLIFE_PUBLIC_KEY)
            val pubKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes)) as RSAPublicKey
            verifyRawRsa(pubKey, sigBytes, challenge.toByteArray(Charsets.UTF_8))
        }.getOrDefault(false)
    }

    fun verifyVersion2(
        challenge: String,
        info: CarLifeProtocol.DeviceInfo?,
        proof: String,
    ): Boolean {
        if (!isVersion2(challenge) || info == null || !proofPattern.matches(proof)) return false
        val nonce = challenge.removePrefix(PREFIX)
        if (!noncePattern.matches(nonce)) return false
        val input = "CARLIFE${info.brand}${info.model}${info.sdk}$nonce".toByteArray(Charsets.UTF_8)
        val expected =
            MessageDigest.getInstance("MD5").digest(input).joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), proof.toByteArray(Charsets.US_ASCII))
    }

    private fun verifyRawRsa(
        key: RSAPublicKey,
        signatureBytes: ByteArray,
        expected: ByteArray,
    ): Boolean {
        val keySize = (key.modulus.bitLength() + 7) / 8
        if (expected.isEmpty() || expected.size > keySize - 11) return false
        val encoded =
            BigInteger(1, signatureBytes)
                .modPow(key.publicExponent, key.modulus)
                .toByteArray()
                .let { raw ->
                    when {
                        raw.size > keySize -> raw.copyOfRange(raw.size - keySize, raw.size)
                        raw.size < keySize -> ByteArray(keySize - raw.size) + raw
                        else -> raw
                    }
                }
        val delimiter = keySize - expected.size - 1
        if (encoded.size != keySize ||
            encoded[0] != 0.toByte() ||
            encoded[1] != 1.toByte() ||
            encoded[delimiter] != 0.toByte() ||
            (2 until delimiter).any { encoded[it] != 0xFF.toByte() }
        ) {
            encoded.fill(0)
            return false
        }
        val ok = MessageDigest.isEqual(expected, encoded.copyOfRange(delimiter + 1, encoded.size))
        encoded.fill(0)
        return ok
    }
}

/**
 * CarLife `CONTENT_ENCRYPTION=1` 一次性 RSA-2048 密钥交换：
 * HU 下发临时 RSA-2048 公钥（`0x1806B`），手机回传 RSA 加密的 AES 种子（`0x1006C`）。
 */
internal class CarLifeSeedExchange(
    private val random: SecureRandom = SecureRandom(),
) {
    private var keyPair: KeyPair? = null

    @Synchronized
    fun createPublicKey(): String {
        val pair =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048, random) }
                .generateKeyPair()
        keyPair = pair
        return Base64.getEncoder().encodeToString(pair.public.encoded)
    }

    @Synchronized
    fun decryptSeed(encryptedSeedBase64: String): ByteArray? =
        runCatching {
            val pair = keyPair ?: return null
            val encrypted = Base64.getDecoder().decode(encryptedSeedBase64)
            Cipher
                .getInstance("RSA/ECB/PKCS1Padding")
                .run {
                    init(Cipher.DECRYPT_MODE, pair.private)
                    doFinal(encrypted)
                }
        }.getOrNull()?.takeIf { it.size in intArrayOf(16, 24, 32) }

    @Synchronized
    fun clear() {
        keyPair = null
    }
}

/**
 * CarLife 控制/视频/触控/音频包体对称加解密（`AES/ECB/PKCS5Padding`）。
 *
 * 注意：空载荷（0B）保持 0B 原样返回（如 `0x1806D`、`0x1804C`、`0x18009`、`0x18025`）。
 */
internal class CarLifePayloadCipher(
    key: ByteArray,
) {
    private val secretKey: ByteArray = key.copyOf()

    init {
        require(secretKey.size in intArrayOf(16, 24, 32)) { "Invalid CarLife AES key length: ${secretKey.size}" }
    }

    fun encrypt(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) return ByteArray(0)
        @Suppress("GetInstance")
        return Cipher
            .getInstance("AES/ECB/PKCS5Padding")
            .run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey, "AES"))
                doFinal(payload)
            }
    }

    fun decrypt(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) return ByteArray(0)
        @Suppress("GetInstance")
        return Cipher
            .getInstance("AES/ECB/PKCS5Padding")
            .run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"))
                doFinal(payload)
            }
    }
}

/**
 * 小米 CarWith `PcmHoldHelper` 混音规则（`XIAOMI_MIX`）：
 * `MUSIC_PKG_INIT`（`0x30001`）与 TTS 通道包体走 AES 解密，而 `9240` 上的
 * `MUSIC_PKG_NORMAL`（`0x30006`）PCM 样本不经过 AES，直接原样写出。
 */
internal object CarLifeAudioPayload {
    fun decode(
        port: Int,
        pkgType: Int,
        payload: ByteArray,
        cipher: CarLifePayloadCipher?,
    ): ByteArray {
        val rawXiaomiMixPcm = port == CarLifeProtocol.PORT_MEDIA && pkgType == CarLifeProtocol.MUSIC_PKG_NORMAL
        if (cipher == null || payload.isEmpty() || rawXiaomiMixPcm) return payload
        return cipher.decrypt(payload)
    }
}

/**
 * 将小米 CarWith `8240` 视频流的分包 Annex-B 数据重组为完整 H.264 Access Unit，
 * 并在 IDR 帧缺失内联 SPS/PPS 时自动前置最近缓存的 SPS+PPS，确保下游解码器与缓存回放自包含。
 */
internal class CarLifeVideoAccessUnitAssembler {
    data class AccessUnit(
        val timestamp: Long,
        val data: ByteArray,
        val keyFrame: Boolean,
    )

    private var pendingTimestamp: Long? = null
    private val pendingNals = ArrayList<ByteArray>()
    private var pendingHasVcl = false
    private var pendingHasIdr = false
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null
    private var annexBBuffer = ByteArray(0)
    private var annexBTimestamp: Long? = null
    private var latestSps: ByteArray? = null
    private var latestPps: ByteArray? = null

    @Synchronized
    fun reset() {
        resetAccessUnit()
        annexBBuffer = ByteArray(0)
        annexBTimestamp = null
        latestSps = null
        latestPps = null
    }

    @Synchronized
    fun push(
        timestamp: Long,
        payload: ByteArray,
    ): List<AccessUnit> {
        if (payload.isEmpty()) return emptyList()
        val complete = ArrayList<AccessUnit>(2)
        if (annexBTimestamp != null && annexBTimestamp != timestamp && annexBBuffer.isNotEmpty()) {
            processAvailableNals(annexBTimestamp!!, final = true, target = complete)
        }
        if (annexBBuffer.isEmpty()) annexBTimestamp = timestamp
        annexBBuffer += payload
        processAvailableNals(timestamp, final = false, target = complete)
        return complete
    }

    @Synchronized
    fun flush(): List<AccessUnit> {
        val complete = ArrayList<AccessUnit>(1)
        annexBTimestamp?.let { ts -> processAvailableNals(ts, final = true, target = complete) }
        flushAccessUnit(complete)
        return complete
    }

    private fun processAvailableNals(
        timestamp: Long,
        final: Boolean,
        target: MutableList<AccessUnit>,
    ) {
        val starts = annexBStartCodes(annexBBuffer)
        var startsBeforeTrailing = false
        if (starts.isEmpty()) {
            if (final && annexBBuffer.isNotEmpty()) annexBBuffer = ByteArray(0)
            return
        }
        for (index in starts.indices) {
            val (start, prefixLen) = starts[index]
            if (index == starts.lastIndex && !final) break
            val bodyStart = start + prefixLen
            val bodyEnd = if (index + 1 < starts.size) starts[index + 1].first else annexBBuffer.size
            if (bodyEnd > bodyStart) {
                consumeNal(annexBBuffer.copyOfRange(bodyStart, bodyEnd), timestamp, target)
            }
        }
        if (!final) {
            val (start, prefixLen) = starts.last()
            if (start + prefixLen < annexBBuffer.size) {
                val trailing = annexBBuffer.copyOfRange(start + prefixLen, annexBBuffer.size)
                val type = trailing[0].toInt() and 0x1F
                startsBeforeTrailing =
                    pendingHasVcl &&
                        (type == 9 || type == 6 || type == 7 || type == 8 || ((type == 1 || type == 5) && firstMbInSlice(trailing) == 0))
            }
        }
        val lastStart = starts.last().first
        annexBBuffer = if (final) ByteArray(0) else annexBBuffer.copyOfRange(lastStart, annexBBuffer.size)
        if (annexBBuffer.isEmpty()) annexBTimestamp = null
        if (startsBeforeTrailing) flushAccessUnit(target)
    }

    private fun consumeNal(
        nal: ByteArray,
        timestamp: Long,
        target: MutableList<AccessUnit>,
    ) {
        if (nal.isEmpty()) return
        val type = nal[0].toInt() and 0x1F
        val isVcl = type == 1 || type == 5
        val startsNewAu =
            type == 9 ||
                (pendingHasVcl && (type == 6 || type == 7 || type == 8)) ||
                (pendingHasVcl && isVcl && firstMbInSlice(nal) == 0)
        if (startsNewAu) flushAccessUnit(target)
        if (pendingTimestamp == null) pendingTimestamp = timestamp
        pendingNals += nal
        pendingHasVcl = pendingHasVcl || isVcl
        when (type) {
            5 -> pendingHasIdr = true
            7 -> {
                pendingSps = nal
                latestSps = nal
            }
            8 -> {
                pendingPps = nal
                latestPps = nal
            }
        }
        val total = pendingNals.sumOf { it.size + START_CODE.size }
        if (total > MAX_ACCESS_UNIT_BYTES) {
            resetAccessUnit()
        }
    }

    private fun flushAccessUnit(target: MutableList<AccessUnit>) {
        val ts = pendingTimestamp
        if (ts == null || pendingNals.isEmpty()) {
            resetAccessUnit()
            return
        }
        val out = ByteArrayOutputStream()
        if (pendingHasIdr && pendingSps == null) {
            val sps = latestSps
            val pps = latestPps
            if (sps != null && pps != null) {
                out.write(START_CODE)
                out.write(sps)
                out.write(START_CODE)
                out.write(pps)
            }
        }
        pendingNals.forEach { nal ->
            out.write(START_CODE)
            out.write(nal)
        }
        target += AccessUnit(timestamp = ts, data = out.toByteArray(), keyFrame = pendingHasIdr)
        resetAccessUnit()
    }

    private fun resetAccessUnit() {
        pendingTimestamp = null
        pendingNals.clear()
        pendingHasVcl = false
        pendingHasIdr = false
        pendingSps = null
        pendingPps = null
    }

    private fun annexBStartCodes(data: ByteArray): List<Pair<Int, Int>> {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 <= data.size) {
            when {
                i + 4 <= data.size &&
                    data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() &&
                    data[i + 3] == 1.toByte() -> {
                    starts += i to 4
                    i += 4
                }
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> {
                    starts += i to 3
                    i += 3
                }
                else -> i++
            }
        }
        return starts
    }

    private fun firstMbInSlice(nal: ByteArray): Int? {
        if (nal.size < 2) return null
        val type = nal[0].toInt() and 0x1F
        if (type != 1 && type != 5) return null
        val rbsp = unescapeRbspPrefix(nal, limit = 32)
        return readFirstUe(rbsp)
    }

    private fun unescapeRbspPrefix(
        nal: ByteArray,
        limit: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(minOf(nal.size, limit))
        var zeros = 0
        for (i in 1 until minOf(nal.size, limit)) {
            val v = nal[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun readFirstUe(rbsp: ByteArray): Int? {
        var bitPos = 0
        val totalBits = rbsp.size * 8

        fun nextBit(): Int? {
            if (bitPos >= totalBits) return null
            val b = rbsp[bitPos ushr 3].toInt() and 0xFF
            val bit = (b ushr (7 - (bitPos and 7))) and 1
            bitPos++
            return bit
        }

        var leadingZeros = 0
        while (true) {
            val bit = nextBit() ?: return null
            if (bit == 1) break
            leadingZeros++
            if (leadingZeros > 30) return null
        }
        var suffix = 0
        repeat(leadingZeros) {
            val bit = nextBit() ?: return null
            suffix = (suffix shl 1) or bit
        }
        return ((1 shl leadingZeros) - 1) + suffix
    }

    private companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)
        const val MAX_ACCESS_UNIT_BYTES = 4 * 1024 * 1024
    }
}
