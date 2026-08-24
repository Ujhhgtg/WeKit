package dev.ujhhgtg.wekit.extensions.monet

import com.android.apksig.ApkVerifier
import com.android.apksig.KeyConfig
import com.reandroid.archive.block.SignatureId
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
        require(unsignedApk.isFile) { "Unsigned Monet overlay does not exist: $unsignedApk" }
        require(unsignedApk.canonicalFile != signedApk.canonicalFile) {
            "Monet signer input and output must be different files"
        }
        loadMonetTemplate(unsignedApk).use { apk ->
            require(apk.androidManifest.minSdkVersion == minSdk) {
                "Monet signer minSdk $minSdk disagrees with manifest ${apk.androidManifest.minSdkVersion}"
            }
        }
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

        val verification = ApkVerifier.Builder(signedApk).build().verify()
        require(verification.isVerified && verification.isVerifiedUsingV3Scheme) {
            "Signed Monet overlay failed APK signature verification: ${verification.errors}"
        }
        loadMonetTemplate(signedApk).use { apk ->
            val signatures = requireNotNull(apk.apkSignatureBlock) {
                "Signed Monet overlay has no APK signing block"
            }
            require(signatures.getSignature(SignatureId.V2) != null) {
                "Signed Monet overlay has no APK Signature Scheme v2 block"
            }
            require(signatures.getSignature(SignatureId.V3) != null) {
                "Signed Monet overlay has no APK Signature Scheme v3 block"
            }
        }
    }
}
