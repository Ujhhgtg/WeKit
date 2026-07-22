package dev.ujhhgtg.wekit.features.items.moments

import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.LinkedList

@Feature(name = "自定义尾巴", categories = ["朋友圈"], description = "自定义发表朋友圈显示的应用来源")
object CustomSourceApp : ClickableFeature(), IResolveDex {

    private const val TAG = "CustomSourceApp"

    private data class SourceApp(val appId: String, val appName: String)

    private val methodCommitSnsInfo by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.UploadPackHelper", "commit sns info ret %d, typeFlag %d sightMd5 %s")
        }
    }

    private val methodSetSdkAppId by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            usingEqStrings("setSdkId", "com.tencent.mm.plugin.sns.model.UploadPackHelper")
        }
    }

    private val methodSetSdkAppName by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            usingEqStrings("setSdkAppName", "com.tencent.mm.plugin.sns.model.UploadPackHelper")
        }
    }

    private val methodSnsUploadUIInitView by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.sns.ui.SnsUploadUI"
            usingEqStrings("initView", "customizeInputView", "com.tencent.mm.plugin.sns.ui.SnsUploadUI")
        }
    }

    private var appId by prefOption("custom_app_id", "")
    private var appName by prefOption("custom_app_name", "")

    override fun onEnable() {
        methodSnsUploadUIInitView.hookAfter {
            val controller = thisObject!!.reflekt().getField("mController", true)!!
            val elements = controller.reflekt().firstField { type = LinkedList::class; superclass() }.get()!! as LinkedList<*>
            elements.last().reflekt().firstField { type = View.OnLongClickListener::class }.set(View.OnLongClickListener { view ->
                showConfigDialog(view.context)
                return@OnLongClickListener true
            })
        }

        methodCommitSnsInfo.hookBefore {
            if (appId.isNotBlank()) {
                methodSetSdkAppId.method.invoke(thisObject, appId)
                WeLogger.i(TAG, "modified app id: $appId")
            }
            if (appName.isNotBlank()) {
                methodSetSdkAppName.method.invoke(thisObject, appName)
                WeLogger.i(TAG, "modified app name: $appName")
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showConfigDialog(context)
    }

    fun showConfigDialog(context: Context) {
        showComposeDialog(context) {
            var appIdInput by remember { mutableStateOf(appId) }
            var appNameInput by remember { mutableStateOf(appName) }

            AlertDialogContent(
                title = { Text("自定义尾巴") },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = appIdInput,
                            onValueChange = { appIdInput = it },
                            label = { Text("应用 ID (留空不更改)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = appNameInput,
                            onValueChange = { appNameInput = it },
                            label = { Text("应用名称 (留空不更改)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Button(onClick = {
                            showSourceAppPicker(context) { sourceApp ->
                                appIdInput = sourceApp.appId
                                appNameInput = sourceApp.appName
                            }
                        }) { Text("从预设应用中选择") }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        appId = appIdInput
                        appName = appNameInput
                        onDismiss()
                    }) { Text("保存") }
                })
        }
    }

    private fun showSourceAppPicker(context: Context, onSelect: (SourceApp) -> Unit) {
        showComposeDialog(context) {
            var keyword by remember { mutableStateOf("") }
            val filteredApps = remember(keyword) {
                val query = keyword.trim()
                if (query.isEmpty()) {
                    sourceApps
                } else {
                    sourceApps.filter {
                        it.appId.contains(query, ignoreCase = true) || it.appName.contains(query, ignoreCase = true)
                    }
                }
            }

            AlertDialogContent(
                title = { Text("选择预设应用") },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            label = { Text("搜索 ID 或名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp)
                        ) {
                            items(filteredApps) { sourceApp ->
                                ListItem(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelect(sourceApp)
                                            onDismiss()
                                        },
                                    supportingContent = { Text(sourceApp.appId) },
                                    headlineContent = { Text(sourceApp.appName) },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {},
            )
        }
    }

    private val sourceApps: List<SourceApp> = listOf(
        SourceApp("wx41d24deba80ba5dc", "**"),
        SourceApp("wx2604a227cbdfc489", "**"),
        SourceApp("ww27aa4d34da709fc3", "市司法局"),
        SourceApp("wx2604a227cbdfc489", "湖南省电力工会"),
        SourceApp("ww371b4a1dcfea4476", "国民养老保险"),
        SourceApp("ww4942374d9cd4a2f0", "360亿方云"),
        SourceApp("ww52c145978d8cfd92", "腾讯公益"),
        SourceApp("ww7dd37b2ec236afa0", "CW"),
        SourceApp("wx00020f3788b9b216", "可爱有文化"),
        SourceApp("wx00027cf1e9af5065", "诸葛亮你饶了我吧"),
        SourceApp("wx000403344802025a", "启好运"),
        SourceApp("wx000833167c8148c4", "秋归"),
        SourceApp("wx000919db96cd9ab9", "胡麻了耶"),
        SourceApp("wx0013a78943962580", "庄记烤肉"),
        SourceApp("wx0013c7d15e8e0879", "该帐号已注销"),
        SourceApp("wx0019f9fb5d1ff628", "骑士闯关"),
        SourceApp("wx001a605bbd256afa", "家政多赚点"),
        SourceApp("wx001fdea2ed617f56", "法猪"),
        SourceApp("wx00200b5eac1ca02e", "永恒征战"),
        SourceApp("wx0022a5c28ed95c40", "三缺一娱乐空间"),
        SourceApp("wx002c1d5193fa2e25", "盼未来"),
        SourceApp("wx0036c37711cdfb99", "湖北电信"),
        SourceApp("wx0038c8ff7a67f9a0", "国民飞跃"),
        SourceApp("wx0039775624f3b1ee", "chair cafe"),
        SourceApp("wxda2ce55e23a3e06c", "爱疯18k永恒钻石版"),
        SourceApp("wx511c8b609d0c7710", "来自BMW X5社交互联"),
        SourceApp("wx281a70a3d390bdf2", "好友已设置小可爱可见"),
        SourceApp("wxb09d381947fc1678", "像我这样的一个人"),
        SourceApp("wxf0bcb316d289f6a4", "亮丽内蒙古"),
        SourceApp("wxe0cf858703575ebb", "FBI专用手机"),
        SourceApp("wxffee936f89cd0db9", "来自紫霞仙子的手机"),
        SourceApp("wx5fa4ebf320cf69f5", "垃圾桶捡到的手机"),
        SourceApp("wxe6f1e2780ae2a481", "仅限长比我丑的人可见"),
        SourceApp("wxaf048e83e0ab3f08", "来自一位陌生的透明人"),
        SourceApp("wx9ad15554b19159ee", "我能对你笑便能对你哭"),
        SourceApp("wx7395b7ea7ae1cab7", "主动久了便会累了"),
        SourceApp("wx77909ff94ab8b236", "一杯敬明天一杯敬过往"),
        SourceApp("wxbd1caba2e56648a2", "来自吃鸡大神专用手机"),
        SourceApp("wx3e73d1816c6e065a", "来自天上人间钻石VIP"),
        SourceApp("wx1bf866d2942d372f", "来自穷人专用老年机"),
        SourceApp("wxff725ddb21b2e1f7", "先放手的人最心痛"),
        SourceApp("wxb4adc29695b46e90", "翻盖大哥大"),
        SourceApp("wxcd3130c3a4ae2177", "你若安好我便不扰"),
        SourceApp("wxb42414c035f71567", "来自博亿达保险专用机"),
        SourceApp("wx6d9823e75d12ae61", "上瘾的东西不会是甜的"),
        SourceApp("wx8f7f888f74380733", "By iPhone X"),
        SourceApp("wxf8451614bded3112", "iPhone XI内测机"),
        SourceApp("wxcdaef18b70a86147", "IPhone Xi Max工程机"),
        SourceApp("wxa6eb9f1e291d445f", "二狗哥哥的iPhone"),
        SourceApp("wxca25444c1e5649c5", "iPhone Xi Max工程机"),
        SourceApp("wx224098d46d4e8bde", "13888888888"),
        SourceApp("wx322bb520817c18e7", "懒癌晚期已弃疗"),
        SourceApp("wx2498965842637a13", "肉的理想白菜命"),
        SourceApp("wxd80e96b5e48d7728", "点赞有惊喜Surprise"),
        SourceApp("wx142f30ba774eae62", "森米良心小卖家"),
        SourceApp("wx912d5d0260ab5965", "付费看评论"),
        SourceApp("wx5ce6035a51a71c8d", "同时提到了你"),
        SourceApp("wxe0d515767e6c3e1e", "已关闭评论功能"),
        SourceApp("wxfc6a2aae239774b5", "王者内测专用机"),
        SourceApp("wx20f9c2070f1c3156", "需要一个件维持生活"),
        SourceApp("wx3fb4f32d3930a347", "吃鸡内测专用机"),
        SourceApp("wxc061a68197db1e6a", "你拿的住我嘛"),
        SourceApp("wxb95263bc58ab28da", "生日快乐鸭"),
        SourceApp("wx4ba729b57c4859d5", "HUAWEI P30Pro"),
        SourceApp("wxd5a171b821e04a1e", "看到请还钱"),
        SourceApp("wxfcc50503e0ba579a", "淼淼的IPhone X"),
        SourceApp("wx2359940f314a69f7", "ıPhone XE"),
        SourceApp("wx196da8c2beb9a80c", "神一样的男人"),
        SourceApp("wx528bc3d4b664d037", "仇家多不方便透漏名字"),
        SourceApp("wx315ce2808c20cb43", "一直被模仿从未被超越"),
        SourceApp("wx367b267970d4cff8", "今日还钱打99折"),
        SourceApp("wx1b17d828fdad34cc", "克克克克业业"),
        SourceApp("wxe299f0e6b1f956e2", "祝自己生日快乐"),
        SourceApp("wx562d2e7716c4e622", "别放弃治疗"),
        SourceApp("wxaff9498e091aa711", "点赞后显示咋自定义"),
        SourceApp("wx7c54fdba0fa911a8", "点赞后查看详情"),
        SourceApp("wxe41414ecc104b2ff", "HUAWEI Mate40"),
        SourceApp("wxd6691b857145f7f0", "HUAWEI MateX"),
        SourceApp("wxafd95c7c133d01d5", "HUAWEI Mate30"),
        SourceApp("wx8b42cb3499e1c520", "HUAWEI P40"),
        SourceApp("wx934ec697e72a2fe1", "叙利亚打工中"),
        SourceApp("wxa91fccd5ffa9b407", "对方正在输入"),
        SourceApp("wx115bcff956fd0905", "仅限渣女可见"),
        SourceApp("wx3f4266934f0e29fb", "仅限渣男可见"),
        SourceApp("wx81474f8de1253450", "诺基亚N72"),
        SourceApp("wxb537a7de758633d2", "摩托罗拉328C"),
        SourceApp("wxea2a989cebb8d5d2", "佳能EOS1D X相机"),
        SourceApp("wxe0c0578e15b5df13", "买菜必涨价超级加倍"),
        SourceApp("wx01a907ff432e7576", "DJI御mavic2专业版"),
        SourceApp("wx0a0f2bf2d182f54e", "我是颜值主播不露脸"),
        SourceApp("wxe2a19dccf56fd564", "工商银行"),
        SourceApp("wx203a1671e701a3ae", "支付宝"),
        SourceApp("wx6d7e881b87cd1114", "LV"),
        SourceApp("wxf5a0f2eb63b78447", "保时捷"),
        SourceApp("wxaadbab9d13edff20", "快手"),
        SourceApp("wxc4c0253df149f02d", "和平精英"),
        SourceApp("wx95a3a4d7c627e07d", "王者荣耀"),
        SourceApp("wxd930ea5d5a258f4f", "openweixin Sdk"),
        SourceApp("wx276cd3ba4592bb9f", "蛋仔派对"),
        SourceApp("wx45116b30f23e0cc4", "波点音乐"),
        SourceApp("wxb0eef1f67b7a2949", "国家反诈中心"),
        SourceApp("wxb45673e2d96faa6a", "淘宝"),
        SourceApp("wxa140d866ec0fe092", "光遇"),
        SourceApp("wxae34109a271f7254", "阿里云盘"),
        SourceApp("wxdf261c3b90ffbc25", "中国电信"),
        SourceApp("wx9f6523d23a33a5b3", "美团外卖"),
        SourceApp("wxf0a80d0ac2e82aa7", "QQ"),
        SourceApp("wx9ceeba6e63dd491a", "讯飞输入法"),
        SourceApp("wxb08b45996fcdb650", "Blurrr软件"),
        SourceApp("wx76fdd06dde311af3", "抖音"),
        SourceApp("wx1ebb9c41ccbfb6d4", "肯德基"),
        SourceApp("wx6d8030a2f43b09a2", "麦当劳"),
        SourceApp("wxace271fb4fda0bc2", "麦当劳两个自测"),
        SourceApp("wx640ea70eb695d13a", "微店"),
        SourceApp("wx967c25b040d37a06", "皮皮虾"),
        SourceApp("wx16516ad81c31d872", "最右"),
        SourceApp("wx1c37343fc2a86bc4", "原神"),
        SourceApp("wxef5e7e401d2565f7", "QQ邮箱"),
        SourceApp("wxd8a2750ce9d46980", "QQ同步助手"),
        SourceApp("wx9b913299215a38f2", "高德地图"),
        SourceApp("wx4a2015b1eba8b32c", "hello语音"),
        SourceApp("wx2654d9155d70a468", "中国建行"),
        SourceApp("wx59cc372381201d39", "瑞幸"),
        SourceApp("wxbe109926790a6b4a", "葫芦侠"),
        SourceApp("wx77d53b84434b9d9a", "拼多多"),
        SourceApp("wx8b5220847f7bb64f", "学习通"),
        SourceApp("wxe75a2e68877315fb", "京东"),
        SourceApp("wxf6f9993ebaa8d663", "钉钉"),
        SourceApp("wxb84362f15c06b3f2", "铁路12306"),
        SourceApp("wx0ea99e425e26f1d4", "交管12123"),
        SourceApp("wxba9074d7f4eeae4e", "中国移动"),
        SourceApp("wxc4d74fa6f3968535", "百度贴吧移动支付"),
        SourceApp("wx77e0ce8ec8251e8d", "贴吧公测app"),
        SourceApp("wx25a5ad4ed63c2176", "贴吧Debug"),
        SourceApp("wx18f4395f7d76645a", "大师Pro开发版"),
        SourceApp("wxf510c23171aefc1a", "大师Pro测试版"),
        SourceApp("wx0aa69088a182a76e", "大师Pro"),
        SourceApp("wx3858261cdede9ca0", "喵喵机"),
        SourceApp("wx6b5d149cf4477e08", "桌面喵"),
        SourceApp("wx8d1a39cef44841a8", "桌面喵 （两个）"),
        SourceApp("wx5e1940228175fdc5", "720云"),
        SourceApp("wx14e6b0215602695c", "Blued"),
        SourceApp("wxc1ac68bd3d5a7381", "CAD看图王"),
        SourceApp("wxf789c03c017d58d6", "CSDN"),
        SourceApp("wx1e7c471af7c85aec", "DJ多多"),
        SourceApp("wx2362365843371394", "DJ嗨嗨"),
        SourceApp("wx4a2015b1eba8b32c", "Hello语音"),
        SourceApp("wxbada3fc7a6cb8d22", "IT之家"),
        SourceApp("wx0d7f08b94ce109b2", "jovi输入法"),
        SourceApp("wxb282679aa5d87d4a", "Keep"),
        SourceApp("wxdb691a69fbe2a6a7", "Max+"),
        SourceApp("wx288c5706af4794ee", "Moon月球"),
        SourceApp("wxd930ea5d5a258f4f", "OpenSDK Demo"),
        SourceApp("wx58837a82c2e0ed15", "QQ安全中心"),
        SourceApp("wx360b06d575d20cc3", "QQ飞车手游"),
        SourceApp("wxf0a80d0ac2e82aa7", "QQ分享"),
        SourceApp("wxc71c879291b0f5ec", "QQ输入法手机版"),
        SourceApp("wx1d0f5457c7556472", "QQ小世界"),
        SourceApp("wx5aa333606550dfd5", "QQ音乐"),
        SourceApp("wx4b7110bee4d7c9b9", "QQ邮箱"),
        SourceApp("wxa140d866ec0fe092", "sky光•遇"),
        SourceApp("wx5ca58eed072c774e", "Top Widgets"),
        SourceApp("wx020a535dccd46c11", "UC浏览器"),
        SourceApp("wx71955f58e7747601", "VN视频剪辑"),
        SourceApp("wxfcbcc7b981b4013c", "WIFI万能钥匙Pro版"),
        SourceApp("wx804d91d1cee20313", "Y2002音乐"),
        SourceApp("wx9ad0060a6cff3d45", "Zepp"),
        SourceApp("wx05a5c3841b61aaf8", "ZeppLife"),
        SourceApp("wx2fab8a9063c8c6d0", "爱奇艺"),
        SourceApp("wx37a067ae9226de4d", "傲软抠图"),
        SourceApp("wx3fcdd8310a136ff8", "百度APP"),
        SourceApp("wx27a43222a6bf2931", "百度"),
        SourceApp("wxb42cca62c3f838b9", "百度输入法"),
        SourceApp("wx608a35259a6f2d5f", "百度输入法键盘"),
        SourceApp("wxa278525fb9f661fd", "百度输入法小米版"),
        SourceApp("wx289a8c58bca4c71e", "百度贴吧"),
        SourceApp("wx65cffe5f882034d1", "百度网盘"),
        SourceApp("wxf5a0f2eb63b78447", "保时捷智慧出行"),
        SourceApp("wxcb8d4298c6a09bcb", "哔哩哔哩"),
        SourceApp("wxd54bd75ad89d33a0", "哔哩哔哩客户端"),
        SourceApp("wx83b51f04d7ebb03b", "彩云天气Pro"),
        SourceApp("wxb81788a085843d31", "超级课程表-表表"),
        SourceApp("wxffc3a16e4a8e535a", "车来了"),
        SourceApp("wx58164a91f1821369", "穿越火线-枪战王者"),
        SourceApp("wx9181ed3f223e6d76", "春晚摇一摇"),
        SourceApp("wx9b1de7cd8f8deb72", "大麦"),
        SourceApp("wx8e251222d6836a60", "大众点评"),
        SourceApp("wx06a0d7e0013fd686", "蛋仔派对hw"),
        SourceApp("wx7e8eef23216bade2", "滴滴出行"),
        SourceApp("wx00868f158610b1f7", "第五人格"),
        SourceApp("wxdf261c3b90ffbc25", "电信营业厅客户端"),
        SourceApp("wx50a3272e1669f0c0", "订阅号助手"),
        SourceApp("wx0d3cc3a6e9f20276", "斗破苍穹：异火重燃"),
        SourceApp("wx6be84d532f192698", "斗鱼"),
        SourceApp("wx0ea7a86743e8aa47", "堆糖"),
        SourceApp("wx5d4b8c07d3999007", "多邻国"),
        SourceApp("wxecf8990f4a9ea69e", "番茄免费小说app"),
        SourceApp("wxd638ead9776a3c87", "飞凡汽车Rising Auto"),
        SourceApp("wxe2a19dccf56fd564", "工行手机银行"),
        SourceApp("wxf680c5a113e94b3f", "工银兴农通"),
        SourceApp("wxa402496b7ea7f957", "广发银行发现精彩APP"),
        SourceApp("wx84008f9992caeaf3", "果冻宝盒"),
        SourceApp("wx0e9bd96707b56471", "哈啰APP"),
        SourceApp("wx4db69980ed8f57a4", "汉堡睡前故事"),
        SourceApp("wx015c3d7da5028dee", "和包"),
        SourceApp("wx1151bdc91cda1ed2", "虎牙直播"),
        SourceApp("wx65d8aeb837088899", "华为浏览器"),
        SourceApp("wx76fc280041c16519", "欢乐斗地主（腾讯）"),
        SourceApp("wxd9c063843bafda36", "欢太浏览器"),
        SourceApp("wxdad7cff233bd33f8", "黄油相机"),
        SourceApp("wx82dd7436af5db835", "火影忍者"),
        SourceApp("wxae75e4ceb13c9df5", "货拉拉司机端"),
        SourceApp("wxf4b42938cba94b7e", "即刻"),
        SourceApp("wx6fcf65f28d6a3919", "驾考宝典"),
        SourceApp("wx83cc040b89fc9445", "见圳"),
        SourceApp("wx6ab1a4553dcb411f", "建行生活"),
        SourceApp("wx0ea99e425e26f1d4", "交警12123"),
        SourceApp("wx4ed5a44d6f4fdf10", "今日头条（社交版）"),
        SourceApp("wx50d801314d9eb858", "今日头条"),
        SourceApp("wxb23e82d26e8ab140", "金铲铲之战"),
        SourceApp("wxa3b3f36fcd9df06e", "京东金融"),
        SourceApp("wxb1753a8e51d9d32d", "酷安"),
        SourceApp("wx72b795aca60ad321", "酷狗概念版"),
        SourceApp("wx79f2c4418704b4f8", "酷狗音乐"),
        SourceApp("wxc305711a2a7ad71c", "酷我音乐"),
        SourceApp("wx85686879e8891882", "夸克"),
        SourceApp("wx42d6d3bdc1cb2bdc", "快手极速版"),
        SourceApp("wxf369b525a2913087", "乐趣用品"),
        SourceApp("wx912afad5fd3f8f46", "雷速体育"),
        SourceApp("wx10e2ae0624e95569", "黎明觉醒：生机"),
        SourceApp("wxe939a762f096c3b0", "恋爱话术pro"),
        SourceApp("wxee04edfc147e07d4", "撩蜜"),
        SourceApp("wx29d28524d6eaf623", "流利说英语"),
        SourceApp("wxfdab5af74990787a", "龙之谷"),
        SourceApp("wx30d7e1a70c61789d", "埋堆堆"),
        SourceApp("wxace271fb4fda0bc2", "麦当劳STG"),
        SourceApp("wx97ae91ec83768ad4", "猫耳"),
        SourceApp("wx8d1a39cef44841a8", "猫之城"),
        SourceApp("wxa552e31d6839de85", "美团"),
        SourceApp("wx39f35628c5fd95d3", "美团外卖商家"),
        SourceApp("wxe6bb36187c7fa4b9", "梦幻西游互通版"),
        SourceApp("wx4cbc67ebaa25f436", "蜜堂好物"),
        SourceApp("wxc1063474755a5f24", "磨题帮"),
        SourceApp("wx124f5809f1a1bead", "拼多多好货"),
        SourceApp("wxf77e7a0d4f534650", "拼多多商家版"),
        SourceApp("wx8d063edb6f724dd9", "平安好车主"),
        SourceApp("wx608e400f36844b88", "平安口袋E"),
        SourceApp("wx4ab005521f9f1c04", "七猫免费小说"),
        SourceApp("wx4706a9fcbbca10f2", "企业微信"),
        SourceApp("wx904fb3ecf62c7dea", "汽水音乐"),
        SourceApp("wx3c77de7b9a15dc9f", "千千音乐"),
        SourceApp("wxef84982ef5634a6e", "悄悄友朋圈"),
        SourceApp("wxcaefc046890fd638", "清风Dj"),
        SourceApp("wx2ed190385c3bafeb", "全民K歌"),
        SourceApp("wx59cc372381201d39", "瑞幸咖啡"),
        SourceApp("wx48a3b2db49e29a1b", "三生三世十里桃花"),
        SourceApp("wx8985aa576c242138", "扫电视"),
        SourceApp("wxfbc915ff7c30e335", "扫条码"),
        SourceApp("wxb08b45996fcdb650", "沙拉视频"),
        SourceApp("wxed08b6c4003b1fd5", "什么值得买"),
        SourceApp("wxc933ffba7d9de4dc", "使命召唤手游"),
        SourceApp("wxf7c4c8000b39008e", "手机阿里巴巴"),
        SourceApp("wx347920cf749b82a7", "说得相机"),
        SourceApp("wxd855cafb5b488002", "搜狗输入法"),
        SourceApp("wx36174d3a5f72f64a", "腾讯地图"),
        SourceApp("wxca942bbff22e0e51", "腾讯视频"),
        SourceApp("wxccac4ab14315add3", "腾讯手机管家"),
        SourceApp("wx073f4a4daff0abe8", "腾讯新闻"),
        SourceApp("wx71873ad429f369f9", "天天军棋"),
        SourceApp("wx77e0ce8ec8251e8d", "贴吧公测App"),
        SourceApp("wxc30efa39c0191186", "同花顺"),
        SourceApp("wxec8f618eaecadf83", "歪麦"),
        SourceApp("wx7819539d83b9dc1e", "完美世界"),
        SourceApp("wx263e2055f871aba6", "网易新闻专业版"),
        SourceApp("wx0aa69088a182a76e", "网易邮箱大师Pro"),
        SourceApp("wx8dd6ecd81906fd84", "网易云音乐"),
        SourceApp("wx68ca4cf18bbd5838", "威锋社区"),
        SourceApp("wx299208e619de7026", "微博"),
        SourceApp("wxc2d4705bed01319d", "微博轻享版"),
        SourceApp("wx2e5df03e8b9c6525", "微店"),
        SourceApp("wx640ea70eb695d13a", "微店买家版"),
        SourceApp("wx02753775b9060fec", "微脉圈"),
        SourceApp("wxce2a6f4e935b4506", "微脉水印相机"),
        SourceApp("wx6618f1cfc6c132f8", "微信电脑版"),
        SourceApp("wxab9b71ad2b90ff34", "微信读书"),
        SourceApp("wx62d9035fd4fd2059", "微信游戏"),
        SourceApp("wx786ab81fe758bec2", "微云"),
        SourceApp("wxe70cb772111a504c", "文字emoji"),
        SourceApp("wx84ef4b0064a3b863", "我的看房日记"),
        SourceApp("wxfebaaacdc317ced7", "喜欢和你在一起"),
        SourceApp("wxb42f1f273716d464", "潇湘书院"),
        SourceApp("wx1ba3408a50f18d5b", "小蚕荟"),
        SourceApp("wxe8989eb8b38b5c4d", "小黑盒"),
        SourceApp("wxd8a2750ce9d46980", "小红书"),
        SourceApp("wx6489dbf9e805d4e9", "小黄历"),
        SourceApp("wx20614bfdb40644b6", "秀色秀场"),
        SourceApp("wx9ceeba6e63dd491a", "讯飞输入法Pro"),
        SourceApp("wx1b4d03357ad23b00", "妖精的尾巴：魔导少年"),
        SourceApp("wx220cd3a2b03aba92", "妖精的尾巴力量觉醒"),
        SourceApp("wx2fe12a395c426fcf", "摇一摇"),
        SourceApp("wx485a97c844086dc9", "摇一摇(两个)"),
        SourceApp("wxa9ae1d63973c3798", "遥望"),
        SourceApp("wxbdc5610cc59c1631", "一号会员店"),
        SourceApp("wx123790a68951765e", "一起来捉妖"),
        SourceApp("wxff1f0c8244042666", "壹深圳"),
        SourceApp("wx6392ee2bb4d30891", "壹言"),
        SourceApp("wx5a611599efa17e78", "英雄联盟手游"),
        SourceApp("wxbda00e1f0a7e2784", "英语趣配音"),
        SourceApp("wxd695f382e6955e5a", "影猫电影"),
        SourceApp("wxf56d7d93bc226f2e", "友邻优课"),
        SourceApp("wx5c6bfdfc3d281fa9", "有道词典"),
        SourceApp("wxf10bdff91370663d", "有道翻译官"),
        SourceApp("wxf2d9346601f834ab", "元气壁纸"),
        SourceApp("wx82582d2ace426da2", "云闪付UnionPay"),
        SourceApp("wxd3f6cb54399a8489", "知乎"),
        SourceApp("wx3e388e8f02f38759", "智行火车票"),
        SourceApp("wx2654d9155d70a468", "中国建设银行"),
        SourceApp("wxc55371ea66dc0e89", "中国建设银行手机银行"),
        SourceApp("wxa13d0b8c5270d1ff", "中国联通APP"),
        SourceApp("wxb6a144065b813239", "中国农业银行"),
        SourceApp("wx51d21349ff5b33a6", "中华万年历"),
        SourceApp("wx324cd70c3e181d8b", "中银跨境GO"),
        SourceApp("wx6f1a8464fa672b11", "转转客户端"),
        SourceApp("wxbe198ad3f7f0dc4c", "子午万年历"),
    )
}
