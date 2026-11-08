package dev.ujhhgtg.wekit.features.items.moments

import android.app.Activity
import android.content.Intent
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
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeVfsApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlin.io.path.copyTo

object NoCompressUploadedImages : ClickableFeature(), IResolveDex {
    override val technicalId = "上传原图"
    override val nameRes = R.string.feature_no_compress_uploaded_images_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_no_compress_uploaded_images_description

    private const val MODE_CONVERT = 0
    private const val MODE_COPY = 1

    private var selectedMode by KvStore.prefOption("no_compress_mode", MODE_CONVERT)

    private val methodImagePreviewSend by dexMethod {
        declaredClass = "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
        returnType = "void"
        paramTypes("android.content.Intent", "boolean", "boolean")
        usingEqStrings("CropImage_OutputPath_List", "key_select_video_list")
    }

    private val methodCreatePic by dexMethod {
        usingEqStrings(
            "MicroMsg.snsMediaStorage",
            "SnsCompressResolutionFor2G",
            "SnsCompressResolutionFor3G",
            "SnsCompressResolutionFor4G",
            "SnsCompressResolutionForWifi",
        )
    }

    private val methodConvertImg2WxamWithoutZip by dexMethod {
        paramTypes("java.lang.String", "java.lang.String")
        usingEqStrings(
            "MicroMsg.snsMediaStorage",
            "convertImg2WxamWithoutZip origPath:%s OutOfMemoryError! rollback",
        )
    }

    override fun onEnable() {
        methodImagePreviewSend.hookBefore {
            val activity = thisObject as Activity
            if (activity.intent.getIntExtra("query_source_type", -1) != 4) return@hookBefore
            // SnsUIAction opens the gallery with source 4. forTimeline=true forces
            // compression and routes a single video through the video editor. Returning
            // the selected file lists instead is also supported by SnsUIAction.
            args[1] = false
            args[2] = false
            (args[0] as Intent).putExtra("CropImage_Compress_Img", false)
        }

        Activity::class.reflekt()
            .firstMethod {
                name = "setResult"
                parameters(Int::class, Intent::class)
            }
            .hookBefore {
                val activity = thisObject as Activity
                if (
                    activity.javaClass.name !in GALLERY_RESULT_PAGES ||
                        activity.intent.getIntExtra("query_source_type", -1) != 4 ||
                        args[0] as Int != Activity.RESULT_OK
                ) {
                    return@hookBefore
                }
                val data = args[1] as? Intent ?: return@hookBefore
                if (
                    data.hasExtra("CropImage_OutputPath_List") ||
                        data.hasExtra("key_select_video_list")
                ) {
                    // Set these before the result is handed to the caller: the gallery writes
                    // key_delete_origin_file late, after constructing the image/video lists.
                    data.putExtra("CropImage_Compress_Img", false)
                    data.putExtra("key_delete_origin_file", false)
                }
            }

        methodCreatePic.hookBefore {
            if (selectedMode == MODE_CONVERT) {
                val str6 = args[0] as? String ?: ""
                val str8 = args[1] as? String ?: ""
                val str = args[2] as? String ?: ""
                val strConcat = str6 + str

                val resultBool =
                    methodConvertImg2WxamWithoutZip.method.invoke(null, str8, strConcat) as? Boolean
                        ?: false
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
                    val dst = WeVfsApi.resolveLocalPath(strConcat2, forWrite = true)
                    if (dst != null) {
                        src.copyTo(dst, overwrite = true)
                    }
                }
            }
        }
    }

    private val GALLERY_RESULT_PAGES =
        setOf(
            "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI",
            "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
        )

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
                                description =
                                    stringResource(
                                        R.string.moments_upload_original_convert_summary,
                                    ),
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
                                description =
                                    stringResource(R.string.moments_upload_original_copy_summary),
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
