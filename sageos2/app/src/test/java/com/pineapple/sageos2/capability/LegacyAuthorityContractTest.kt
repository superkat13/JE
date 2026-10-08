package com.pineapple.sageos2.capability

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyAuthorityContractTest {
    @Test
    fun criticalComponentNamesRemainStableForInPlaceUpgrade() {
        assertEquals("com.pineapple.sage.SageDeviceAdminReceiver", com.pineapple.sage.SageDeviceAdminReceiver::class.java.name)
        assertEquals("com.pineapple.sage.SageAccessibilityService", com.pineapple.sage.SageAccessibilityService::class.java.name)
        assertEquals("com.pineapple.sage.SageNotificationListener", com.pineapple.sage.SageNotificationListener::class.java.name)
        assertEquals("com.pineapple.sage.SageBootReceiver", com.pineapple.sage.SageBootReceiver::class.java.name)
        assertEquals("com.pineapple.sage.SageAssistActivity", com.pineapple.sage.SageAssistActivity::class.java.name)
        assertEquals("com.pineapple.sage.SageVoiceService", com.pineapple.sage.SageVoiceService::class.java.name)
        assertEquals("com.pineapple.sage.SageSherpaRecognitionService", com.pineapple.sage.SageSherpaRecognitionService::class.java.name)
    }

    @Test
    fun voiceInteractionComponentsHaveStableSageIdentity() {
        assertEquals("com.pineapple.sage.SageVoiceInteractionService", com.pineapple.sage.SageVoiceInteractionService::class.java.name)
        assertEquals("com.pineapple.sage.SageVoiceInteractionSessionService", com.pineapple.sage.SageVoiceInteractionSessionService::class.java.name)
    }
}
