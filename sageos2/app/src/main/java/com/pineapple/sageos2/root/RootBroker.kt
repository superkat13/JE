package com.pineapple.sageos2.root

sealed interface RootOperation {
    data class InstallPackage(val apkPath: String, val replaceExisting: Boolean = true) : RootOperation
    data class UninstallPackage(val packageName: String, val keepData: Boolean = false) : RootOperation
    data class SetPackageEnabled(val packageName: String, val enabled: Boolean) : RootOperation
    data class WriteSecureSetting(val namespace: SettingNamespace, val key: String, val value: String?) : RootOperation
    data class SetFileOwnership(val path: String, val uid: Int, val gid: Int) : RootOperation
    data class SetFileMode(val path: String, val mode: Int) : RootOperation
    data class RestartSystemService(val serviceName: String) : RootOperation
    data class Power(val action: PowerAction) : RootOperation
    data object Health : RootOperation
}

enum class SettingNamespace { SYSTEM, SECURE, GLOBAL }
enum class PowerAction { REBOOT, SHUTDOWN, REBOOT_RECOVERY }

data class RootBrokerRequest(
    val requestId: String,
    val operation: RootOperation
)

data class RootBrokerResult(
    val requestId: String,
    val success: Boolean,
    val code: String,
    val detail: String,
    val auditId: String? = null
)

data class RootBrokerHealth(
    val available: Boolean,
    val version: String? = null,
    val detail: String
)

interface RootBrokerClient {
    fun health(): RootBrokerHealth
    fun execute(request: RootBrokerRequest): RootBrokerResult
}

class UnavailableRootBrokerClient : RootBrokerClient {
    override fun health() = RootBrokerHealth(
        available = false,
        detail = "SageOS root broker is not installed in this app-level build"
    )

    override fun execute(request: RootBrokerRequest) = RootBrokerResult(
        requestId = request.requestId,
        success = false,
        code = "ROOT_BROKER_UNAVAILABLE",
        detail = "SageOS root broker is not installed in this app-level build"
    )
}
