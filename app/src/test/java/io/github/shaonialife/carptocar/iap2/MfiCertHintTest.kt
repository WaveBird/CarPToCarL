package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MFi `0xAA01` 证书公钥类型识别单测。
 *
 * 实车依据：2026-11-21 宝马 530Li（MGU-55-J，iDrive 7）实测 `0xAA01` 返回 RSA-1024 /
 * `sha1WithRSAEncryption` 证书（MFi 2.0C 协处理器），发 32B 挑战会在 56~112ms 内被车机直接断链，
 * 必须首试 20B；而 DiPlay / AuthV3 的 ECDSA P-256 证书必须首试 32B。
 */
class MfiCertHintTest {
    private val oidEcPublicKey =
        byteArrayOf(0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)
    private val oidRsaEncryption =
        byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01)
    private val oidSha1WithRsa =
        byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x05)
    private val oidSha256WithRsa =
        byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)

    @Test
    fun bmwMguMfi20cRsaSha1CertPrefers20Bytes() {
        // 宝马 MGU-55-J 实车 0xAA01 证书里的真实算法 OID 组合（PKCS#7 + RSA-1024 + sha1WithRSAEncryption）
        val der = byteArrayOf(0x30, 0x82.toByte(), 0x03, 0x88.toByte()) + oidSha1WithRsa + byteArrayOf(0x05, 0x00) + oidRsaEncryption
        val body = Iap2Csm.param(0, der)
        val kind = MfiCertHint.inspectFrameBody(body)
        assertEquals(MfiCertHint.Kind.RSA_SHA1, kind)
        assertEquals(true, kind.prefer20First)
    }

    @Test
    fun ecdsaAuthV3CertPrefers32BytesEvenIfChainHasRsaCa() {
        // 叶子证书是 ECDSA P-256（MFi 3.0 / AuthV3），即使证书链里带 RSA CA 也不得误判成 RSA
        val der = oidRsaEncryption + oidSha1WithRsa + oidEcPublicKey
        val kind = MfiCertHint.inspectFrameBody(Iap2Csm.param(0, der))
        assertEquals(MfiCertHint.Kind.ECDSA_SHA256, kind)
        assertEquals(false, kind.prefer20First)
    }

    @Test
    fun rsaSha256Mfi30CertPrefers32Bytes() {
        val der = oidSha256WithRsa + oidRsaEncryption
        val kind = MfiCertHint.inspect(der)
        assertEquals(MfiCertHint.Kind.RSA_SHA256, kind)
        assertEquals(false, kind.prefer20First)
    }

    @Test
    fun plainRsaWithoutHashOidFallsBackTo20Bytes() {
        val der = byteArrayOf(0x30, 0x0D) + oidRsaEncryption + byteArrayOf(0x05, 0x00)
        val kind = MfiCertHint.inspect(der)
        assertEquals(MfiCertHint.Kind.RSA_OTHER, kind)
        assertEquals(true, kind.prefer20First)
    }

    @Test
    fun syntheticBytesWithoutOidReturnUnknown() {
        // 单测里的伪证书（ByteArray(64)）不含 ASN.1 OID，必须返回 UNKNOWN 交回调用方既有基线
        val dummy = ByteArray(64) { it.toByte() }
        val kind = MfiCertHint.inspectFrameBody(Iap2Csm.param(0, dummy))
        assertEquals(MfiCertHint.Kind.UNKNOWN, kind)
        assertNull(kind.prefer20First)
        assertEquals(MfiCertHint.Kind.UNKNOWN, MfiCertHint.inspect(ByteArray(0)))
    }
}
