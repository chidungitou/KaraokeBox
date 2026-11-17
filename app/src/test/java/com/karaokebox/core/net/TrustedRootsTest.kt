package com.karaokebox.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

class TrustedRootsTest {
    private class Fake(private val error: CertificateException?) : X509TrustManager {
        var calls = 0
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { calls++; if (error != null) throw error }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val chain = arrayOf<X509Certificate>()

    @Test fun bundledRootsParseAndAreNotExpired() {
        // Gradle chạy test với thư mục làm việc là app/
        val certs = File("src/main/assets/cacerts.bin").inputStream().use { TrustedRoots.readBundle(it) }
        assertTrue(certs.size >= 100)
        certs.forEach { it.checkValidity() }   // chứng chỉ gốc hết hạn thì phải cập nhật lại cacerts.bin
    }

    @Test fun fallbackUsedOnlyWhenSystemRejects() {
        val ok = Fake(null); val extra = Fake(null)
        TrustedRoots.FallbackTrustManager(ok, extra).checkServerTrusted(chain, "RSA")
        assertEquals(0, extra.calls)                       // hệ thống tin rồi thì không cần kho đi kèm

        val bad = Fake(CertificateException("hệ thống từ chối"))
        TrustedRoots.FallbackTrustManager(bad, extra).checkServerTrusted(chain, "RSA")
        assertEquals(1, extra.calls)                       // hệ thống từ chối, kho đi kèm tin
    }

    @Test fun bothRejectThrowsSystemError() {
        val tm = TrustedRoots.FallbackTrustManager(Fake(CertificateException("của hệ thống")), Fake(CertificateException("của kho đi kèm")))
        try { tm.checkServerTrusted(chain, "RSA"); throw AssertionError("phải ném lỗi") }
        catch (e: CertificateException) { assertEquals("của hệ thống", e.message) }
    }

    @Test fun certErrorDetectionFollowsCauses() {
        assertTrue(TrustedRoots.isCertError(SSLHandshakeException("x").apply { initCause(CertPathValidatorException("Trust anchor")) }))
        assertTrue(TrustedRoots.isCertError(java.io.IOException("lỗi", CertificateException("y"))))
        assertFalse(TrustedRoots.isCertError(java.io.IOException("Unable to resolve host")))
        assertFalse(TrustedRoots.isCertError(null))
    }
}
