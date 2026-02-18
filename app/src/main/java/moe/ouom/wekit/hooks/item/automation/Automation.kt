package moe.ouom.wekit.hooks.item.automation

import android.content.ContentValues
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.api.WeDatabaseListener
import moe.ouom.wekit.hooks.sdk.protocol.intf.IWePkgInterceptor
import moe.ouom.wekit.ui.compose.showComposeDialog
import moe.ouom.wekit.ui.creator.dialog.hooks.BaseHooksSettingsDialog
import moe.ouom.wekit.util.WeProtoData
import moe.ouom.wekit.util.log.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "自动化/自动化引擎", desc = "点击管理自动化规则")
class AutomationRuleManager : BaseClickableFunctionHookItem(),
    WeDatabaseListener.DatabaseInsertListener,
    IWePkgInterceptor
{
    companion object {
        private const val TAG = "AutomationRuleManager"

        // type=1 plain text
        // type=3 picture
        // type=43 video
        // type=48 static location
        // type=49 external app share
        // type=50 video/audio-only call
        // type=419430449 cash transfer
        // type=436207665 red packet
        // type=1040187441 qq music
        // type=1090519089 file
        val rules = CopyOnWriteArrayList(
            listOf(
                AutomationRule(
                    id = 0,
                    name = "bot_commands",
                    script = """
                        function getCleanContent(content) {
                            // Remove "wxid_xxx:\n" prefix in group chats
                            var match = content.match(/^wxid_[^:]+:\n(.*)$/s);
                            if (match) {
                                return match[1];
                            }
                            return content;
                        }
                        
                        function commandWeather(content) {
                            log.i("fetching weather...");
                            
                            var cityName = content.substring(8).trim();
                            
                            // Default to Shanghai if no city specified
                            if (cityName === "") {
                                cityName = "上海";
                            }
                            
                            log.i("querying weather for:", cityName);
                            
                            // City code mapping (you can expand this)
                            var cityCodeMap = {
                                "北京": "101010100",
                                "上海": "101020100",
                                "广州": "101280101",
                                "深圳": "101280601",
                                "杭州": "101210101",
                                "成都": "101270101",
                                "武汉": "101200101",
                                "西安": "101110101",
                                "重庆": "101040100",
                                "天津": "101030100",
                                "南京": "101190101",
                                "苏州": "101190401",
                                "郑州": "101180101",
                                "长沙": "101250101",
                                "沈阳": "101070101",
                                "青岛": "101120201",
                                "厦门": "101230201",
                                "大连": "101070201",
                                "济南": "101120101",
                                "哈尔滨": "101050101"
                            };
                            
                            var cityCode = cityCodeMap[cityName];
                            
                            if (!cityCode) {
                                log.w("city not found in map:", cityName);
                                return "抱歉，暂不支持查询该城市天气。\n支持的城市：" + Object.keys(cityCodeMap).join("、");
                            }
                            
                            // Make request to Xiaomi Weather API
                            var response = http.get("https://weatherapi.market.xiaomi.com/wtr-v3/weather/all", {
                                latitude: "0",
                                longitude: "0",
                                locationKey: "weathercn:" + cityCode,
                                sign: "zUFJoAR2ZVrDy1vF3D07",
                                isGlobal: "false",
                                locale: "zh_cn",
                                days: "1",
                                appKey: "weather20151024"
                            });
                            
                            log.i("api response status:", response.status);
                            
                            if (!response.ok) {
                                log.e("weather api request failed");
                                log.e("status:", response.status);
                                log.e("error:", response.error);
                                return "天气查询失败，请稍后重试";
                            }
                            
                            if (!response.json) {
                                log.e("response is not json");
                                log.e("body:", response.body);
                                return "天气数据解析失败";
                            }
                            
                            var data = response.json;
                            log.d("full response:", JSON.stringify(data));
                            
                            // Check if current weather data exists
                            if (!data.current) {
                                log.e("no current weather data in response");
                                return "未获取到天气数据";
                            }
                            
                            var current = data.current;
                            
                            // Weather code to description mapping
                            var weatherMap = {
                                "0": "晴",
                                "1": "多云",
                                "2": "阴",
                                "3": "阵雨",
                                "4": "雷阵雨",
                                "5": "雷阵雨伴有冰雹",
                                "6": "雨夹雪",
                                "7": "小雨",
                                "8": "中雨",
                                "9": "大雨",
                                "10": "暴雨",
                                "11": "大暴雨",
                                "12": "特大暴雨",
                                "13": "阵雪",
                                "14": "小雪",
                                "15": "中雪",
                                "16": "大雪",
                                "17": "暴雪",
                                "18": "雾",
                                "19": "冻雨",
                                "20": "沙尘暴",
                                "21": "小到中雨",
                                "22": "中到大雨",
                                "23": "大到暴雨",
                                "24": "暴雨到大暴雨",
                                "25": "大暴雨到特大暴雨",
                                "26": "小到中雪",
                                "27": "中到大雪",
                                "28": "大到暴雪",
                                "29": "浮尘",
                                "30": "扬沙",
                                "31": "强沙尘暴",
                                "32": "霾",
                                "53": "霾"
                            };
                            
                            var weatherDesc = weatherMap[current.weather] || "未知";
                            var temperature = current.temperature.value + current.temperature.unit;
                            var feelsLike = current.feelsLike.value + current.feelsLike.unit;
                            var humidity = current.humidity.value + current.humidity.unit;
                            var pressure = current.pressure.value + current.pressure.unit;
                            var windSpeed = current.wind.speed.value + current.wind.speed.unit;
                            var windDir = current.wind.direction.value + current.wind.direction.unit;
                            var uvIndex = current.uvIndex;
                            
                            log.i("weather parsed successfully for", cityName);
                            
                            // Format response message
                            var message = "📍 " + cityName + " 天气\n" +
                                         "━━━━━━━━━━━━\n" +
                                         "🌡️ 温度：" + temperature + "\n" +
                                         "🤚 体感：" + feelsLike + "\n" +
                                         "☁️ 天气：" + weatherDesc + "\n" +
                                         "💧 湿度：" + humidity + "\n" +
                                         "🎐 气压：" + pressure + "\n" +
                                         "💨 风速：" + windSpeed + "\n" +
                                         "🧭 风向：" + windDir + "\n" +
                                         "☀️ 紫外线：" + uvIndex + "\n" +
                                         "━━━━━━━━━━━━\n" +
                                         "⏰ 更新时间：" + current.pubTime;
                            
                            return message;
                        }
                        
                        function commandRandomPic(content) {
                            log.i("fetching random picture...");
                            var sourceName = content.substring(11).trim();
                            if (sourceName === "") {
                                sourceName = "alcy";
                            }
                            
                            log.d("sourceName=" + sourceName);
                            
                            if (sourceName === "alcy") {
                                log.i("fetching random picture from Alcy...");
                                
                                var response = http.get("https://t.alcy.cc/ysz", {
                                    json: "",
                                    quantity: "1"
                                });
                                
                                log.i("api response status:", response.status);
                            
                                if (!response.ok) {
                                    log.e("pic api request failed");
                                    log.e("status:", response.status);
                                    log.e("error:", response.error);
                                    replyText("图片获取失败，请稍后重试");
                                }
                                
                                var url = response.body.trim();
                                var result = http.download(url);
                                
                                if (!result.ok) {
                                    log.e("failed to download picture");
                                    replyText("图片下载失败，请稍后重试");
                                }
                                
                                replyImage(result.path);
                            }
                            else {
                                replyText("暂不支持当前来源，请等待开发者实现喵");
                            }
                        }
                        
                        function commandHitokoto(content) {
                            log.i("fetching sentence from hitokoto v1 api...");
                            var response = http.get("https://v1.hitokoto.cn/");
                            
                            if (!response.ok) {
                                log.e("hitokoto api request failed");
                                log.e("status:", response.status);
                                log.e("error:", response.error);
                                replyText("一言获取失败，请稍后重试");
                            }
                            
                            if (!response.json) {
                                log.e("response is not json");
                                log.e("body:", response.body);
                                return "一言数据解析失败";
                            }
                            
                            var data = response.json;
                            log.d("full response:", JSON.stringify(data));
                            
                            // Format response message
                            if (data.from_who) {
                                var message = "『" + data.hitokoto + "』\n" + "        —— " + data.from_who + "「" + data.from + "」";
                            }
                            else {
                                var message = "『" + data.hitokoto + "』\n" + "        —— " + "「" + data.from + "」";
                            }
                            
                            return message;
                        }
                        
                        // when using onMessage, you can mix and use send*() and reply*() method and return value at the same time
                        // you can replyText() and return null, that is ok, and have the same effect as returning the text directly
                        function onMessage(talker, content, type, isSend) {
                            log.i("onMessage() triggered");
                            
                            content = getCleanContent(content);
                            
                            if (content === "/help") {
                                return "可用命令:\n/weather (<城市; 默认为上海>)\n/help\n/random-pic (<来源; 默认为 alcy; 可选项: alcy,yande.re,konachan,zerochan,danbooru,gelbooru,waifu.im,wallhaven>)\n/hitokoto";
                            }
                            
                            if (content.startsWith("/weather")) {
                                return commandWeather(content);
                            }
                            
                            if (content.startsWith("/random-pic")) {
                                commandRandomPic(content);
                                return null;
                            }
                            
                            if (content.startsWith("/hitokoto")) {
                                return commandHitokoto(content);
                            }
                            
                            return null;
                        }
                        
                        // when using onRequest, you must return the json object
                        // there's no helper methods like "sendJson()"
                        // function onRequest(uri, cgiId, json) {
                        //     // do something with the json
                        //     json.someField = "someValue";
                        //     return json;
                        // }
                        
                        // when using onResponse, you must return the json object
                        // there's no helper methods like "sendJson()"
                        // function onResponse(uri, cgiId, json) {
                        //     // do something with the json
                        //     json.someField = "someValue";
                        //     return json;
                        // }
                    """.trimIndent(),
                    enabled = true
                )
            )
        )
    }

    // --- ui ---
    override fun onClick(context: Context?) {
        if (context == null) return
        showComposeDialog(context) { onDismiss ->
            BaseHooksSettingsDialog("管理规则", onDismiss) {
                AutomationSettingsDialogContent(rules)
            }
        }
    }

    override fun entry(classLoader: ClassLoader) {
        WeLogger.i(TAG, "registering automation DB listener")
        WeDatabaseListener.addListener(this)
    }

    // --- onMessage ---
    override fun onInsert(table: String, values: ContentValues) {
        if (!isEnabled) return
        if (!OnMessage.enabled) {
            WeLogger.i(TAG, "OnMessage hook is disabled, ignoring")
            return
        }

        if (table != "message") return

        val isSend  = values.getAsInteger("isSend")  ?: return
        if (isSend != 0) return // ignore outgoing

        val talker  = values.getAsString("talker")   ?: return
        val content = values.getAsString("content")  ?: return
        val type    = values.getAsInteger("type")    ?: 0

        WeLogger.i(TAG, "message received: talker=$talker type=$type content.length=${content.length}")


        AutomationEngine.executeAllOnMessage(rules, talker, content, type, isSend)
    }

    override fun unload(classLoader: ClassLoader) {
        WeLogger.i(TAG, "removing automation DB listener")
        WeDatabaseListener.removeListener(this)
        super.unload(classLoader)
    }

    // --- onRequest ---
    override fun onRequest(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        if (!isEnabled) return null
        if (!OnRequest.enabled) {
            WeLogger.i(TAG, "OnRequest hook is disabled, ignoring")
            return null
        }

        try {
            val data = WeProtoData()
            data.fromBytes(reqBytes)
            val json = data.toJSON()
            val modifiedJson = AutomationEngine.executeAllOnRequest(uri, cgiId, json)
            data.applyViewJSON(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e(TAG, e)
        }

        return null
    }

    // --- onResponse ---
    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (!isEnabled) return null
        if (!OnResponse.enabled) {
            WeLogger.i(TAG, "OnResponse hook is disabled, ignoring")
            return null
        }

        try {
            val data = WeProtoData()
            data.fromBytes(respBytes)
            val json = data.toJSON()
            val modifiedJson = AutomationEngine.executeAllOnResponse(uri, cgiId, json)
            data.applyViewJSON(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e(TAG, e)
        }
        return null
    }
}

@Composable
private fun AutomationSettingsDialogContent(rules: MutableList<AutomationRule>) {
    var snapshot by remember { mutableStateOf(rules.toList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun refresh() { snapshot = rules.toList() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("规则列表 (${snapshot.size})", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { showAddDialog = true }) { Text("+ 添加") }
        }

        Spacer(Modifier.height(8.dp))

        if (snapshot.isEmpty()) {
            Text(
                "暂无规则",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                snapshot.forEach { rule ->
                    AutomationRuleCard(
                        rule = rule,
                        onToggle = {
                            val idx = rules.indexOfFirst { it.id == rule.id }
                            if (idx != -1) { rules[idx] = rule.copy(enabled = !rule.enabled) }
                            refresh()
                        },
                        onDelete = {
                            rules.removeAll { it.id == rule.id }
                            refresh()
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    if (showAddDialog) {
        AddAutomationRuleDialog(
            onConfirm = { newRule ->
                rules.add(newRule)
                refresh()
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun AutomationRuleCard(rule: AutomationRule, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "脚本长度: ${rule.script.length} 字符",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AddAutomationRuleDialog(onConfirm: (AutomationRule) -> Unit, onDismiss: () -> Unit) {
    var ruleName by remember { mutableStateOf("") }
    var script by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自动化规则") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("规则名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text("JavaScript 脚本") },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    placeholder = {
                        Text(
                            "function onMessage(talker, content, type, isSend) {\n" +
                                    "  // your code here\n" +
                                    "  return null;\n" +
                                    "}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (ruleName.isBlank() || script.isBlank()) return@TextButton
                    onConfirm(
                        AutomationRule(
                            id = System.currentTimeMillis(),
                            name = ruleName,
                            script = script,
                            enabled = true
                        )
                    )
                }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}