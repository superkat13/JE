package com.pineapple.sageos2.migration

import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMigrationReportTest {
    @Test fun summaryDistinguishesReconciliationFromMissingLegacySources() {
        val report = LegacyMigrationReport(
            alreadyCompleted = true,
            completed = true,
            coreImported = true,
            legacyCorePresent = true,
            legacyMemoryPresent = false,
            legacyOwnerAppsPresent = true,
            legacyWakePresent = true,
            legacyAutonomyPresent = false
        )

        val summary = report.summary()
        assertTrue(summary.contains("already complete"))
        assertTrue(summary.contains("core=true"))
        assertTrue(summary.contains("source[core=true,memory=false,apps=true,wake=true,autonomy=false]"))
    }
}
