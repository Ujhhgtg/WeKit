package dev.ujhhgtg.wekit.extensions.monet

import com.android.apksig.KeyConfig
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import com.android.apksig.ApkSigner as AndroidApkSigner

internal object MonetApkSigner {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun sign(unsignedApk: File, signedApk: File, minSdk: Int) {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val dn = org.bouncycastle.asn1.x500.X500Name("CN=WeKit Monet Overlay")
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 30L * 365 * 24 * 60 * 60 * 1000)
        val certBuilder = JcaX509v3CertificateBuilder(
            dn,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            dn,
            keyPair.public,
        )
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert: X509Certificate = JcaX509CertificateConverter()
            .getCertificate(certBuilder.build(contentSigner))
        val signerConfig = AndroidApkSigner.SignerConfig.Builder(
            "WeKitMonet",
            KeyConfig.Jca(keyPair.private),
            listOf(cert),
        ).build()

        signedApk.parentFile?.mkdirs()
        AndroidApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(minSdk)
            .build()
            .sign()
    }
}
