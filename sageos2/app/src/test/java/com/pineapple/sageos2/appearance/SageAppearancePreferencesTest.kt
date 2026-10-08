package com.pineapple.sageos2.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class SageAppearancePreferencesTest {
    @Test fun legacyStoredModesRemainReadableAndCycleInTheFamiliarOrder() {
        assertEquals(SageAppearanceMode.DARK, SageAppearanceMode.fromStored("dark"))
        assertEquals(SageAppearanceMode.DIM, SageAppearanceMode.fromStored("dim"))
        assertEquals(SageAppearanceMode.BLACK, SageAppearanceMode.fromStored("black"))
        assertEquals(SageAppearanceMode.DIM, SageAppearanceMode.DARK.next())
        assertEquals(SageAppearanceMode.BLACK, SageAppearanceMode.DIM.next())
        assertEquals(SageAppearanceMode.DARK, SageAppearanceMode.BLACK.next())
    }
}
