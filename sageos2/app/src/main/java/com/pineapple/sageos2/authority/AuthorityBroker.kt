package com.pineapple.sageos2.authority

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.pineapple.sage.SageAccessibilityService
import com.pineapple.sageos2.root.RootBrokerClient
import com.pineapple.sageos2.root.RootBrokerHealth
import com.pineapple.sageos2.root.RootBrokerRequest
import com.pineapple.sageos2.root.RootBrokerResult
import com.pineapple.sageos2.root.RootOperation
import java.util.UUID

data class AuthoritySnapshot(
    val accessibility: Boolean,
    val deviceOwner: Boolean,
    val root: RootBrokerHealth
)

/**
 * Single truthful authority inventory for SageOS 2. It does not decide whether
 * Sage should perform an action; Sage Core/reasoning owns that decision. This
 * layer answers only: what technical authority is actually available, and can
 * the requested operation be carried out through that authority?
 */
class AuthorityBroker(
    context: Context,
    private val rootBroker: RootBrokerClient
) {
    private val appContext = context.applicationContext

    fun snapshot(): AuthoritySnapshot {
        val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
        return AuthoritySnapshot(
            accessibility = SageAccessibilityService.activeInstance() != null,
            deviceOwner = runCatching { dpm.isDeviceOwnerApp(appContext.packageName) }.getOrDefault(false),
            root = rootBroker.health()
        )
    }

    fun executeRoot(operation: RootOperation): RootBrokerResult = rootBroker.execute(
        RootBrokerRequest(UUID.randomUUID().toString(), operation)
    )
}
