package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLockTargetResolverTest {
    @Test
    fun lockTargetRequiresAnInstalledAppWithMatchingNameAndPackage() {
        val apps = mapOf(
            "com.example.video" to "Example Video",
            "com.example.chat.one" to "Messenger",
            "com.example.chat.two" to "Messenger",
        )

        assertEquals(
            UsageLockTargetResolution.Resolved(UsageLockTarget("com.example.video", "Example Video")),
            resolveUsageLockTarget("com.example.video", "Example Video", apps),
        )
        assertEquals(
            UsageLockTargetResolution.Resolved(UsageLockTarget("com.example.video", "Example Video")),
            resolveUsageLockTarget(null, "example video", apps),
        )
        assertTrue(
            (resolveUsageLockTarget("com.example.video", "Messenger", apps) as UsageLockTargetResolution.Rejected)
                .reason.contains("Exact package for 'Messenger': com.example.chat.one, com.example.chat.two")
        )
        assertTrue(
            (resolveUsageLockTarget("com.missing.app", "Example Video", apps) as UsageLockTargetResolution.Rejected)
                .reason.contains("not an available launcher app")
        )
        assertTrue(
            (resolveUsageLockTarget(null, "Messenger", apps) as UsageLockTargetResolution.Rejected)
                .reason.contains("ambiguous")
        )
        assertTrue(
            (resolveUsageLockTarget("com.example.video", null, apps) as UsageLockTargetResolution.Rejected)
                .reason.contains("target_label")
        )
    }
}
