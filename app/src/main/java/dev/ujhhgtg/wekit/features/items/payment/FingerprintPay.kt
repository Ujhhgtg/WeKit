package dev.ujhhgtg.wekit.features.items.payment

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlinedfilled.Visibility
import com.composables.icons.materialsymbols.outlinedfilled.Visibility_off
import com.tencent.mm.plugin.fingerprint.ui.FingerPrintAuthTransparentUI
import com.tenpay.android.wechat.MyKeyboardWindow
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.CryptoManager
import dev.ujhhgtg.wekit.utils.EncryptedData
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.nul


@Feature(
    id = "指纹支付",
    nameRes = "feature_fingerprint_pay_name",
    categoryIds = [FeatureCategoryIds.PAYMENT],
    descriptionRes = "feature_fingerprint_pay_description",
)
object FingerprintPay : ClickableFeature() {

    private const val TAG = "FingerprintPay"
    private var encryptedData by prefOption("payment_pswd_encdata", nul<String>())

    private const val SPLIT_CHAR = ':'

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        MyKeyboardWindow::class.reflekt().firstMethod { name = "setInputEditText" }.hookAfter {
            if (args[0] == null) return@hookAfter

            WeLogger.i(TAG, "MyKeyboardWindow initialized, requesting biometric auth")

            val thiz = thisObject as MyKeyboardWindow
            val digitViews = MyKeyboardWindow::class.reflekt()
                .fields { type = View::class }
                .map { it.get(thisObject as MyKeyboardWindow)!! as View }

            val context = thiz.context

            val rawEncData = encryptedData ?: run {
                showToast("支付密码未设置, 指纹支付不会生效!")
                return@hookAfter
            }
            val splitRawEncData = rawEncData.split(SPLIT_CHAR)
            val encData = EncryptedData(splitRawEncData[0], splitRawEncData[1])
            decryptWithBiometric(context, encData) { plaintext ->
                showToast("支付密码解密成功!")
                for (char in plaintext) {
                    val digit = char.digitToInt()
                    digitViews[digit].performClick()
                    Thread.sleep(20)
                }
            }
        }

        FingerPrintAuthTransparentUI::class.java.hookBeforeOnCreate {
            // hide 'enable fingerprint pay' guide dialog
            val bundle = args[0] as Bundle
            bundle.putBoolean("key_show_guide", false)
        }
    }

    override fun onClick(context: ComponentActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showToast("Android 版本过低 (< Android 11), 无法使用指纹验证!")
            return
        }

        showComposeDialog(context) {
            var plaintext by remember { mutableStateOf("") }
            var visible by remember { mutableStateOf(false) }

            AlertDialogContent(
                title = { Text("指纹支付") },
                text = {
                    TextField(
                        value = plaintext,
                        onValueChange = {
                            if (it.length > 6) return@TextField
                            plaintext = it.filter { c -> c.isDigit() }
                        },
                        label = { Text("支付密码") },
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(
                                    imageVector = if (visible) MaterialSymbols.OutlinedFilled.Visibility else MaterialSymbols.OutlinedFilled.Visibility_off,
                                    contentDescription = if (visible) "Hide password" else "Show password"
                                )
                            }
                        }
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                    TextButton(onClick = {
                        val rawEncData = encryptedData ?: run {
                            showToast("支付密码未设置!")
                            return@TextButton
                        }
                        val splitRawEncData = rawEncData.split(SPLIT_CHAR)
                        val encData = EncryptedData(splitRawEncData[0], splitRawEncData[1])
                        decryptWithBiometric(context, encData) { plaintext ->
                            showToast("支付密码解密成功! 内容: ${plaintext.first()}****${plaintext.last()}")
                        }
                    }) { Text("测试解密") }
                },
                confirmButton = {
                    Button(onClick = {
                        if (plaintext.length != 6) {
                            showToast("密码长度不正确!")
                            return@Button
                        }
                        onDismiss()
                        encryptWithBiometric(context, plaintext) { encData ->
                            encryptedData = "${encData.ciphertext}${SPLIT_CHAR}${encData.iv}"
                            showToast("支付密码加密并保存成功!")
                        }
                    }) { Text("确定") }
                })
        }

    }

    private fun buildPrompt(
        activity: FragmentActivity,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit
    ): BiometricPrompt {
        val executor = ContextCompat.getMainExecutor(activity)
        return BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    activity.finish()
                    onSuccess(result)
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    showToast("验证失败! 错因: $msg")
                    if (code == BiometricPrompt.ERROR_CANCELED ||
                        code == BiometricPrompt.ERROR_USER_CANCELED
                    ) activity.finish()
                }

                override fun onAuthenticationFailed() {}
            })
    }

    @get:RequiresApi(Build.VERSION_CODES.R)
    private val promptInfo by lazy {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("验证")
            .setSubtitle("验证指纹或密码以加解密支付密码")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
    }

    // --- ENCRYPT ---
    @RequiresApi(Build.VERSION_CODES.R)
    fun encryptWithBiometric(context: Context, plaintext: String, onSuccess: (EncryptedData) -> Unit) {
        val cipher = try {
            CryptoManager.getEncryptCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            showToast("检测到新生物特征, 密钥已重置, 请在模块设置中重新加密支付密码!")
            return
        } catch (e: Exception) {
            showToast("捕获到未处理的异常! 请向模块作者报告问题")
            WeLogger.e(TAG, "unhandled exception", e)
            return
        }
        TransparentActivity.launch(context) {
            buildPrompt(this) { result ->
                val authorizedCipher = result.cryptoObject?.cipher ?: run {
                    showToast("指纹验证成功, 但无法获取密文对象! 请向模块作者报告问题")
                    return@buildPrompt
                }
                val encData = runCatching {
                    CryptoManager.encrypt(plaintext, authorizedCipher)
                }.getOrElse {
                    WeLogger.e(TAG, "failed to encrypt", it)
                    showToast(context, "加密失败! 请向模块作者报告问题")
                    return@buildPrompt
                }
                onSuccess(encData)
            }.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }
    }

    // --- DECRYPT ---
    @RequiresApi(Build.VERSION_CODES.R)
    fun decryptWithBiometric(context: Context, encryptedData: EncryptedData, onSuccess: (String) -> Unit) {
        val iv = android.util.Base64.decode(encryptedData.iv, android.util.Base64.DEFAULT)
        val cipher = try {
            CryptoManager.getDecryptCipher(iv)
        } catch (_: KeyPermanentlyInvalidatedException) {
            showToast("检测到新生物特征, 密钥已重置, 请在模块设置中重新加密支付密码!")
            return
        } catch (e: Exception) {
            showToast("捕获到未处理的异常! 请向模块作者报告问题")
            WeLogger.e(TAG, "unhandled exception", e)
            return
        }
        TransparentActivity.launch(context) {
            buildPrompt(this) { result ->
                val authorizedCipher = result.cryptoObject?.cipher ?: run {
                    showToast("指纹验证成功, 但无法获取密文对象! 请向模块作者报告问题")
                    return@buildPrompt
                }
                val plaintext = runCatching {
                    CryptoManager.decrypt(encryptedData, authorizedCipher)
                }.getOrElse {
                    WeLogger.e(TAG, "failed to decrypt", it)
                    showToast(context, "解密失败! 请向模块作者报告问题")
                    return@buildPrompt
                }
                onSuccess(plaintext)
            }.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "错误") },
                    text = {
                        Text(
                            text =
                                "Android 版本过低 (< Android 11), 无法使用指纹验证!\n" +
                                        "为追求代码简洁度与稳定性, 本项目使用 AndroidX Biometric API, 不支持 < Android 11 的设备\n" +
                                        "如确实需要此功能, 可使用第三方项目 eritpchy/FingerprintPay"
                        )
                    },
                    confirmButton = { Button(onDismiss) { Text("关闭") } }
                )
            }
            showToast("Android 版本过低 (< Android 11), 无法使用指纹验证!")
            return false
        }

        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) { Text("确定") }
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } }
                )
            }
            return false
        }

        return true
    }
}
