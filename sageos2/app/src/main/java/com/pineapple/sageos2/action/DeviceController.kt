package com.pineapple.sageos2.action

data class DeviceControlResult(val success: Boolean, val message: String)

interface DeviceController {
    fun execute(command: FastCommand): DeviceControlResult
}
