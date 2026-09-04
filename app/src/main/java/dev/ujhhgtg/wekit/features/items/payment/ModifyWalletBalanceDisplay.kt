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
import dev.ujhhgtg.reflekt.utils.isSubclassOf
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
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.math.DecimalExpression
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.float
import dev.ujhhgtg.wekit.utils.reflection.long
import dev.ujhhgtg.wekit.utils.reflection.void
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

    private const val KEY_EXPRESSION_BALANCE = "fake_wallet_balance_expression_balance"
    private const val KEY_EXPRESSION_LQT = "fake_wallet_balance_expression_lqt"
    private const val KEY_EXPRESSION_BUSINESS = "fake_wallet_balance_expression_business"

    private const val MODE_FIXED = "fixed"
    private const val MODE_INCREASE = "increase"
    private const val MODE_DECREASE = "decrease"
    private const val VALUE_VARIABLE = "value"
    private const val TAG = "ModifyWalletBalanceDisplay"

    private val tickerSetTextAnimated by dexMethod {
        matcher {
            declaredClass = "com.robinhood.ticker.TickerView"
            paramTypes(BString, bool)
            returnType = "void"
            usingEqStrings("Need to call #setCharacterLists first.")
        }
    }

    private var balanceExpression by prefOption(KEY_EXPRESSION_BALANCE, "value")
    private var lqtExpression by prefOption(KEY_EXPRESSION_LQT, "value")
    private var businessExpression by prefOption(KEY_EXPRESSION_BUSINESS, "value")
    private var enableBalance by prefOption(KEY_ENABLE_BALANCE, false)
    private var enableLqt by prefOption(KEY_ENABLE_LQT, false)
    private var enableBusiness by prefOption(KEY_ENABLE_BUSINESS, false)

    private val callStack = ThreadLocal.withInitial { ArrayDeque<Boolean>() }
    private val overrideState = ThreadLocal<AmountOverride?>()
    private val tickerState = WeakHashMap<View, Boolean>()
    private val amountState = WeakHashMap<View, AmountTextState>()
    private val expressionCache = mutableMapOf<Target, Pair<String, DecimalExpression>>()
    private lateinit var tickerSetText: Method

    private data class AmountOverride(val target: Target, val original: String)
    private data class AmountTextState(val target: Target, val original: String, val rendered: String)

    private enum class Target {
        BALANCE, LQT, BUSINESS;

        val expression: String
            get() = when (this) {
                BALANCE -> balanceExpression
                LQT -> lqtExpression
                BUSINESS -> if (WePrefs.default.contains(KEY_EXPRESSION_BUSINESS)) {
                    businessExpression
                } else {
                    lqtExpression
                }
            }
    }

    override fun onEnable() {
        migrateLegacySettings()

        val wcClazz = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView".toClass()
        wcClazz.reflekt().methods {
            parameters { params ->
                params.isNotEmpty() && params[0] == BString
            }
        }.filter { method ->
            val params = method.parameterTypes
            params[0] == BString &&
                (method.name in setOf("setMoney", "setFirstMoney", "setNewMoney") && params.size == 1 ||
                    (params.size == 2 || params.size == 4) && params.drop(1).all { it == bool })
        }.forEach { method ->
            method.hookBefore {
                if (!beginOverride(thisObject as View, args[0] as String)) {
                    val view = thisObject as View
                    val target = targetFor(view) ?: Target.BALANCE
                    val tickerReady = !isLqtOrBusiness(target) ||
                        findTickerView(view)?.let { tickerState[it] == true } == true
                    if (tickerReady && isEnabled(target)) {
                        val original = args[0] as String
                        evaluateAmount(target, original)?.let { replacement ->
                            setOverride(target, original)
                            args[0] = formatAmount(original, replacement)
                        }
                    }
                }
            }
            method.hookAfter { endOverride() }
        }

        val crossClazz = "com.tencent.kinda.framework.WxCrossServices".toClass()
        crossClazz.reflekt().methods {
            name = "startLqtDetailUseCaseWithBalanceInMMProcess"
            parameters { params ->
                params.size == 2 && params[0] isSubclassOf Context::class &&
                    params[1] == long
            }
            returnType(bool)
        }.forEach { method ->
            method.hookBefore {
                if (!beginOverride(null, null) && isEnabled(Target.LQT)) {
                    val original = BigDecimal.valueOf(args[1] as Long, 2).toPlainString()
                    evaluateAmount(Target.LQT, original)?.let { replacement ->
                        runCatching {
                            replacement.toBigDecimal().movePointRight(2)
                                .setScale(0, RoundingMode.HALF_UP).longValueExact()
                        }.onFailure { error ->
                            logExpressionError(Target.LQT, original, error)
                        }.getOrNull()?.let { rendered ->
                            setOverride(Target.LQT, original)
                            args[1] = rendered
                        }
                    }
                }
            }
            method.hookAfter { endOverride() }
        }

        val mallClazz = "com.tencent.mm.plugin.mall.ui.MallWalletSectionCellView".toClass()
        mallClazz.reflekt().methods {
            returnType(void)
            parameters { params ->
                params.size == 7 && params[1].name == "org.json.JSONObject" &&
                    params[2] == bool && params[3] == BString && params[4] == bool
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
                        evaluateAmount(target, base)?.let { replacement ->
                            val rendered = formatAmount(base, replacement)
                            rememberText(thisObject as? View, target, base, rendered)
                            setOverride(target, base)
                            args[3] = rendered
                        }
                    }
                }
            }
            method.hookAfter { endOverride() }
        }

        val tickerClazz = "com.robinhood.ticker.TickerView".toClass()
        tickerSetText = tickerClazz.reflekt().firstMethod {
            name = "setText"
            parameters(BString)
            returnType(void)
        }.self
        installTickerMethod(tickerSetText)
        installTickerMethod(tickerSetTextAnimated.method)
        tickerClazz.reflekt().firstMethod {
            name = "setTextSize"
            parameters(float)
            returnType(void)
        }.apply {
            hookBefore {
                val view = thisObject as View
                val target = targetFor(view) ?: Target.BALANCE
                if (isEnabled(target)) {
                    animator(view)?.takeIf(ValueAnimator::isStarted)?.end()
                    if (view.parent != null && isLqtOrBusiness(target)) tickerState[view] = true
                }
            }
            hookAfter {
                val view = thisObject as View
                val target = targetFor(view) ?: Target.BALANCE
                if (isEnabled(target)) {
                    animator(view)?.setCurrentFraction(1.0f)
                    if (isLqtOrBusiness(target) && tickerState[view] == true) {
                        val original = synchronized(amountState) { amountState[view]?.original }
                            ?: view.reflekt().firstMethod { name = "getText" }.invoke() as String
                        if (original.any(Char::isDigit)) tickerSetText.invoke(view, original)
                    }
                }
            }
        }
    }

    private fun installTickerMethod(method: Method) {
        method.hookBefore {
            val view = thisObject as View
            val original = args[0] as String
            if (!beginOverride(view, original)) {
                val target = targetFor(view) ?: Target.BALANCE
                if ((!isLqtOrBusiness(target) || tickerState[view] == true) && isEnabled(target)) {
                    animator(view)?.takeIf(ValueAnimator::isStarted)?.end()
                    val base = stableOriginal(view, target, original)
                    evaluateAmount(target, base)?.let { replacement ->
                        val rendered = formatAmount(base, replacement)
                        rememberText(view, target, base, rendered)
                        setOverride(target, base)
                        args[0] = rendered
                        if (method.parameterTypes.size == 2) args[1] = false
                    }
                }
            }
        }
        method.hookAfter { endOverride() }
    }

    private fun beginOverride(view: View?, text: String?): Boolean {
        val stack = callStack.get()!!
        stack.addLast(false)
        val override = overrideState.get()
        if (override != null) {
            if (view != null && text != null && text.any(Char::isDigit)) {
                val original = formatAmount(text, amount(override.original).toPlainString())
                synchronized(amountState) {
                    amountState[view] = AmountTextState(override.target, original, text)
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

    private fun evaluateAmount(target: Target, original: String): String? {
        val expression = target.expression
        return runCatching {
            val compiled = synchronized(expressionCache) {
                expressionCache[target]?.takeIf { it.first == expression }?.second
                    ?: DecimalExpression.parse(expression, setOf(VALUE_VARIABLE)).also {
                        expressionCache[target] = expression to it
                    }
            }
            compiled.evaluate(mapOf(VALUE_VARIABLE to amount(original)))
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString()
        }.onFailure { error ->
            logExpressionError(target, original, error)
        }.getOrNull()
    }

    private fun logExpressionError(target: Target, original: String, error: Throwable) {
        WeLogger.e(
            TAG,
            "failed to evaluate ${target.name.lowercase(Locale.US)} expression " +
                "'${target.expression}' with value '${amount(original).toPlainString()}'; keeping original amount",
            error,
        )
    }

    private fun formatAmount(text: String, replacement: String): String {
        val normalized = text.trim()
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

    private fun findTickerView(view: View): View? {
        if (view.javaClass.name == "com.robinhood.ticker.TickerView") return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTickerView(view.getChildAt(index))?.let { return it }
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
        view.reflekt().fields { type { it isSubclassOf ValueAnimator::class } }
            .firstOrNull()?.get(view) as? ValueAnimator

    private fun rememberText(view: View?, target: Target, original: String, rendered: String) {
        if (view != null) synchronized(amountState) { amountState[view] = AmountTextState(target, original, rendered) }
    }

    private fun stableOriginal(view: View?, target: Target, current: String): String {
        if (view == null) return current
        synchronized(amountState) {
            val state = amountState[view]
            return if (state != null && state.target == target && current.any(Char::isDigit) &&
                state.rendered.any(Char::isDigit) && amount(current).compareTo(amount(state.rendered)) == 0
            )
                formatAmount(current, amount(state.original).toPlainString())
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

        var migrated = false
        if (shouldMigrateWalletExpression(
                hasExpression = WePrefs.default.contains(KEY_EXPRESSION_BALANCE),
                hasLegacyAmount = WePrefs.default.contains(KEY_BALANCE),
                hasLegacyMode = WePrefs.default.contains(KEY_MODE_BALANCE),
            )
        ) {
            val configured = WePrefs.getStringOrDef(KEY_BALANCE, "0.00")
            val mode = resolveMode(KEY_MODE_BALANCE, configured, MODE_FIXED)
            WePrefs.putString(KEY_EXPRESSION_BALANCE, migrateExpression(configured, mode))
            migrated = true
        }
        if (shouldMigrateWalletExpression(
                hasExpression = WePrefs.default.contains(KEY_EXPRESSION_LQT),
                hasLegacyAmount = WePrefs.default.contains(KEY_LQT),
                hasLegacyMode = WePrefs.default.contains(KEY_MODE_LQT),
            )
        ) {
            val configured = WePrefs.getStringOrDef(KEY_LQT, "0.00")
            val mode = resolveMode(KEY_MODE_LQT, configured, MODE_FIXED)
            WePrefs.putString(KEY_EXPRESSION_LQT, migrateExpression(configured, mode))
            migrated = true
        }
        if (shouldMigrateWalletExpression(
                hasExpression = WePrefs.default.contains(KEY_EXPRESSION_BUSINESS),
                hasLegacyAmount = WePrefs.default.contains(KEY_BUSINESS),
                hasLegacyMode = WePrefs.default.contains(KEY_MODE_BUSINESS),
            )
        ) {
            val lqtConfigured = WePrefs.getStringOrDef(KEY_LQT, "0.00")
            val configured = WePrefs.getString(KEY_BUSINESS) ?: lqtConfigured
            val lqtMode = resolveMode(KEY_MODE_LQT, lqtConfigured, MODE_FIXED)
            val mode = resolveMode(KEY_MODE_BUSINESS, configured, lqtMode)
            WePrefs.putString(KEY_EXPRESSION_BUSINESS, migrateExpression(configured, mode))
            migrated = true
        }
        if (migrated) WeLogger.i(TAG, "migrated legacy wallet balance settings to expressions")
    }

    private fun migrateExpression(configured: String, mode: String): String {
        return migrateWalletBalanceExpression(configured, mode)
    }

    private fun expressionError(expression: String): String? = runCatching {
        DecimalExpression.parse(expression, setOf(VALUE_VARIABLE))
    }.exceptionOrNull()?.message

    override fun onClick(context: ComponentActivity) {
        migrateLegacySettings()
        showComposeDialog(context) {
            var balanceInput by remember { mutableStateOf(balanceExpression) }
            var lqtInput by remember { mutableStateOf(lqtExpression) }
            var businessInput by remember { mutableStateOf(businessExpression) }
            var balanceEnabled by remember { mutableStateOf(enableBalance) }
            var lqtEnabled by remember { mutableStateOf(enableLqt) }
            var businessEnabled by remember { mutableStateOf(enableBusiness) }
            val balanceError = if (balanceEnabled) expressionError(balanceInput) else null
            val lqtError = if (lqtEnabled) expressionError(lqtInput) else null
            val businessError = if (businessEnabled) expressionError(businessInput) else null
            val expressionHint = stringResource(R.string.payment_wallet_balance_expression_hint)
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
                                BaseSupportingWidget(title = stringResource(R.string.payment_wallet_balance_expression)) {
                                    OutlinedTextField(
                                        value = balanceInput,
                                        onValueChange = { balanceInput = it },
                                        supportingText = { Text(balanceError ?: expressionHint) },
                                        isError = balanceError != null,
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
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
                                BaseSupportingWidget(title = stringResource(R.string.payment_wallet_balance_expression)) {
                                    OutlinedTextField(
                                        value = lqtInput,
                                        onValueChange = { lqtInput = it },
                                        supportingText = { Text(lqtError ?: expressionHint) },
                                        isError = lqtError != null,
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
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
                                BaseSupportingWidget(title = stringResource(R.string.payment_wallet_balance_expression)) {
                                    OutlinedTextField(
                                        value = businessInput,
                                        onValueChange = { businessInput = it },
                                        supportingText = { Text(businessError ?: expressionHint) },
                                        isError = businessError != null,
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = balanceError == null && lqtError == null && businessError == null,
                        onClick = {
                            balanceExpression = balanceInput.trim()
                            lqtExpression = lqtInput.trim()
                            businessExpression = businessInput.trim()
                            enableBalance = balanceEnabled
                            enableLqt = lqtEnabled
                            enableBusiness = businessEnabled
                            synchronized(expressionCache) { expressionCache.clear() }
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }
}

internal fun migrateWalletBalanceExpression(configured: String, mode: String): String {
    val operand = Regex("[+-]?\\d+(?:\\.\\d+)?")
        .find(configured.replace(",", ""))
        ?.value
        ?.toBigDecimalOrNull()
        ?.setScale(2, RoundingMode.HALF_UP)
        ?.abs()
        ?: BigDecimal.ZERO.setScale(2)
    val normalized = operand.stripTrailingZeros().toPlainString()
    return when (mode) {
        "increase" -> "value + $normalized"
        "decrease" -> "value - $normalized"
        else -> normalized
    }
}

internal fun shouldMigrateWalletExpression(
    hasExpression: Boolean,
    hasLegacyAmount: Boolean,
    hasLegacyMode: Boolean,
): Boolean = !hasExpression && (hasLegacyAmount || hasLegacyMode)
