package com.shilapi.xcertplay.transport

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build

/** Probes Android 9's explicit USB permission activity without opening it. */
internal object UsbPermissionActivityProbe {
    internal const val COMPONENT = "com.android.systemui/com.android.systemui.usb.UsbPermissionActivity"
    internal const val PREFS = "diplay_usb_permission_ui"

    enum class State { AVAILABLE, UNAVAILABLE, UNKNOWN }

    data class Result(val state: State, val cached: Boolean = false) {
        fun diagnostic(): String = "USB permission UI state=$state cached=$cached component=$COMPONENT"
    }

    fun inspect(context: Context): Result {
        // Newer Android versions use an overlay-configurable activity and package visibility
        // restrictions. Do not infer absence from the old component on those versions.
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return Result(State.UNKNOWN)
        val app = context.applicationContext
        val identity = try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "v1:${Build.VERSION.SDK_INT}:${Build.FINGERPRINT}:${info.lastUpdateTime}:$version"
        } catch (_: Exception) {
            return Result(probe(app.packageManager))
        }
        return cached(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), identity) {
            probe(app.packageManager)
        }
    }

    @Synchronized
    internal fun cached(prefs: SharedPreferences, identity: String, probe: () -> State): Result {
        if (prefs.getString("identity", null) == identity) {
            val state = State.entries.firstOrNull { it.name == prefs.getString("state", null) }
            if (state != null && state != State.UNKNOWN) return Result(state, cached = true)
        }
        val state = probe()
        if (state == State.UNKNOWN) {
            prefs.edit().clear().apply()
        } else {
            prefs.edit().putString("identity", identity).putString("state", state.name)
                .remove("checkedAt").apply()
        }
        return Result(state)
    }

    internal fun probe(manager: PackageManager): State = try {
        val component = ComponentName.unflattenFromString(COMPONENT)!!
        // system_server opens this activity, so it need not be exported to applications.
        val info = manager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        val activityEnabled = enabled(manager.getComponentEnabledSetting(component), info.enabled)
        val appEnabled = enabled(manager.getApplicationEnabledSetting(component.packageName), info.applicationInfo.enabled)
        if (activityEnabled && appEnabled) State.AVAILABLE else State.UNAVAILABLE
    } catch (_: PackageManager.NameNotFoundException) {
        State.UNAVAILABLE
    } catch (_: Exception) {
        State.UNKNOWN
    }

    private fun enabled(setting: Int, manifestEnabled: Boolean): Boolean = when (setting) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }
}
