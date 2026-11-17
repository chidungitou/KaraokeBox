package com.karaokebox.core.net

import java.io.DataInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Kho chứng chỉ gốc đi kèm app (Mozilla, file assets/cacerts.bin) cho các box Android cũ.
 * Android 5-7 thiếu nhiều chứng chỉ gốc mới nên tải từ GitHub báo "Trust anchor for certification path not found"
 * dù mạng bình thường. Cách dùng: tin theo kho của hệ thống TRƯỚC; chỉ khi hệ thống từ chối mới thử kho đi kèm.
 * Chỉ dùng cho các kết nối cập nhật app / NewPipe từ GitHub, không đụng tới phần YouTube.
 */
object TrustedRoots {

    /** Bộ tạo kết nối TLS dùng cho OkHttp (sslSocketFactory(factory, trustManager)). */
    class Material(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

    /** Đọc cacerts.bin: [số lượng: int] rồi mỗi chứng chỉ [độ dài: int][DER]. Hỏng giữa chừng thì ném IOException. */
    fun readBundle(input: InputStream): List<X509Certificate> {
        val inp = DataInputStream(input.buffered())
        val n = inp.readInt()
        require(n in 1..2000) { "cacerts.bin không hợp lệ" }
        val cf = CertificateFactory.getInstance("X.509")
        val out = ArrayList<X509Certificate>(n)
        repeat(n) {
            val len = inp.readInt()
            require(len in 1..65_536) { "cacerts.bin không hợp lệ" }
            val der = ByteArray(len)
            inp.readFully(der)
            out.add(cf.generateCertificate(der.inputStream()) as X509Certificate)
        }
        return out
    }

    /** Kho tin cậy mặc định của hệ thống. */
    fun systemTrustManager(): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Kho tin cậy chỉ gồm [roots]. */
    fun trustManagerFor(roots: List<X509Certificate>): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        roots.forEachIndexed { i, c -> ks.setCertificateEntry("root$i", c) }
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Tin theo [primary]; nếu từ chối thì thử [extra]. Chuỗi chứng chỉ vẫn phải hợp lệ và còn hạn theo [extra]. */
    class FallbackTrustManager(private val primary: X509TrustManager, private val extra: X509TrustManager) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            primary.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                primary.checkServerTrusted(chain, authType)
            } catch (first: CertificateException) {
                try {
                    extra.checkServerTrusted(chain, authType)
                } catch (_: CertificateException) {
                    throw first   // báo lỗi gốc của hệ thống
                }
            }
        }

        // OkHttp dựng chuỗi chứng chỉ dựa trên danh sách này nên phải có cả hai kho
        override fun getAcceptedIssuers(): Array<X509Certificate> = primary.acceptedIssuers + extra.acceptedIssuers
    }

    /** null nếu không đọc được kho đi kèm (khi đó dùng kết nối mặc định như trước). */
    fun build(open: () -> InputStream): Material? {
        return try {
            val roots = open().use { readBundle(it) }
            val tm = FallbackTrustManager(systemTrustManager(), trustManagerFor(roots))
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            Material(ctx.socketFactory, tm)
        } catch (e: Exception) {
            null
        }
    }

    /** true nếu lỗi (hoặc nguyên nhân sâu hơn) là do không tin được chứng chỉ của máy chủ. */
    fun isCertError(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is CertificateException || t is java.security.cert.CertPathValidatorException ||
                t is javax.net.ssl.SSLHandshakeException
            ) return true
            t = t.cause
        }
        return false
    }
}
