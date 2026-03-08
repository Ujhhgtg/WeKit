package moe.ouom.wekit.hooks.items.payment

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.config.RuntimeConfig
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.CryptoManager
import moe.ouom.wekit.utils.EncryptedData
import moe.ouom.wekit.utils.common.ToastUtils

@HookItem(path = "红包与支付/指纹支付", desc = "使用指纹快捷确认支付 (没写完)")
object FingerprintPay : BaseClickableFunctionHookItem() {

    private val TAG = nameof(FingerprintPay)

    val activity by lazy { RuntimeConfig.getLauncherUIActivity() as Activity }

    private val tempEncData: EncryptedData? = null

    private fun getOrCreateFragment(activity: Activity): Fragment {
        val getSupportFragmentManager = activity.asResolver()
            .firstMethod {
                name = "getSupportFragmentManager"
                superclass()
            }
        val fm = getSupportFragmentManager.invoke()!!

        var fragment = fm.asResolver()
            .firstMethod {
                name = "findFragmentByTag"
                superclass()
            }.invoke(TAG)!! as? Fragment?
        if (fragment == null) {
            fragment = Fragment()
            (fm.asResolver().firstMethod {
                name = "beginTransaction"
                superclass()
            }
            .invoke() as FragmentTransaction)
            .add(fragment, TAG).commitNow()
        }
        return fragment
    }

    override fun onClick(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ToastUtils.showToast("Android 版本过低 (< Android 11), 无法使用指纹验证!")
            return
        }

        if (tempEncData == null)
            encryptWithBiometric("hello") { encData ->
                ToastUtils.showToast("encData.ciphertext=${encData.ciphertext}, encData.iv=${encData.iv}")
            }
        else
            decryptWithBiometric(tempEncData) { plaintext ->
                ToastUtils.showToast("plaintext=$plaintext")
            }
    }

    private fun buildPrompt(
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit
    ): BiometricPrompt {
        val fragment = getOrCreateFragment(activity)
        val executor = ContextCompat.getMainExecutor(activity)
        return BiometricPrompt(fragment, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess(result)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                ToastUtils.showToast("指纹验证失败! 错误码: $code, 错因: $msg")
            }
            override fun onAuthenticationFailed() {
                ToastUtils.showToast("指纹不匹配!")
            }
        })
    }

    private val cryptoManager = CryptoManager()

    @RequiresApi(Build.VERSION_CODES.R)
    private val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Authenticate")
        .setSubtitle("Use biometric to access encrypted data")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()

    // --- ENCRYPT ---
    @RequiresApi(Build.VERSION_CODES.R)
    fun encryptWithBiometric(plaintext: String, onSuccess: (EncryptedData) -> Unit) {
        val cipher = cryptoManager.getEncryptCipher()
        buildPrompt { result ->
            val authorizedCipher = result.cryptoObject?.cipher ?: return@buildPrompt
            onSuccess(cryptoManager.encrypt(plaintext, authorizedCipher))
        }.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    // --- DECRYPT ---
    @RequiresApi(Build.VERSION_CODES.R)
    fun decryptWithBiometric(encryptedData: EncryptedData, onSuccess: (String) -> Unit) {
        val iv = android.util.Base64.decode(encryptedData.iv, android.util.Base64.DEFAULT)
        val cipher = cryptoManager.getDecryptCipher(iv)
        buildPrompt { result ->
            val authorizedCipher = result.cryptoObject?.cipher ?: return@buildPrompt
            val plaintext = cryptoManager.decrypt(encryptedData, authorizedCipher)
            onSuccess(plaintext)
        }.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
