package com.pineapple.sageos2.capability

import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.pineapple.sage.SageDeviceAdminReceiver

sealed interface CapabilityActivationPlan {
    data class Launch(val intent: Intent) : CapabilityActivationPlan
    data class ProvisioningRequired(val reason: String) : CapabilityActivationPlan
    data class NotUserActivatable(val reason: String) : CapabilityActivationPlan
}

class CapabilityActivationPlanner(private val context: Context) {
    fun plan(capability: Capability): CapabilityActivationPlan = when (capability) {
        Capability.ACCESSIBILITY -> launch(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Capability.NOTIFICATION_LISTENER -> launch(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Capability.USAGE_ACCESS -> CapabilityActivationPlan.Launch(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
        Capability.BATTERY_EXEMPTION -> launch(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        Capability.DEVICE_ADMIN -> CapabilityActivationPlan.Launch(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, SageDeviceAdminReceiver::class.java))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Sage uses device-admin authority only for owner-approved tablet controls.")
        )
        Capability.DEVICE_OWNER -> CapabilityActivationPlan.ProvisioningRequired(
            "Device Owner is a provisioning-time Android role and cannot be silently enabled inside the app."
        )
        Capability.ASSISTANT_ROLE -> assistantPlan()
        Capability.PLATFORM_PRIVILEGED -> CapabilityActivationPlan.ProvisioningRequired(
            "Platform privilege requires the SageOS platform-signing/system-image path."
        )
        Capability.SAGEOS_ROOT_BROKER -> CapabilityActivationPlan.ProvisioningRequired(
            "Root-backed Sage capability requires the SageOS root broker/system-image integration."
        )
    }

    private fun assistantPlan(): CapabilityActivationPlan {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return CapabilityActivationPlan.NotUserActivatable("Assistant role API unavailable on this Android version.")
        }
        val roles = context.getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            return CapabilityActivationPlan.NotUserActivatable("Assistant role unavailable on this device.")
        }
        return CapabilityActivationPlan.Launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
    }

    private fun launch(action: String) = CapabilityActivationPlan.Launch(Intent(action))
}
