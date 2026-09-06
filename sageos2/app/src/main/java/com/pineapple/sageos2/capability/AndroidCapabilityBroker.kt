package com.pineapple.sageos2.capability

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import com.pineapple.sage.SageAccessibilityService
import com.pineapple.sage.SageDeviceAdminReceiver
import com.pineapple.sage.SageNotificationListener
import com.pineapple.sage.SageVoiceInteractionService

class AndroidCapabilityBroker(
    private val context: Context
) : CapabilityBroker {
    override fun snapshot(): CapabilitySnapshot = CapabilitySnapshot(
        mapOf(
            Capability.ACCESSIBILITY to activeOrAvailable(accessibilityActive()),
            Capability.NOTIFICATION_LISTENER to activeOrAvailable(notificationListenerActive()),
            Capability.USAGE_ACCESS to activeOrAvailable(usageAccessActive()),
            Capability.BATTERY_EXEMPTION to activeOrAvailable(batteryExemptionActive()),
            Capability.DEVICE_ADMIN to activeOrAvailable(deviceAdminActive()),
            Capability.DEVICE_OWNER to deviceOwnerStatus(),
            Capability.ASSISTANT_ROLE to assistantStatus(),
            Capability.SHIZUKU_SHELL to shizukuStatus(),
            Capability.PLATFORM_PRIVILEGED to platformPrivilegeStatus(),
            Capability.SAGEOS_ROOT_BROKER to CapabilityStatus.UNAVAILABLE
        )
    )

    override fun execute(action: DeviceAction): CapabilityResult = CapabilityResult(
        success = false,
        detail = "Android device-action execution is not wired yet: ${action.name}"
    )

    private fun accessibilityActive(): Boolean {
        val target = ComponentName(context, SageAccessibilityService::class.java)
        return enabledComponents(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(target)
    }

    private fun notificationListenerActive(): Boolean {
        val target = ComponentName(context, SageNotificationListener::class.java)
        return enabledComponents(ENABLED_NOTIFICATION_LISTENERS_SETTING).contains(target)
    }

    private fun usageAccessActive(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun batteryExemptionActive(): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    private fun deviceAdminActive(): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm.isAdminActive(ComponentName(context, SageDeviceAdminReceiver::class.java))
    }

    private fun deviceOwnerStatus(): CapabilityStatus {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isDeviceOwnerApp(context.packageName)) return CapabilityStatus.ACTIVE
        return try {
            if (dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE)) {
                CapabilityStatus.AVAILABLE
            } else {
                CapabilityStatus.UNAVAILABLE
            }
        } catch (_: RuntimeException) {
            CapabilityStatus.UNKNOWN
        }
    }

    private fun assistantStatus(): CapabilityStatus {
        val service = ComponentName(context, SageVoiceInteractionService::class.java)
        if (VoiceInteractionService.isActiveService(context, service)) {
            return CapabilityStatus.ACTIVE
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return CapabilityStatus.AVAILABLE
        val roles = context.getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return CapabilityStatus.UNAVAILABLE
        return if (roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            CapabilityStatus.ACTIVE
        } else {
            CapabilityStatus.AVAILABLE
        }
    }

    private fun shizukuStatus(): CapabilityStatus = if (packageInstalled(SHIZUKU_PACKAGE)) {
        CapabilityStatus.AVAILABLE
    } else {
        CapabilityStatus.UNAVAILABLE
    }

    private fun platformPrivilegeStatus(): CapabilityStatus = if (
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        CapabilityStatus.ACTIVE
    } else {
        CapabilityStatus.UNAVAILABLE
    }

    private fun enabledComponents(setting: String): Set<ComponentName> {
        val flattened = Settings.Secure.getString(context.contentResolver, setting).orEmpty()
        return flattened.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .toSet()
    }

    private fun packageInstalled(packageName: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun activeOrAvailable(active: Boolean): CapabilityStatus =
        if (active) CapabilityStatus.ACTIVE else CapabilityStatus.AVAILABLE

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val ENABLED_NOTIFICATION_LISTENERS_SETTING = "enabled_notification_listeners"
    }
}
