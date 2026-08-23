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
import androidx.compose.ui.res.stringResource
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
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.core.ClickableFeature
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


object FingerprintPay : ClickableFeature() {

    override val technicalId = "指纹支付"
    override val nameRes = R.string.feature_fingerprint_pay_name
    override val categoryIds = listOf(FeatureCategoryIds.PAYMENT)
    override val descriptionRes = R.string.feature_fingerprint_pay_description

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
                showToast(localizedPaymentString(R.string.payment_fingerprint_password_not_configured))
                return@hookAfter
            }
            val splitRawEncData = rawEncData.split(SPLIT_CHAR)
            val encData = EncryptedData(splitRawEncData[0], splitRawEncData[1])
            decryptWithBiometric(context, encData) { plaintext ->
                showToast(localizedPaymentString(R.string.payment_fingerprint_decrypted))
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
            showToast(localizedPaymentString(R.string.payment_fingerprint_android_too_old))
            return
        }

        showComposeDialog(context) {
            var plaintext by remember { mutableStateOf("") }
            var visible by remember { mutableStateOf(false) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_fingerprint_pay_name)) },
                text = {
                    TextField(
                        value = plaintext,
                        onValueChange = {
                            if (it.length > 6) return@TextField
                            plaintext = it.filter { c -> c.isDigit() }
                        },
                        label = { Text(stringResource(R.string.payment_fingerprint_password)) },
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(
                                    imageVector = if (visible) MaterialSymbols.OutlinedFilled.Visibility else MaterialSymbols.OutlinedFilled.Visibility_off,
                                    contentDescription = stringResource(
                                        if (visible) R.string.hide_password else R.string.show_password
                                    )
                                )
                            }
                        }
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                    TextButton(onClick = {
                        val rawEncData = encryptedData ?: run {
                            showToast(localizedPaymentString(R.string.payment_fingerprint_password_missing))
                            return@TextButton
                        }
                        val splitRawEncData = rawEncData.split(SPLIT_CHAR)
                        val encData = EncryptedData(splitRawEncData[0], splitRawEncData[1])
                        decryptWithBiometric(context, encData) { plaintext ->
                            showToast(
                                localizedPaymentString(
                                    R.string.payment_fingerprint_decrypted_preview,
                                    plaintext.first(),
                                    plaintext.last(),
                                )
                            )
                        }
                    }) { Text(stringResource(R.string.payment_fingerprint_test_decryption)) }
                },
                confirmButton = {
                    Button(onClick = {
                        if (plaintext.length != 6) {
                            showToast(localizedPaymentString(R.string.payment_fingerprint_invalid_length))
                            return@Button
                        }
                        onDismiss()
                        encryptWithBiometric(context, plaintext) { encData ->
                            encryptedData = "${encData.ciphertext}${SPLIT_CHAR}${encData.iv}"
                            showToast(localizedPaymentString(R.string.payment_fingerprint_saved))
                        }
                    }) { Text(stringResource(R.string.dialog_confirm)) }
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
                    showToast(localizedPaymentString(R.string.payment_fingerprint_auth_failed, msg))
                    if (code == BiometricPrompt.ERROR_CANCELED ||
                        code == BiometricPrompt.ERROR_USER_CANCELED
                    ) activity.finish()
                }

                override fun onAuthenticationFailed() {}
            })
    }

    @get:RequiresApi(Build.VERSION_CODES.R)
    private val promptInfo: BiometricPrompt.PromptInfo
        get() =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(localizedPaymentString(R.string.payment_fingerprint_verify_title))
            .setSubtitle(localizedPaymentString(R.string.payment_fingerprint_verify_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

    // --- ENCRYPT ---
    @RequiresApi(Build.VERSION_CODES.R)
    fun encryptWithBiometric(context: Context, plaintext: String, onSuccess: (EncryptedData) -> Unit) {
        val cipher = try {
            CryptoManager.getEncryptCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            showToast(localizedPaymentString(R.string.payment_fingerprint_key_reset))
            return
        } catch (e: Exception) {
            showToast(localizedPaymentString(R.string.payment_fingerprint_unhandled_error))
            WeLogger.e(TAG, "unhandled exception", e)
            return
        }
        TransparentActivity.launch(context) {
            buildPrompt(this) { result ->
                val authorizedCipher = result.cryptoObject?.cipher ?: run {
                    showToast(localizedPaymentString(R.string.payment_fingerprint_missing_cipher))
                    return@buildPrompt
                }
                val encData = runCatching {
                    CryptoManager.encrypt(plaintext, authorizedCipher)
                }.getOrElse {
                    WeLogger.e(TAG, "failed to encrypt", it)
                    showToast(context, context.localizedPaymentString(R.string.payment_fingerprint_encrypt_failed))
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
            showToast(localizedPaymentString(R.string.payment_fingerprint_key_reset))
            return
        } catch (e: Exception) {
            showToast(localizedPaymentString(R.string.payment_fingerprint_unhandled_error))
            WeLogger.e(TAG, "unhandled exception", e)
            return
        }
        TransparentActivity.launch(context) {
            buildPrompt(this) { result ->
                val authorizedCipher = result.cryptoObject?.cipher ?: run {
                    showToast(localizedPaymentString(R.string.payment_fingerprint_missing_cipher))
                    return@buildPrompt
                }
                val plaintext = runCatching {
                    CryptoManager.decrypt(encryptedData, authorizedCipher)
                }.getOrElse {
                    WeLogger.e(TAG, "failed to decrypt", it)
                    showToast(context, context.localizedPaymentString(R.string.payment_fingerprint_decrypt_failed))
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
                    title = { Text(text = stringResource(R.string.error)) },
                    text = {
                        Text(
                            text = stringResource(R.string.payment_fingerprint_android_too_old_details)
                        )
                    },
                    confirmButton = { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
                )
            }
            showToast(localizedPaymentString(R.string.payment_fingerprint_android_too_old))
            return false
        }

        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = stringResource(R.string.warning)) },
                    text = { Text(text = stringResource(R.string.payment_risk_warning)) },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) { Text(stringResource(R.string.dialog_confirm)) }
                    },
                    dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
                )
            }
            return false
        }

        return true
    }
}
