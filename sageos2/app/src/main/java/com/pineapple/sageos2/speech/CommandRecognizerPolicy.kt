package com.pineapple.sageos2.speech

enum class CommandRecognizerBackend {
    LOCAL_SHERPA,
    ANDROID_ON_DEVICE,
    ANDROID_DEFAULT,
    UNAVAILABLE
}

/** Pure selection policy so local-primary/Android-fallback behavior is testable off-device. */
object CommandRecognizerPolicy {
    fun choose(
        localSherpaReady: Boolean,
        androidOnDeviceAvailable: Boolean,
        androidDefaultAvailable: Boolean
    ): CommandRecognizerBackend = when {
        localSherpaReady -> CommandRecognizerBackend.LOCAL_SHERPA
        androidOnDeviceAvailable -> CommandRecognizerBackend.ANDROID_ON_DEVICE
        androidDefaultAvailable -> CommandRecognizerBackend.ANDROID_DEFAULT
        else -> CommandRecognizerBackend.UNAVAILABLE
    }
}
