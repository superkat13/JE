package com.pineapple.sageos2.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerAppsTest {
    private val snapshot = OwnerAppSnapshot(1, listOf(
        OwnerAppRecord("com.example.browser", "Firefox", aliases = listOf("browser", "the fox"), purpose = "web"),
        OwnerAppRecord("com.example.music", "Music Player", aliases = listOf("music", "stereo"), purpose = "audio")
    ))

    @Test fun exactOwnerAliasBeatsGenericAndroidGuessing() {
        assertEquals("com.example.browser", OwnerAppResolver().resolve("the fox", snapshot)?.packageName)
    }

    @Test fun purposeDoesNotAccidentallyBecomeAnAlias() {
        assertNull(OwnerAppResolver().resolve("web", snapshot))
    }

    @Test fun displayNameStillResolves() {
        assertEquals("com.example.music", OwnerAppResolver().resolve("music player", snapshot)?.packageName)
    }
}
