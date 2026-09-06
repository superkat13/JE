package com.pineapple.sageos2.brain

import com.pineapple.sage.SageBrainManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNativeBrainEngineTest {
    @Test fun missingLibraryIsReportedUnavailableWithoutCrashing() {
        val engine = LocalNativeBrainEngine(
            modelPath = "/definitely/missing/model.gguf",
            manager = SageBrainManager(),
            libraryLoader = { error("not packaged") }
        )
        val health = engine.health()
        assertFalse(health.ready)
        assertTrue(health.detail.contains("native library unavailable"))
    }
}
