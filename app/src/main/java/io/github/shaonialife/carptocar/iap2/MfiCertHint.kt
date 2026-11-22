package io.github.shaonialife.carptocar.iap2

/**
 * 从车机 `0xAA01` 认证证书（DER / PKCS#7）里识别 MFi 协处理器代际，
 * 给出冷启动时的首选挑战长度（按真实能力分支，不靠品牌字符串猜）。
 *
 * 2026-11-21 宝马 530Li（MGU-55-J，iDrive 7）实车定谳：
 * 车机 `0xAA01` 交出的是 **MFi 2.0C 证书**（RSA-1024 + `sha1WithRSAEncryption`，CN=`IPA_...`，908B）。
 * CP2.0C 协处理器硬件只接受 20 字节 SHA-1 挑战；此前蓝牙路径未检查证书且品牌白名单不含 BMW/MGU，
 * 冷启动发 32B 导致车机在 56~112ms 内直接断开 RFCOMM，车机报「添加失败，请重试」。
 *
 * 判定顺序（ECDSA 优先，避免证书链含 RSA CA 时被误判成 RSA）：
 *  1. 含 `id-ecPublicKey`（1.2.840.10045.2.1）→ MFi 3.0 / AuthV3（ECDSA P-256）→ 首选 **32B**；
 *  2. 含 `sha1WithRSAEncryption`（1.2.840.113549.1.1.5）且含 `rsaEncryption` → MFi 2.0C（RSA-1024/SHA-1）→ 首选 **20B**；
 *  3. 含 `sha256WithRSAEncryption`（1.2.840.113549.1.1.11）且含 `rsaEncryption` → MFi 3.0（RSA-2048/SHA-256）→ 首选 **32B**；
 *  4. 仅含 `rsaEncryption`（1.2.840.113549.1.1.1）→ RSA 证书 → 保守首选 **20B**；
 *  5. 均不含（单测伪证书 / 非标准字节）→ [Kind.UNKNOWN]，回退调用方既有基线。
 */
object MfiCertHint {
    enum class Kind(
        val prefer20First: Boolean?,
        val label: String,
    ) {
        ECDSA_SHA256(false, "ECDSA-P256（MFi 3.0）"),
        RSA_SHA1(true, "RSA/SHA-1（MFi 2.0C）"),
        RSA_SHA256(false, "RSA/SHA-256（MFi 3.0）"),
        RSA_OTHER(true, "RSA"),
        UNKNOWN(null, "未知"),
    }

    /** ANSI X9.62 `id-ecPublicKey`（1.2.840.10045.2.1）DER 编码。 */
    private val OID_EC_PUBLIC_KEY =
        byteArrayOf(0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)

    /** PKCS#1 `rsaEncryption`（1.2.840.113549.1.1.1）DER 编码。 */
    private val OID_RSA_ENCRYPTION =
        byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01)

    /** PKCS#1 `sha1WithRSAEncryption`（1.2.840.113549.1.1.5）DER 编码。 */
    private val OID_SHA1_WITH_RSA =
        byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x05)

    /** PKCS#1 `sha256WithRSAEncryption`（1.2.840.113549.1.1.11）DER 编码。 */
    private val OID_SHA256_WITH_RSA =
        byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)

    /** 从 `0xAA01` 完整 CSM 报文体（优先取 param 0，缺失时扫整段）识别证书类型。 */
    fun inspectFrameBody(frameBody: ByteArray): Kind {
        val certBytes =
            runCatching {
                Iap2Csm.parseParams(frameBody).firstOrNull { it.id == 0 && it.value.isNotEmpty() }?.value
            }.getOrNull() ?: frameBody
        return inspect(certBytes)
    }

    /** 从 DER / PKCS#7 证书字节识别协处理器公钥类型。 */
    fun inspect(der: ByteArray): Kind {
        if (der.isEmpty()) return Kind.UNKNOWN
        if (containsBytes(der, OID_EC_PUBLIC_KEY)) return Kind.ECDSA_SHA256
        if (!containsBytes(der, OID_RSA_ENCRYPTION)) return Kind.UNKNOWN
        if (containsBytes(der, OID_SHA1_WITH_RSA)) return Kind.RSA_SHA1
        if (containsBytes(der, OID_SHA256_WITH_RSA)) return Kind.RSA_SHA256
        return Kind.RSA_OTHER
    }

    private fun containsBytes(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        for (start in 0..(haystack.size - needle.size)) {
            var matched = true
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }
}
