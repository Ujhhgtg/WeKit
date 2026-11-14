package dev.ujhhgtg.wekit.utils.android

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

@SuppressLint("QueryPermissionsNeeded")
fun ComponentName.getEnabled(ctx: Context): Boolean {
    val pm: PackageManager = ctx.packageManager
    val list =
        pm.queryIntentActivities(
            Intent().setComponent(this),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
    return list.isNotEmpty()
}

fun ComponentName.setEnabled(ctx: Context, enabled: Boolean) {
    val pm: PackageManager = ctx.packageManager
    if (getEnabled(ctx) == enabled) return
    pm.setComponentEnabledSetting(
        this,
        if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        },
        PackageManager.DONT_KILL_APP,
    )
}
