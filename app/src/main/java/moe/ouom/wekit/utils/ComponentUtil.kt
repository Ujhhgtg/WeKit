package moe.ouom.wekit.utils

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

@SuppressLint("QueryPermissionsNeeded")
fun ComponentName.getEnable(ctx: Context): Boolean {
    val packageManager: PackageManager = ctx.packageManager
    val list = packageManager.queryIntentActivities(
        Intent().setComponent(this), PackageManager.MATCH_DEFAULT_ONLY
    )
    return list.isNotEmpty()
}

fun ComponentName.setEnable(ctx: Context, enabled: Boolean) {
    val packageManager: PackageManager = ctx.packageManager
    if (this.getEnable(ctx) == enabled) return
    packageManager.setComponentEnabledSetting(
        this,
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP
    )
}
