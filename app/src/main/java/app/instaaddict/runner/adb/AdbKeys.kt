package app.instaaddict.runner.adb

import android.content.Context
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The app's own adb identity. It is created once and kept in private storage, so a phone that has
 * accepted it (pairing or "Always allow") keeps trusting it after restarts.
 */
class AdbKeys(context: Context) {
    private val dir = File(context.filesDir, "adb").apply { mkdirs() }
    private val keyFile = File(dir, "key.pk8")
    private val certFile = File(dir, "cert.der")
    private var cached: Pair<PrivateKey, Certificate>? = null

    @Synchronized
    fun privateKey(): PrivateKey = load().first

    @Synchronized
    fun certificate(): Certificate = load().second

    private fun load(): Pair<PrivateKey, Certificate> {
        cached?.let { return it }
        val pair = if (keyFile.isFile && certFile.isFile) {
            try {
                read()
            } catch (_: Exception) {
                generate()
            }
        } else {
            generate()
        }
        cached = pair
        return pair
    }

    private fun read(): Pair<PrivateKey, Certificate> {
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        return Pair(key, cert)
    }

    private fun generate(): Pair<PrivateKey, Certificate> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Principal("CN=InstaAddict")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - 60_000L),
            Date(now + 30L * 365L * 24L * 3600L * 1000L),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        keyFile.writeBytes(PKCS8EncodedKeySpec(keyPair.private.encoded).encoded)
        certFile.writeBytes(cert.encoded)
        runCatching {
            android.system.Os.chmod(keyFile.path, 384) // 0600
            android.system.Os.chmod(certFile.path, 384)
        }
        return Pair(keyPair.private, cert)
    }
}
