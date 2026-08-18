package dev.ujhhgtg.wekit.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.utils.WeLogger

private const val REQUEST_OPEN_LSPOSED_MANAGER = 0x574B

object ManagerLaunchContract {
    const val ACTION_OPEN_LSPOSED_MANAGER =
        "${PackageNames.MODULE}.action.OPEN_LSPOSED_MANAGER"
    const val EXTRA_ERROR = "manager_launch_error"

    val ROOT_MANAGER_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "com.sukisu.ultra",
        "com.resukisu.resukisu",
        "me.bmax.apatch",
        "me.yuki.folk",
    )
}

@Suppress("DEPRECATION")
fun openLsposedManager(activity: Activity) {
    val intent = Intent().apply {
        setClassName(PackageNames.MODULE, "${PackageNames.MODULE}.activity.MainActivity")
        action = ManagerLaunchContract.ACTION_OPEN_LSPOSED_MANAGER
    }
    activity.startActivityForResult(intent, REQUEST_OPEN_LSPOSED_MANAGER)
}

@Suppress("DEPRECATION")
fun openRootManager(context: Context): Boolean {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val packageManager = context.packageManager
    val launcherPackages = packageManager.queryIntentActivities(
        launcherIntent,
        0,
    ).mapTo(mutableSetOf()) { it.activityInfo.packageName }

    val managerPackage = ManagerLaunchContract.ROOT_MANAGER_PACKAGES
        .firstOrNull(launcherPackages::contains)
        ?: return false
    val intent = packageManager.getLaunchIntentForPackage(managerPackage)?.apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } ?: return false

    return runCatching { context.startActivity(intent) }
        .onFailure { WeLogger.e("ManagerLaunch", "failed to launch $managerPackage", it) }
        .isSuccess
}
