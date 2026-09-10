package com.pineapple.sageos2.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandRecognizerPolicyTest {
    @Test fun preservedLocalSherpaIsPrimary() {
        assertEquals(
            CommandRecognizerBackend.LOCAL_SHERPA,
            CommandRecognizerPolicy.choose(true, true, true)
        )
    }

    @Test fun onDeviceAndroidIsFirstFallback() {
        assertEquals(
            CommandRecognizerBackend.ANDROID_ON_DEVICE,
            CommandRecognizerPolicy.choose(false, true, true)
        )
    }

    @Test fun defaultAndroidIsLastFallback() {
        assertEquals(
            CommandRecognizerBackend.ANDROID_DEFAULT,
            CommandRecognizerPolicy.choose(false, false, true)
        )
    }

    @Test fun noBackendIsReportedTruthfully() {
        assertEquals(
            CommandRecognizerBackend.UNAVAILABLE,
            CommandRecognizerPolicy.choose(false, false, false)
        )
    }
}
