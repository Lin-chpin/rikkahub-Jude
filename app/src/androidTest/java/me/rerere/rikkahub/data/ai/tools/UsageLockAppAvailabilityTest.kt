package me.rerere.rikkahub.data.ai.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.usagetracker.UsageStatsReader
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageLockAppAvailabilityTest {
    @Test fun launcherWithoutDefaultCategoryIsAvailableForLocking() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(UsageStatsReader(context).loadLaunchableAppLabels().containsKey(context.packageName))
    }
}
