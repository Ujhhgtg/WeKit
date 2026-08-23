package dev.ujhhgtg.wekit.features.items.payment

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.float
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayDeque
import java.util.Locale
import java.util.WeakHashMap

object ModifyWalletBalanceDisplay : ClickableFeature(), IResolveDex {

    override val technicalId = "修改显示余额"
    override val nameRes = R.string.feature_modify_wallet_balance_display_name
    override val categoryIds = listOf(FeatureCategoryIds.PAYMENT)
    override val descriptionRes = R.string.feature_modify_wallet_balance_display_description

    private const val LEGACY_BALANCE = "fake_wallet_balance"
    private const val LEGACY_LQT = "fake_wallet_balance_lqt"
    private const val LEGACY_BUSINESS = "fake_wallet_balance_business"

    private const val KEY_BALANCE = "fake_wallet_balance_amount"
    private const val KEY_LQT = "fake_wallet_lqt_amount"
    private const val KEY_BUSINESS = "fake_wallet_business_amount"
    private const val KEY_ENABLE_BALANCE = "fake_wallet_balance_enable_balance"
    private const val KEY_ENABLE_LQT = "fake_wallet_balance_enable_lqt"
    private const val KEY_ENABLE_BUSINESS = "fake_wallet_balance_enable_business"
    private const val KEY_GLOBAL_ENABLE = "fake_wallet_balance_enable"
    private const val KEY_MODE_BALANCE = "fake_wallet_balance_mode_balance"
    private const val KEY_MODE_LQT = "fake_wallet_balance_mode_lqt"
    private const val KEY_MODE_BUSINESS = "fake_wallet_balance_mode_business"

    private const val MODE_FIXED = "fixed"
    private const val MODE_INCREASE = "increase"
    private const val MODE_DECREASE = "decrease"

    private val tickerSetTextAnimated by dexMethod {
        matcher {
            declaredClass = "com.robinhood.ticker.TickerView"
            paramTypes(BString, bool)
            returnType = "void"
            usingEqStrings("Need to call #setCharacterLists first.")
        }
    }

    private var balance by prefOption(KEY_BALANCE, "0.00")
    private var lqt by prefOption(KEY_LQT, "0.00")
    private var business by prefOption(KEY_BUSINESS, null as String?)
    private var enableBalance by prefOption(KEY_ENABLE_BALANCE, false)
    private var enableLqt by prefOption(KEY_ENABLE_LQT, false)
    private var enableBusiness by prefOption(KEY_ENABLE_BUSINESS, false)
    private var balanceMode by prefOption(KEY_MODE_BALANCE, MODE_FIXED)
    private var lqtMode by prefOption(KEY_MODE_LQT, MODE_FIXED)
    private var businessMode by prefOption(KEY_MODE_BUSINESS, MODE_FIXED)

    private val callStack = ThreadLocal.withInitial { ArrayDeque<Boolean>() }
    private val overrideState = ThreadLocal<AmountOverride?>()
    private val tickerState = WeakHashMap<View, Boolean>()
    private val amountState = WeakHashMap<View, AmountTextState>()
    private lateinit var tickerSetText: Method

    private data class AmountOverride(val target: Target, val original: String)
    private data class AmountTextState(val target: Target, val original: String, val rendered: String)

    private enum class Target {
        BALANCE, LQT, BUSINESS;

        val amount: String
            get() = when (this) {
                BALANCE -> balance
                LQT -> lqt
                BUSINESS -> if (WePrefs.default.contains(KEY_BUSINESS)) business ?: "0.00" else lqt
            }

        val mode: String
            get() = when (this) {
                BALANCE -> resolveMode(KEY_MODE_BALANCE, amount, MODE_FIXED)
                LQT -> resolveMode(KEY_MODE_LQT, amount, MODE_FIXED)
                BUSINESS -> resolveMode(
                    KEY_MODE_BUSINESS,
                    amount,
                    if (WePrefs.default.contains(KEY_BUSINESS)) MODE_FIXED
                    else resolveMode(KEY_MODE_LQT, lqt, MODE_FIXED),
                )
            }
    }

    override fun onEnable() {
        migrateLegacySettings()

        val wcClazz = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView".toClass()
        wcClazz.reflekt().methods {
                parameters { params ->
                    params.isNotEmpty() && params[0] == String::class.java
                }
            }.filter { method ->
                val params = method.parameterTypes
                params[0] == String::class.java &&
                    (method.name in setOf("setMoney", "setFirstMoney", "setNewMoney") && params.size == 1 ||
                        (params.size == 2 || params.size == 4) && params.drop(1).all { it == Boolean::class.javaPrimitiveType })
            }.forEach { method ->
                method.hookBefore {
                    if (!beginOverride(thisObject as View, args[0] as String)) {
                        val target = targetFor(thisObject as View) ?: Target.BALANCE
                        if (isEnabled(target)) {
                            val original = args[0] as String
                            setOverride(target, original)
                            args[0] = formatAmount(original, fakeAmount(target, original))
                        }
                    }
                }
                method.hookAfter { endOverride() }
            }

        val crossClazz = "com.tencent.kinda.framework.WxCrossServices".toClass()
        crossClazz.reflekt().methods {
                name = "startLqtDetailUseCaseWithBalanceInMMProcess"
                parameters { params ->
                    params.size == 2 && Context::class.java.isAssignableFrom(params[0]) &&
                        params[1] == Long::class.javaPrimitiveType
                }
                returnType(Boolean::class.javaPrimitiveType!!)
            }.forEach { method ->
                method.hookBefore {
                    if (!beginOverride(null, null) && isEnabled(Target.LQT)) {
                        val original = BigDecimal.valueOf(args[1] as Long, 2).toPlainString()
                        val rendered = fakeAmount(Target.LQT, original).toBigDecimal()
                            .movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
                        setOverride(Target.LQT, original)
                        args[1] = rendered
                    }
                }
                method.hookAfter { endOverride() }
            }

        val mallClazz = "com.tencent.mm.plugin.mall.ui.MallWalletSectionCellView".toClass()
        mallClazz.reflekt().methods {
                returnType(Void.TYPE)
                parameters { params ->
                    params.size == 7 && params[1].name == "org.json.JSONObject" &&
                        params[2] == Boolean::class.javaPrimitiveType && params[3] == String::class.java &&
                        params[4] == Boolean::class.javaPrimitiveType
                }
            }.forEach { method ->
                method.hookBefore {
                    val original = args[3] as String
                    if (!beginOverride(thisObject as? View, original)) {
                        val cell = args[0]!!.reflekt().firstField { name = "i" }.get(args[0]!!)
                        val target = when (cell) {
                            "balance_cell" -> Target.BALANCE
                            "lqt_cell" -> Target.LQT
                            else -> null
                        } ?: return@hookBefore
                        if (isEnabled(target)) {
                            val base = stableOriginal(thisObject as? View, target, original)
                            val rendered = formatAmount(base, fakeAmount(target, base))
                            rememberText(thisObject as? View, target, base, rendered)
                            setOverride(target, base)
                            args[3] = rendered
                        }
                    }
                }
                method.hookAfter { endOverride() }
            }

        val tickerClazz = "com.robinhood.ticker.TickerView".toClass()
        tickerSetText = tickerClazz.reflekt().firstMethod {
            name = "setText"
            parameters(BString)
            returnType(Void.TYPE)
        }.self
        installTickerMethod(tickerSetText)
        installTickerMethod(tickerSetTextAnimated.method)
        tickerClazz.reflekt().firstMethod {
            name = "setTextSize"
            parameters(float)
            returnType(Void.TYPE)
        }.hookBefore {
            val view = thisObject as View
            val target = targetFor(view) ?: Target.BALANCE
            if (isEnabled(target)) {
                animator(view)?.takeIf(ValueAnimator::isStarted)?.end()
                if (view.parent != null && isLqtOrBusiness(target)) tickerState[view] = true
            }
        }
    }

    private fun installTickerMethod(method: Method) {
        method.hookBefore {
            val view = thisObject as View
            val original = args[0] as String
            if (!beginOverride(view, original)) {
                val target = targetFor(view) ?: Target.BALANCE
                if (isEnabled(target)) {
                    animator(view)?.takeIf(ValueAnimator::isStarted)?.end()
                    val base = stableOriginal(view, target, original)
                    val rendered = formatAmount(base, fakeAmount(target, base))
                    rememberText(view, target, base, rendered)
                    setOverride(target, base)
                    args[0] = rendered
                    if (method.parameterTypes.size == 2) args[1] = false
                }
            }
        }
        method.hookAfter {
            endOverride()
            val view = thisObject as View
            val target = targetFor(view) ?: Target.BALANCE
            if (isEnabled(target)) {
                animator(view)?.setCurrentFraction(1.0f)
                if (isLqtOrBusiness(target) && tickerState[view] == true) {
                    val rendered = synchronized(amountState) { amountState[view]?.rendered }
                        ?: view.reflekt().firstMethod { name = "getText" }.invoke() as String
                    if (rendered.any(Char::isDigit)) {
                        tickerSetText.invoke(view, rendered)
                    }
                }
            }
        }
    }

    private fun beginOverride(view: View?, text: String?): Boolean {
        val stack = callStack.get()!!
        stack.addLast(false)
        val override = overrideState.get()
        if (override != null) {
            if (view != null && text != null && text.any(Char::isDigit)) {
                val rendered = formatAmount(text, amount(override.original).toPlainString())
                synchronized(amountState) {
                    amountState[view] = AmountTextState(override.target, override.original, rendered)
                }
            }
            return true
        }
        return false
    }

    private fun setOverride(target: Target, original: String) {
        val stack = callStack.get()!!
        stack.removeLast()
        stack.addLast(true)
        overrideState.set(AmountOverride(target, original))
    }

    private fun endOverride() {
        val stack = callStack.get()!!
        if (stack.isEmpty()) return
        if (stack.removeLast()) overrideState.remove()
        if (stack.isEmpty()) callStack.remove()
    }

    private fun isEnabled(target: Target): Boolean {
        val key = when (target) {
            Target.BALANCE -> KEY_ENABLE_BALANCE
            Target.LQT -> KEY_ENABLE_LQT
            Target.BUSINESS -> KEY_ENABLE_BUSINESS
        }
        if (!WePrefs.default.contains(key)) {
            val hasConfiguredAmount = WePrefs.default.contains(KEY_BALANCE) ||
                WePrefs.default.contains(KEY_LQT) || WePrefs.default.contains(KEY_BUSINESS) ||
                WePrefs.default.contains(LEGACY_BALANCE) || WePrefs.default.contains(LEGACY_LQT) ||
                WePrefs.default.contains(LEGACY_BUSINESS)
            return WePrefs.getBoolOrDef(KEY_GLOBAL_ENABLE, isActive && hasConfiguredAmount)
        }
        return when (target) {
            Target.BALANCE -> enableBalance
            Target.LQT -> enableLqt
            Target.BUSINESS -> enableBusiness
        }
    }

    private fun isLqtOrBusiness(target: Target) = target == Target.LQT || target == Target.BUSINESS

    private fun fakeAmount(target: Target, original: String): String {
        val configured = amount(target.amount).abs()
        val real = amount(original)
        val result = when (target.mode) {
            MODE_INCREASE -> real + configured
            MODE_DECREASE -> real - configured
            else -> configured
        }.max(BigDecimal.ZERO)
        return result.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    private fun formatAmount(text: String, replacement: String): String {
        val normalized = text.replace(" ", "")
        val start = normalized.indexOfFirst(Char::isDigit)
        if (start < 0) return if ('¥' in normalized || '￥' in normalized) normalized + replacement else replacement
        var end = start
        while (end < normalized.length && (normalized[end].isDigit() || normalized[end] == ',' || normalized[end] == '.')) end++
        return normalized.substring(0, start) + replacement + normalized.substring(end)
    }

    private fun amount(value: String): BigDecimal {
        val match = Regex("[+-]?\\d+(?:\\.\\d+)?").find(value.replace(",", ""))
        return match?.value?.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
            ?: BigDecimal.ZERO.setScale(2)
    }

    private fun normalize(value: String): String = amount(value).abs().toPlainString()

    private fun resolveMode(key: String, configured: String, fallback: String): String {
        if (WePrefs.default.contains(key)) {
            return when (WePrefs.getString(key)) {
                MODE_FIXED, MODE_INCREASE, MODE_DECREASE -> WePrefs.getString(key)!!
                else -> fallback
            }
        }
        val normalized = configured.replace(",", "").replace("¥", "").replace("￥", "").trim()
        return when {
            normalized.startsWith('+') -> MODE_INCREASE
            normalized.startsWith('-') -> MODE_DECREASE
            else -> fallback
        }
    }

    private fun targetFor(view: View): Target? {
        var current: View? = view
        repeat(8) {
            val description = current?.contentDescription?.toString()
            classify(description)?.let { return it }
            if (current is TextView) classify(current.text?.toString())?.let { return it }
            val parent = current?.parent as? View
            if (parent is ViewGroup) {
                var found: Target? = null
                scanChildren(parent, current, 0) { candidate ->
                    if (candidate == Target.BUSINESS || found == null ||
                        candidate == Target.LQT && found == Target.BALANCE
                    ) found = candidate
                }
                if (found != null) return found
            }
            current = parent
        }

        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        classify(activity?.title?.toString())?.let { return it }
        var clazz: Class<*>? = activity?.javaClass
        while (clazz != null && clazz != Activity::class.java) {
            val name = clazz.name.lowercase(Locale.US)
            if ("lqt" in name || "moneyfund" in name) return Target.LQT
            if ("walletbalancemanagerui" in name || "mallindexui" in name || "mallwallet" in name ||
                ".wallet.balance.ui." in name || ".plugin.mall.ui." in name
            ) return Target.BALANCE
            clazz = clazz.superclass
        }
        Thread.currentThread().stackTrace.forEach { element ->
            val name = element.className.lowercase(Locale.US)
            if ("lqt" in name) return Target.LQT
            if ("walletbalancemanagerui" in name || "mallindexui" in name || "mallwallet" in name) return Target.BALANCE
        }
        return null
    }

    private fun scanChildren(root: ViewGroup, excluded: View, depth: Int, found: (Target) -> Unit) {
        if (depth > 3) return
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child == excluded) continue
            classify(child.contentDescription?.toString())?.let(found)
            if (child is TextView) classify(child.text?.toString())?.let(found)
            if (child is ViewGroup) scanChildren(child, excluded, depth + 1, found)
        }
    }

    private fun classify(text: String?): Target? {
        val value = text.orEmpty().replace(Regex("\\s+"), "")
        if (value.isEmpty()) return null
        if (listOf("经营账户", "经营账号", "商户账户", "商户余额", "商家账户").any(value::contains)) return Target.BUSINESS
        if (value.contains("零钱通") || value.contains("理财通")) return Target.LQT
        if (value.contains("零钱") || value.contains("钱包余额")) return Target.BALANCE
        return null
    }

    private fun animator(view: View): ValueAnimator? =
        view.reflekt().fields { type { ValueAnimator::class.java.isAssignableFrom(it) } }
            .firstOrNull()?.get(view) as? ValueAnimator

    private fun rememberText(view: View?, target: Target, original: String, rendered: String) {
        if (view != null) synchronized(amountState) { amountState[view] = AmountTextState(target, original, rendered) }
    }

    private fun stableOriginal(view: View?, target: Target, current: String): String {
        if (view == null) return current
        synchronized(amountState) {
            val state = amountState[view]
            return if (state != null && state.target == target && amount(current).compareTo(amount(state.rendered)) == 0)
                state.original
            else current
        }
    }

    private fun migrateLegacySettings() {
        if (!WePrefs.default.contains(KEY_BALANCE) && WePrefs.default.contains(LEGACY_BALANCE))
            WePrefs.putString(KEY_BALANCE, WePrefs.getString(LEGACY_BALANCE)!!)
        if (!WePrefs.default.contains(KEY_LQT) && WePrefs.default.contains(LEGACY_LQT))
            WePrefs.putString(KEY_LQT, WePrefs.getString(LEGACY_LQT)!!)
        if (!WePrefs.default.contains(KEY_BUSINESS) && WePrefs.default.contains(LEGACY_BUSINESS))
            WePrefs.putString(KEY_BUSINESS, WePrefs.getString(LEGACY_BUSINESS)!!)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var balanceInput by remember { mutableStateOf(balance) }
            var lqtInput by remember { mutableStateOf(lqt) }
            var businessInput by remember { mutableStateOf(business ?: lqt) }
            var balanceEnabled by remember { mutableStateOf(enableBalance) }
            var lqtEnabled by remember { mutableStateOf(enableLqt) }
            var businessEnabled by remember { mutableStateOf(enableBusiness) }
            var balanceModeInput by remember { mutableStateOf(balanceMode) }
            var lqtModeInput by remember { mutableStateOf(lqtMode) }
            var businessModeInput by remember { mutableStateOf(businessMode) }
            val modes = listOf(
                DropdownOption(MODE_FIXED, stringResource(R.string.payment_wallet_balance_mode_fixed)),
                DropdownOption(MODE_INCREASE, stringResource(R.string.payment_wallet_balance_mode_increase)),
                DropdownOption(MODE_DECREASE, stringResource(R.string.payment_wallet_balance_mode_decrease)),
            )
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_modify_wallet_balance_display_name)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                SwitchWidget(
                                    title = stringResource(R.string.payment_wallet_balance_title),
                                    checked = balanceEnabled,
                                    onCheckedChange = { balanceEnabled = it },
                                )
                            }
                            item(animatedVisibility = balanceEnabled) {
                                BaseSupportingWidget(title = stringResource(R.string.payment_balance_display_amount)) {
                                    OutlinedTextField(
                                        value = balanceInput,
                                        onValueChange = { balanceInput = it },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            item(animatedVisibility = balanceEnabled) {
                                DropDownMenuWidget(
                                    title = stringResource(R.string.payment_wallet_balance_mode),
                                    description = null,
                                    value = balanceModeInput,
                                    options = modes,
                                    onValueChange = { balanceModeInput = it },
                                )
                            }
                        }

                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                SwitchWidget(
                                    title = stringResource(R.string.payment_wealth_balance_title),
                                    checked = lqtEnabled,
                                    onCheckedChange = { lqtEnabled = it },
                                )
                            }
                            item(animatedVisibility = lqtEnabled) {
                                BaseSupportingWidget(title = stringResource(R.string.payment_balance_display_amount)) {
                                    OutlinedTextField(
                                        value = lqtInput,
                                        onValueChange = { lqtInput = it },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            item(animatedVisibility = lqtEnabled) {
                                DropDownMenuWidget(
                                    title = stringResource(R.string.payment_wealth_balance_mode),
                                    description = null,
                                    value = lqtModeInput,
                                    options = modes,
                                    onValueChange = { lqtModeInput = it },
                                )
                            }
                        }

                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                SwitchWidget(
                                    title = stringResource(R.string.payment_business_balance_title),
                                    checked = businessEnabled,
                                    onCheckedChange = { businessEnabled = it },
                                )
                            }
                            item(animatedVisibility = businessEnabled) {
                                BaseSupportingWidget(title = stringResource(R.string.payment_balance_display_amount)) {
                                    OutlinedTextField(
                                        value = businessInput,
                                        onValueChange = { businessInput = it },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            item(animatedVisibility = businessEnabled) {
                                DropDownMenuWidget(
                                    title = stringResource(R.string.payment_business_balance_mode),
                                    description = null,
                                    value = businessModeInput,
                                    options = modes,
                                    onValueChange = { businessModeInput = it },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        balance = normalize(balanceInput)
                        lqt = normalize(lqtInput)
                        business = normalize(businessInput)
                        enableBalance = balanceEnabled
                        enableLqt = lqtEnabled
                        enableBusiness = businessEnabled
                        balanceMode = balanceModeInput
                        lqtMode = lqtModeInput
                        businessMode = businessModeInput
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }
}
