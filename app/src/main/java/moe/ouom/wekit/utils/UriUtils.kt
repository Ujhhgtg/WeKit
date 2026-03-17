package moe.ouom.wekit.utils

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import moe.ouom.wekit.preferences.WePrefs

fun Uri.openInSystem(
    context: Context,
    useCustomTabs: Boolean = WePrefs.getBoolOrFalse("use_custom_tabs")) {
    if (useCustomTabs) {
        val forwardBitmap =
            BitmapFactory.decodeResource(
                ModuleRes.resources,
                ModuleRes.getId("forward_24px", "drawable")
            )
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_SEND)
                .setComponent(
                    ComponentName(
                        context.packageName,
                        // although this activity is called 'ShareImg',
                        // it is actually used to handle all types
                        "com.tencent.mm.ui.tools.ShareImgUI"
                    )
                )
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, this.toString()),
            PendingIntent.FLAG_IMMUTABLE
        )

        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .setBookmarksButtonEnabled(true)
            .setDownloadButtonEnabled(true)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
            .setActionButton(
                forwardBitmap,
                "转发",
                pendingIntent,
                true
            )
            .build()

        intent.launchUrl(context, this)
    }
    else {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = this
        context.startActivity(intent)
    }
}