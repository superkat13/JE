package com.pineapple.sageos2.capability

enum class Capability {
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
    USAGE_ACCESS,
    BATTERY_EXEMPTION,
    DEVICE_ADMIN,
    DEVICE_OWNER,
    ASSISTANT_ROLE,
    PLATFORM_PRIVILEGED,
    SAGEOS_ROOT_BROKER
}

enum class CapabilityStatus { ACTIVE, AVAILABLE, UNAVAILABLE, UNKNOWN }

data class CapabilitySnapshot(val states: Map<Capability, CapabilityStatus>)

data class DeviceAction(val name: String, val arguments: Map<String, String> = emptyMap())
data class CapabilityResult(val success: Boolean, val detail: String)

interface CapabilityBroker {
    fun snapshot(): CapabilitySnapshot
    fun execute(action: DeviceAction): CapabilityResult
}

object EmptyCapabilityBroker : CapabilityBroker {
    override fun snapshot() = CapabilitySnapshot(emptyMap())
    override fun execute(action: DeviceAction) = CapabilityResult(
        success = false,
        detail = "Capability '${action.name}' is not available in this runtime"
    )
}
