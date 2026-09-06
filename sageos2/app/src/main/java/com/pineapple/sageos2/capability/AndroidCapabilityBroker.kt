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
import com.pineapple.sageos2.root.PowerAction
import com.pineapple.sageos2.root.RootBrokerClient
import com.pineapple.sageos2.root.RootBrokerRequest
import com.pineapple.sageos2.root.RootOperation
import com.pineapple.sageos2.root.SettingNamespace
import com.pineapple.sageos2.root.UnavailableRootBrokerClient
import java.util.UUID

class AndroidCapabilityBroker(
    private val context: Context,
    private val rootBroker: RootBrokerClient = UnavailableRootBrokerClient()
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
            Capability.PLATFORM_PRIVILEGED to platformPrivilegeStatus(),
            Capability.SAGEOS_ROOT_BROKER to rootBrokerStatus()
        )
    )

    override fun execute(action: DeviceAction): CapabilityResult = try {
        val operation = rootOperation(action)
            ?: return CapabilityResult(false, "Unsupported capability action: ${action.name}")
        val result = rootBroker.execute(RootBrokerRequest(UUID.randomUUID().toString(), operation))
        CapabilityResult(
            success = result.success,
            detail = buildString {
                append(result.detail)
                result.stdout?.takeIf { it.isNotBlank() }?.let { append("\n").append(it.trimEnd()) }
                result.stderr?.takeIf { it.isNotBlank() }?.let { append("\n[stderr] ").append(it.trimEnd()) }
                result.auditId?.let { append("\n[audit ").append(it).append(']') }
            }
        )
    } catch (t: Throwable) {
        CapabilityResult(false, "Capability action failed: ${t.message ?: t::class.java.simpleName}")
    }

    private fun rootOperation(action: DeviceAction): RootOperation? = when (action.name) {
        "root.health" -> RootOperation.Health
        "root.install_package" -> RootOperation.InstallPackage(
            apkPath = action.required("path"),
            replaceExisting = action.boolean("replace", true)
        )
        "root.uninstall_package" -> RootOperation.UninstallPackage(
            packageName = action.required("package"),
            keepData = action.boolean("keep_data", false)
        )
        "root.set_package_enabled" -> RootOperation.SetPackageEnabled(
            packageName = action.required("package"),
            enabled = action.boolean("enabled", true)
        )
        "root.write_setting" -> RootOperation.WriteSecureSetting(
            namespace = SettingNamespace.valueOf(action.required("namespace").uppercase()),
            key = action.required("key"),
            value = action.arguments["value"]
        )
        "root.chown" -> RootOperation.SetFileOwnership(
            path = action.required("path"),
            uid = action.required("uid").toInt(),
            gid = action.required("gid").toInt()
        )
        "root.chmod" -> RootOperation.SetFileMode(
            path = action.required("path"),
            mode = action.required("mode").toInt()
        )
        "root.restart_service" -> RootOperation.RestartSystemService(action.required("service"))
        "root.power" -> RootOperation.Power(PowerAction.valueOf(action.required("action").uppercase()))
        "root.exec" -> RootOperation.ExecuteProcess(
            executable = action.required("executable"),
            args = action.numbered("arg."),
            environment = action.arguments
                .filterKeys { it.startsWith("env.") }
                .mapKeys { it.key.removePrefix("env.") },
            workingDirectory = action.arguments["cwd"],
            timeoutMs = action.arguments["timeout_ms"]?.toLongOrNull() ?: 30_000L
        )
        else -> null
    }

    private fun DeviceAction.required(name: String): String =
        arguments[name]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing argument '$name' for ${this.name}")

    private fun DeviceAction.boolean(name: String, default: Boolean): Boolean = when (arguments[name]?.lowercase()) {
        null -> default
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> throw IllegalArgumentException("Invalid boolean '$name' for ${this.name}")
    }

    private fun DeviceAction.numbered(prefix: String): List<String> = arguments.entries
        .mapNotNull { (key, value) ->
            key.removePrefix(prefix).takeIf { key.startsWith(prefix) }?.toIntOrNull()?.let { it to value }
        }
        .sortedBy { it.first }
        .map { it.second }

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
        return if (roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) CapabilityStatus.ACTIVE else CapabilityStatus.AVAILABLE
    }

    private fun rootBrokerStatus(): CapabilityStatus = try {
        if (rootBroker.health().available) CapabilityStatus.ACTIVE else CapabilityStatus.UNAVAILABLE
    } catch (_: RuntimeException) {
        CapabilityStatus.UNKNOWN
    }

    private fun platformPrivilegeStatus(): CapabilityStatus = if (
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) CapabilityStatus.ACTIVE else CapabilityStatus.UNAVAILABLE

    private fun enabledComponents(setting: String): Set<ComponentName> {
        val flattened = Settings.Secure.getString(context.contentResolver, setting).orEmpty()
        return flattened.split(':').mapNotNull(ComponentName::unflattenFromString).toSet()
    }

    private fun activeOrAvailable(active: Boolean): CapabilityStatus =
        if (active) CapabilityStatus.ACTIVE else CapabilityStatus.AVAILABLE

    companion object {
        private const val ENABLED_NOTIFICATION_LISTENERS_SETTING = "enabled_notification_listeners"
    }
}
