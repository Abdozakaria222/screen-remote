package com.screenremote.app.control.androidtv

import android.content.Context
import com.screenremote.app.util.Net
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * شهادة عميل موقّعة ذاتياً للاقتران مع Android TV. تُنشأ مرة واحدة وتُخزَّن،
 * ويُعاد استخدام نفس الشهادة في الاقتران (6467) وفي الاتصال (6466).
 */
object CertUtil {

    private const val KEYSTORE_FILE = "androidtv_client.p12"
    private const val ALIAS = "client"
    private val PASSWORD = "screenremote".toCharArray()

    @Synchronized
    private fun keyStore(ctx: Context): KeyStore {
        val file = File(ctx.filesDir, KEYSTORE_FILE)
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            file.inputStream().use { ks.load(it, PASSWORD) }
            return ks
        }
        ks.load(null, null)
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = selfSigned(keyPair)
        ks.setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf(cert))
        file.outputStream().use { ks.store(it, PASSWORD) }
        return ks
    }

    fun clientCert(ctx: Context): X509Certificate =
        keyStore(ctx).getCertificate(ALIAS) as X509Certificate

    fun keyManagers(ctx: Context): Array<KeyManager> {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(ctx), PASSWORD)
        return kmf.keyManagers
    }

    fun sslContext(keyManagers: Array<KeyManager>): SSLContext {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(keyManagers, arrayOf(Net.TrustAllManager()), SecureRandom())
        return ctx
    }

    private fun selfSigned(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=Screen Remote, O=ScreenRemote")
        val serial = BigInteger.valueOf(now)
        val builder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /**
     * حساب السر من الكود الذي يظهر على الشاشة:
     * SHA-256( معامل+أس المفتاح العام للعميل + معامل+أس المفتاح العام للخادم + الكود[1..] ).
     * ويجب أن تطابق أول بايت من الناتج أول بايت من الكود.
     */
    fun pairingSecret(code: String, client: X509Certificate, server: X509Certificate): ByteArray {
        val codeBytes = hexToBytes(code)
        if (codeBytes.size < 2) throw IOException("كود غير صالح")
        val md = MessageDigest.getInstance("SHA-256")
        val clientPub = client.publicKey as RSAPublicKey
        val serverPub = server.publicKey as RSAPublicKey
        md.update(unsigned(clientPub.modulus))
        md.update(unsigned(clientPub.publicExponent))
        md.update(unsigned(serverPub.modulus))
        md.update(unsigned(serverPub.publicExponent))
        md.update(codeBytes, 1, codeBytes.size - 1)
        val digest = md.digest()
        if (digest[0] != codeBytes[0]) throw IOException("الكود غير مطابق — تأكد من إدخاله صحيحاً")
        return digest
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun hexToBytes(s: String): ByteArray {
        val clean = s.trim().replace(" ", "")
        val n = clean.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) throw IOException("كود غير صالح")
            out[i] = ((hi shl 4) + lo).toByte()
        }
        return out
    }
}
