package dev.ujhhgtg.wekit.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.chat.VoiceMessagePlaybackOptimization.MODE_DISABLE
import dev.ujhhgtg.wekit.features.items.chat.VoiceMessagePlaybackOptimization.MODE_INHERIT_PROGRESS
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.reflection.bool

/**
 * 语音消息播放逻辑优化。
 *
 * 微信在聊天语音播放时通过距离传感器自动切换听筒/扬声器, 其完整链路 (8.0.65-8.0.76):
 * - AutoPlay 控制器 (`com.tencent.mm.ui.chatting` 包内, 类名各版本不同:
 *   8.0.65 x0 / 8.0.67-69 y0 / 8.0.74-76 v0) 持有 SensorController;
 * - 传感器回调方法 (8.0.65 x1 / 8.0.67 s1 / 8.0.69 x1 / 8.0.74 f1 / 8.0.76 c1,
 *   参数 boolean, false=贴近耳朵 true=离开耳朵) 收到事件后延迟 50ms 调度切换任务;
 * - 切换任务类 (8.0.65 e1 / 8.0.67-69 f1 / 8.0.74-76 c1) 的 `onTimerExpired()` 执行真正切换:
 *   - 离开耳朵: setSpeakerOn(true) + switchSpeaker(), 若 RepairerConfigVoiceSpeakerSeek
 *     开启再回退 2 秒;
 *   - 贴近耳朵: setSpeakerOn(false), 若 VoiceSpeakerSeek 开启则 switchSpeaker() + 回退 2 秒,
 *     否则重新 `start play` 从头播放 — 这就是「切换后从头重播」的来源。
 *
 * 本功能两个模式:
 * - [MODE_DISABLE]: 直接拦截传感器回调, 完全禁用自动切换 (贴近/离开都不再切);
 * - [MODE_INHERIT_PROGRESS]: 仍自动切换, 但只执行 setSpeakerOn + switchSpeaker
 *   (switchSpeaker 内部对 MediaPlayer 保存 currentPosition 后 seekTo, Speex/Silk 仅重建
 *   AudioTrack 流类型), 跳过从头重播与 2 秒回退, 播放进度原样继承。
 *
 * 所有锚点均为跨版本稳定字符串, 按项目约定不加 allowFailure。
 */
object VoiceMessagePlaybackOptimization : ClickableFeature(), IResolveDex {

    override val technicalId = "语音消息播放逻辑优化"
    override val nameRes = R.string.feature_voice_message_playback_optimization_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_voice_message_playback_optimization_description

    /** 完全禁用自动切换 */
    private const val MODE_DISABLE = 0

    /** 切换后继承播放进度 */
    private const val MODE_INHERIT_PROGRESS = 1

    private var playbackMode by prefOption("voice_playback_mode", MODE_INHERIT_PROGRESS)

    /**
     * AutoPlay 语音播放控制器: 日志锚点 `MicroMsg.AutoPlay` + 传感器回调内的
     * `onSensorEvent, isON:` 组合定位, 各版本唯一。
     */
    private val classAutoPlay by dexClass {
        matcher {
            usingEqStrings("MicroMsg.AutoPlay", "onSensorEvent, isON:")
        }
    }

    /**
     * AutoPlay 的距离传感器回调 (boolean: false=贴近耳朵 true=离开耳朵)。
     * 方法名各版本不同, 用方法体内日志常量 `onSensorEvent, isON:` 锚定。
     */
    private val methodSensorCallback by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AutoPlay", "onSensorEvent, isON:")
            }
            usingEqStrings("onSensorEvent, isON:")
            paramTypes(bool)
            returnType("void")
        }
    }

    /** 切换任务入口 `onTimerExpired()`: 无参 boolean 返回, 方法名跨版本稳定。 */
    private val methodSwitchTask by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("speaker true", "speaker off")
            }
            paramCount = 0
            returnType("boolean")
        }
    }

    /** AutoPlay.setSpeakerOn(boolean) (8.0.65-74 G / 8.0.76 H): 记录当前扬声器状态。 */
    private val methodSetSpeakerOn by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AutoPlay", "onSensorEvent, isON:")
            }
            usingEqStrings("speakerOn has been set %s")
            paramTypes(bool)
        }
    }

    /** AutoPlay.switchSpeaker() (8.0.65-74 K / 8.0.76 L): 无参切换, 内部继承播放位置。 */
    private val methodSwitchSpeaker by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AutoPlay", "onSensorEvent, isON:")
            }
            usingEqStrings("switchSpeaker, isSpeakerOn: %b, isPlaying: %b")
            paramCount = 0
        }
    }

    override fun onEnable() {
        // 完全禁用: 传感器事件直接丢弃, AutoPlay 不会进入任何切换逻辑。
        methodSensorCallback.hookBefore {
            if (playbackMode == MODE_DISABLE) result = null
        }

        // 切换任务: 禁用模式下兜底丢弃; 继承进度模式下替换为「仅切换 + 保留进度」。
        methodSwitchTask.hookBefore {
            when (playbackMode) {
                MODE_DISABLE -> result = false
                MODE_INHERIT_PROGRESS -> {
                    val task = thisObject!!
                    val autoPlay = task.reflekt()
                        .firstField { type = classAutoPlay.clazz }
                        .get()
                    val switchingToEarpiece = task.reflekt()
                        .firstField { type = bool }
                        .get() as Boolean
                    methodSetSpeakerOn.method.invoke(autoPlay, switchingToEarpiece)
                    methodSwitchSpeaker.method.invoke(autoPlay)
                    result = false
                }
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var mode by remember { mutableIntStateOf(playbackMode) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_voice_message_playback_optimization_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_voice_playback_keep_progress),
                                description = stringResource(R.string.chat_voice_playback_keep_progress_description),
                                selected = mode == MODE_INHERIT_PROGRESS,
                                onClick = {
                                    mode = MODE_INHERIT_PROGRESS
                                    playbackMode = MODE_INHERIT_PROGRESS
                                },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_voice_playback_disable_switch),
                                description = stringResource(R.string.chat_voice_playback_disable_switch_description),
                                selected = mode == MODE_DISABLE,
                                onClick = {
                                    mode = MODE_DISABLE
                                    playbackMode = MODE_DISABLE
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
