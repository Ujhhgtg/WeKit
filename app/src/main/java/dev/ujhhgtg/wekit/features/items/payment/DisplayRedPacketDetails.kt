package dev.ujhhgtg.wekit.features.items.payment

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
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

@Feature(name = "红包页面详情", categories = ["支付", "红包"], description = "红包领取页面显示更加详细的领取情况")
object DisplayRedPacketDetails : SwitchFeature(), IResolveDex {

    private val TAG = This.Class.simpleName

    private val methodOnBindListView by dexMethod {
        matcher {
            usingStrings(
                "MicroMsg.LuckyMoneyDetailUI",
                "try get user contact: %s"
            )
        }
    }

    private val classNetSceneOpenLuckyMoney by dexClass {
        matcher {
            usingStrings(
                "MicroMsg.NetSceneOpenLuckyMoney",
                "/cgi-bin/mmpay-bin/openwxhb"
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
                    localDate == today -> "HH:mm:ss"
                    localDate.year == today.year -> "M月d日 HH:mm:ss"
                    else -> "yyyy年M月d日 HH:mm:ss"
                }
                val formatter = DateTimeFormatter.ofPattern(pattern)
                textView.text = localDateTime.format(formatter)
            }.onFailure {
                WeLogger.e(TAG, "error binding red packet list item time", it)
            }
        }

        listOf(classNetSceneOpenLuckyMoney, classNetSceneLuckyMoneyDetail).forEach { dexClass ->
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
        return itemView.findViewByChildIndexes(0, 1, 1, 1, 1)
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
        sb.append("金额:").append(recAmount / 100.0).append('/').append(totalAmount / 100.0).append("元\n")
        sb.append("数量:").append(recNum).append('/').append(totalNum).append('\n')

        val remaining = (totalAmount - recAmount) / 100.0
        if (remaining > 0.0) {
            sb.append("剩余:").append(remaining).append("元\n")
        }

        jsonObject.put("headTitle", sb.toString())
    }
}
