package dev.ujhhgtg.wekit.hooks.items.payment

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import dev.ujhhgtg.wekit.activity.StubFragmentActivity
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.CryptoManager
import dev.ujhhgtg.wekit.utils.EncryptedData
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.ToastUtils
import dev.ujhhgtg.wekit.utils.logging.WeLogger
import kotlin.time.TimeSource


@HookItem(path = "红包与支付/指纹支付", desc = "使用指纹快捷确认支付")
object FingerprintPay : ClickableHookItem() {

    private val TAG = nameof(FingerprintPay)
    private const val KEY_ENCRYPTED_DATA = "payment_pswd_encdata"

    private const val SPLIT_CHAR = ':'

    @Volatile
    private var isVerificationOngoing = false

    override fun onEnable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        listOf("com.tencent.mm.framework.app.UIPageFragmentActivity",
            "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI").forEach {
            it.toClass().asResolver()
            .apply {
                firstMethod { name = "onResume" }
                    .hookBefore { param ->
                        if (isVerificationOngoing) return@hookBefore
                        isVerificationOngoing = true

                        val activity = param.thisObject as Activity

                        val mark = TimeSource.Monotonic.markNow()
                        val root = activity.findViewById<ViewGroup>(android.R.id.content)
                        val searchedView = root.findViewByChildIndexes<ViewGroup>(0, 0, 2, 0, 2)!!
                        val myKeyboardWindow = searchedView.findViewWhich<LinearLayout> { view ->
                            view.javaClass.name == "com.tenpay.android.wechat.MyKeyboardWindow"
                        } ?: return@hookBefore
                        val digitViews = myKeyboardWindow.findViewsWhich<TextView> { it is TextView }
                        val orderedDigits = listOf(digitViews.last()) + digitViews.dropLast(1)
                        val elapsed = mark.elapsedNow()
                        WeLogger.d(TAG, "took $elapsed to locate all views")

                        val rawEncData = WePrefs.getString(KEY_ENCRYPTED_DATA) ?: run {
                            ToastUtils.showToast("支付密码未设置, 指纹支付不会生效!")
                            return@hookBefore
                        }
                        val splitRawEncData = rawEncData.split(SPLIT_CHAR)
                        val encData = EncryptedData(splitRawEncData[0], splitRawEncData[1])
                        decryptWithBiometric(encData) { plaintext ->
                            ToastUtils.showToast("支付密码解密成功!")
                            for (char in plaintext) {
                                val digit = char.digitToInt()
                                orderedDigits[digit].performClick()
                                Thread.sleep(20)
                            }
                        }
                    }

                // WxaLiteAppTransparentLiteUI inherits WxaLiteAppTransparentUI::finish()
                firstMethod { name = "finish"; superclass() }
                    .hookBefore { _ ->
                        isVerificationOngoing = false
                    }
            }
        }

        "com.tencent.mm.plugin.fingerprint.ui.FingerPrintAuthTransparentUI".toClass().asResolver()
            .firstMethod {
                name = "onCreate"
                parameters(Bundle::class)
            }
            .hookBefore { param ->
                // hide 'enable fingerprint pay' guide dialog
                val bundle = param.args[0] as Bundle
                bundle.putBoolean("key_show_guide", false)
            }
    }

    override fun onClick(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ToastUtils.showToast("Android 版本过低 (< Android 11), 无法使用指纹验证!")
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
                                    imageVector = if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (visible) "Hide password" else "Show password"
                                )
                            }
                        }
                    )
                },
                dismissButton = {
                    TextButton(dismiss) { Text("取消") }
                    TextButton(onClick = {
                        val rawEncData = WePrefs.getString(KEY_ENCRYPTED_DATA) ?: run {
                            ToastUtils.showToast("支付密码未设置!")
                            return@TextButton
                        }
                        val splitRawEncData = rawEncData.split(SPLIT_CHAR)
                        val encData = EncryptedData(splitRawEncData[0], splitRawEncData[1])
                        decryptWithBiometric(encData) { plaintext ->
                            ToastUtils.showToast("支付密码解密成功! 内容: ${plaintext.first()}****${plaintext.last()}")
                        }
                    }) { Text("测试解密") }
                },
                confirmButton = {
                    Button(onClick = {
                        if (plaintext.length != 6) {
                            ToastUtils.showToast("密码长度不正确!")
                            return@Button
                        }
                        dismiss()
                        encryptWithBiometric(plaintext) { encData ->
                            WePrefs.putString(KEY_ENCRYPTED_DATA, "${encData.ciphertext}${SPLIT_CHAR}${encData.iv}")
                            ToastUtils.showToast("支付密码加密并保存成功!")
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
                    ToastUtils.showToast("验证失败! 错因: $msg")
                    if (code == BiometricPrompt.ERROR_CANCELED ||
                        code == BiometricPrompt.ERROR_USER_CANCELED
                    ) activity.finish()
                }

                override fun onAuthenticationFailed() {}
            })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("验证")
        .setSubtitle("验证指纹或密码以加解密支付密码")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()

    // --- ENCRYPT ---
    @RequiresApi(Build.VERSION_CODES.R)
    fun encryptWithBiometric(plaintext: String, onSuccess: (EncryptedData) -> Unit) {
        val cipher = try {
            CryptoManager.getEncryptCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            ToastUtils.showToast("检测到新生物特征, 密钥已重置, 请在模块设置中重新加密支付密码!")
            return
        } catch (e: Exception) {
            ToastUtils.showToast("捕获到未处理的异常! 请向模块作者报告问题")
            WeLogger.e(TAG, "unhandled exception", e)
            return
        }
        StubFragmentActivity.launch(HostInfo.application) {
            buildPrompt(this) { result ->
                val authorizedCipher = result.cryptoObject?.cipher ?: run {
                    ToastUtils.showToast("指纹验证成功, 但无法获取密文对象! 请向模块作者报告问题")
                    return@buildPrompt
                }
                onSuccess(CryptoManager.encrypt(plaintext, authorizedCipher))
            }.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }
    }

    // --- DECRYPT ---
    @RequiresApi(Build.VERSION_CODES.R)
    fun decryptWithBiometric(encryptedData: EncryptedData, onSuccess: (String) -> Unit) {
        val iv = android.util.Base64.decode(encryptedData.iv, android.util.Base64.DEFAULT)
        val cipher = try {
            CryptoManager.getDecryptCipher(iv)
        } catch (_: KeyPermanentlyInvalidatedException) {
            ToastUtils.showToast("检测到新生物特征, 密钥已重置, 请在模块设置中重新加密支付密码!")
            return
        } catch (e: Exception) {
            ToastUtils.showToast("捕获到未处理的异常! 请向模块作者报告问题")
            WeLogger.e(TAG, "unhandled exception", e)
            return
        }
        StubFragmentActivity.launch(HostInfo.application) {
            buildPrompt(this) { result ->
                val authorizedCipher = result.cryptoObject?.cipher ?: run {
                    ToastUtils.showToast("指纹验证成功, 但无法获取密文对象! 请向模块作者报告问题")
                    return@buildPrompt
                }
                val plaintext = CryptoManager.decrypt(encryptedData, authorizedCipher)
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
                    confirmButton = { Button(dismiss) { Text("关闭") } }
                )
            }
            ToastUtils.showToast("Android 版本过低 (< Android 11), 无法使用指纹验证!")
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
                            dismiss()
                        }) { Text("确定") }
                    },
                    dismissButton = { TextButton(dismiss) { Text("取消") } }
                )
            }
            return false
        }

        return true
    }
}
