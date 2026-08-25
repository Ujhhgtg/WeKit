package dev.ujhhgtg.wekit.features.items.payment

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WePaymentApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.int
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DisplayRedPacketDetails : SwitchFeature(), IResolveDex {

    override val technicalId = "红包页面详情"
    override val nameRes = R.string.feature_display_red_packet_details_name
    override val categoryIds = listOf(FeatureCategoryIds.PAYMENT)
    override val descriptionRes = R.string.feature_display_red_packet_details_description

    private const val TAG = "DisplayRedPacketDetails"

    private val methodOnBindListView by dexMethod {
        matcher {
            usingStrings(
                "MicroMsg.LuckyMoneyDetailUI",
                "try get user contact: %s"
            )
        }
    }

    private val classNetSceneLuckyMoneyDetail by dexClass {
        matcher {
            usingStrings(
                "MicroMsg.NetSceneLuckyMoneyDetail",
                "/cgi-bin/mmpay-bin/qrydetailwxhb"
            )
        }
    }

    override fun onEnable() {
        methodOnBindListView.hookAfter {
            val holder = args[0] ?: return@hookAfter
            val record = args[1] ?: return@hookAfter

            runCatching {
                val textView = getTextViewFromHolder(holder) ?: return@runCatching
                val timestamp = getTimestampFromRecord(record) ?: return@runCatching

                val instant = Instant.ofEpochMilli(timestamp)
                val localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                val localDate = localDateTime.toLocalDate()
                val today = LocalDate.now()
                val pattern = when {
                    localDate == today -> localizedPaymentString(R.string.payment_red_packet_date_today)
                    localDate.year == today.year ->
                        localizedPaymentString(R.string.payment_red_packet_date_same_year)
                    else -> localizedPaymentString(R.string.payment_red_packet_date_other_year)
                }
                val formatter = DateTimeFormatter.ofPattern(
                    pattern,
                    Locale.forLanguageTag(WeKitLocaleController.resolvedLocale.androidTag),
                )
                textView.text = localDateTime.format(formatter)
            }.onFailure {
                WeLogger.e(TAG, "error binding red packet list item time", it)
            }
        }

        listOf(WePaymentApi.classOpenLuckyMoney, classNetSceneLuckyMoneyDetail).forEach { dexClass ->
            val method = dexClass.reflekt().firstMethod {
                name = "onGYNetEnd"
                parameters(int, BString, JSONObject::class.java)
            }

            method.hookBefore {
                val jsonObject = args[2] as? JSONObject ?: return@hookBefore
                runCatching {
                    processRedPacketJson(jsonObject)
                }.onFailure {
                    WeLogger.e(TAG, "error processing red packet json response", it)
                }
            }
        }
    }

    private fun getTextViewFromHolder(holder: Any): TextView? {
        val itemView = holder.reflekt()
            .firstField {
                type = View::class
                superclass()
            }
            .get() as? ViewGroup ?: return null
        return itemView.findViewByChildIndexes(0, 1, 1, 1, 1) as TextView?
    }

    private fun getTimestampFromRecord(record: Any): Long? {
        val fields = record.javaClass.declaredFields
        if (fields.isEmpty()) return null
        val field = fields.first()
        field.isAccessible = true
        val innerObj = field.get(record) ?: return null

        val strFields = innerObj.javaClass.declaredFields.filter { it.type == String::class.java }
        for (f in strFields) {
            f.isAccessible = true
            val str = f.get(innerObj) as? String ?: continue
            if (str.length == 10 && str.all { it.isDigit() }) {
                return str.toLongOrNull()?.times(1000)
            }
        }
        return null
    }

    private fun processRedPacketJson(jsonObject: JSONObject) {
//        if (Math.random() > 0.65) {
//            val names = listOf("Hd", "久雾", "豆子", "拖鞋")
//            jsonObject.put("changeWording", "已存入${names.random()}的余额(WA)")
//        }

        val totalAmount = jsonObject.optInt("totalAmount", 0)
        val totalNum = jsonObject.optInt("totalNum", 0)
        val recNum = jsonObject.optInt("recNum", 0)
        val recAmount = jsonObject.optInt("recAmount", 0)

        val sb = StringBuilder()
        sb.append(
            localizedPaymentString(
                R.string.payment_red_packet_amount_details,
                recAmount / 100.0,
                totalAmount / 100.0,
            )
        ).append('\n')
        sb.append(
            localizedPaymentString(R.string.payment_red_packet_count_details, recNum, totalNum)
        ).append('\n')

        val remaining = (totalAmount - recAmount) / 100.0
        if (remaining > 0.0) {
            sb.append(localizedPaymentString(R.string.payment_red_packet_remaining, remaining)).append('\n')
        }

        jsonObject.put("headTitle", sb.toString())
    }
}
