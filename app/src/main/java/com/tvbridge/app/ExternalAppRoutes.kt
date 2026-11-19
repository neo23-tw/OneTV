package com.tvbridge.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build

object ExternalAppRoutes {
    private const val PREFS_NAME = "external_routes"
    private const val KEY_MOVIE_PACKAGE = "movie_package"
    private const val KEY_MOVIE_LABEL = "movie_label"

    // Default package candidates for OK影視 or common TV streaming apps
    val DEFAULT_CANDIDATES = listOf(
        "com.ok.video",
        "com.ok.tv",
        "com.myok.tv",
        "com.ok.ys",
        "com.fongmi.android.tv",
        "com.github.tvbox.osc"
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMoviePackage(context: Context): String {
        val configured = prefs(context).getString(KEY_MOVIE_PACKAGE, null)
        if (!configured.isNullOrEmpty()) return configured
        return DEFAULT_CANDIDATES.firstOrNull { isPackageInstalled(context, it) } ?: DEFAULT_CANDIDATES.first()
    }

    fun getMovieLabel(context: Context): String {
        val label = prefs(context).getString(KEY_MOVIE_LABEL, null)
        if (!label.isNullOrEmpty()) return label
        val currentPkg = getMoviePackage(context)
        return try {
            val appInfo = context.packageManager.getApplicationInfo(currentPkg, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            "OK影視"
        }
    }

    fun setMovieApp(context: Context, packageName: String, label: String = "") {
        prefs(context).edit()
            .putString(KEY_MOVIE_PACKAGE, packageName)
            .putString(KEY_MOVIE_LABEL, label.ifEmpty { "OK影視" })
            .apply()
    }

    /**
     * Resolves the launch intent for an app on Android TV.
     * First checks standard launch intent; if null, falls back to Leanback launch intent (Android 5.0+).
     */
    fun getLaunchIntent(context: Context, packageName: String): Intent? {
        val pm = context.packageManager
        val standardIntent = try { pm.getLaunchIntentForPackage(packageName) } catch (_: Exception) { null }
        if (standardIntent != null) return standardIntent

        if (Build.VERSION.SDK_INT >= 21) {
            val leanbackIntent = try { pm.getLeanbackLaunchIntentForPackage(packageName) } catch (_: Exception) { null }
            if (leanbackIntent != null) return leanbackIntent
        }
        return null
    }

    /**
     * Safely launch the external movie app.
     * Guaranteed not to crash on Leanback-only apps, missing apps, or permission blocks.
     */
    fun launchMovieApp(context: Context): Boolean {
        val targetPkg = getMoviePackage(context)
        val launchIntent = getLaunchIntent(context, targetPkg)

        if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(launchIntent)
                return true
            } catch (e: Exception) {
                NativeUi.notice(context, "啟動電影應用失敗: ${e.message ?: "無法開啟"}")
                return false
            }
        }

        // Target package not installed or not resolvable
        val label = getMovieLabel(context)
        NativeUi.notice(context, "未安裝 $label（目標包名：$targetPkg），請先安裝或於設定中選擇應用")
        return false
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        if (try { pm.getPackageInfo(packageName, 0); true } catch (_: Exception) { false }) {
            return true
        }
        return getLaunchIntent(context, packageName) != null
    }

    /**
     * Show a dialog to let user choose which installed TV app is linked to the "電影" tab.
     * Comprehensive: lists all apps having LEANBACK_LAUNCHER or LAUNCHER intents.
     */
    fun showSelectMovieAppDialog(activity: android.app.Activity, onSelected: () -> Unit = {}) {
        val launcherApps = LauncherApps(activity)
        val installed = launcherApps.installed().filter { it.component.packageName != activity.packageName }
        val currentLabel = getMovieLabel(activity)

        val options = mutableListOf("恢復預設（OK影視）") + installed.map { it.label }

        NativeUi.show(
            AlertDialog.Builder(activity)
                .setTitle("選擇「電影」分頁關聯應用 (目前: $currentLabel)")
                .setItems(options.toTypedArray()) { _, which ->
                    if (which == 0) {
                        prefs(activity).edit().remove(KEY_MOVIE_PACKAGE).remove(KEY_MOVIE_LABEL).apply()
                        NativeUi.notice(activity, "已恢復預設電影應用（OK影視）")
                    } else {
                        val chosen = installed[which - 1]
                        setMovieApp(activity, chosen.component.packageName, chosen.label)
                        NativeUi.notice(activity, "已將「電影」分頁關聯至：${chosen.label}")
                    }
                    onSelected()
                }
                .setNegativeButton("取消", null)
        )
    }
}
