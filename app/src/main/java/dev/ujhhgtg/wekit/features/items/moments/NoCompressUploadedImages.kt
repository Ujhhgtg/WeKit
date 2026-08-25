package dev.ujhhgtg.wekit.features.items.moments

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.bool
import kotlin.io.path.copyTo

object NoCompressUploadedImages : ClickableFeature(), IResolveDex {

    override val technicalId = "上传原图"
    override val nameRes = R.string.feature_no_compress_uploaded_images_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_no_compress_uploaded_images_description

    private const val MODE_CONVERT = 0
    private const val MODE_COPY = 1

    private var selectedMode by WePrefs.prefOption("no_compress_mode", MODE_CONVERT)

    private val methodCreatePic by dexMethod {
        matcher {
            usingEqStrings(
                "MicroMsg.snsMediaStorage",
                "SnsCompressResolutionFor2G",
                "SnsCompressResolutionFor3G",
                "SnsCompressResolutionFor4G",
                "SnsCompressResolutionForWifi"
            )
        }
    }

    private val methodConvertImg2WxamWithoutZip by dexMethod {
        matcher {
            paramTypes("java.lang.String", "java.lang.String")
            usingEqStrings(
                "MicroMsg.snsMediaStorage",
                "convertImg2WxamWithoutZip origPath:%s OutOfMemoryError! rollback"
            )
        }
    }

    private val vfsGetCachePathMethod by lazy {
        WeMessageApi.classVfs.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(BString, bool)
            returnType = BString
        }
    }

    override fun onEnable() {
        methodCreatePic.hookBefore {
            if (selectedMode == MODE_CONVERT) {
                val str6 = args[0] as? String ?: ""
                val str8 = args[1] as? String ?: ""
                val str = args[2] as? String ?: ""
                val strConcat = str6 + str

                val resultBool = methodConvertImg2WxamWithoutZip.method.invoke(null, str8, strConcat) as? Boolean ?: false
                result = resultBool
            }
        }

        methodCreatePic.hookAfter {
            if (selectedMode == MODE_COPY) {
                val str11 = args[0] as? String ?: ""
                val str13 = args[1] as? String ?: ""
                val str = args[2] as? String ?: ""
                val isUpload = args[3] as? Boolean ?: false

                if (isUpload) {
                    val src = str13.asPath
                    val strConcat2 = str11 + str
                    val cachePath = vfsGetCachePathMethod.invoke(null, strConcat2, true) as? String
                    if (cachePath != null) {
                        val dst = cachePath.asPath
                        src.copyTo(dst, overwrite = true)
                    }
                }
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var mode by remember { mutableIntStateOf(selectedMode) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.moments_upload_original_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = MODE_CONVERT) {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.moments_upload_original_convert),
                                description = stringResource(R.string.moments_upload_original_convert_summary),
                                selected = mode == MODE_CONVERT,
                                onClick = {
                                    mode = MODE_CONVERT
                                    selectedMode = MODE_CONVERT
                                },
                            )
                        }
                        item(key = MODE_COPY) {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.moments_upload_original_copy),
                                description = stringResource(R.string.moments_upload_original_copy_summary),
                                selected = mode == MODE_COPY,
                                onClick = {
                                    mode = MODE_COPY
                                    selectedMode = MODE_COPY
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
