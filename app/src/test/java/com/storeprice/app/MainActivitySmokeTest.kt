package com.storeprice.app

import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySmokeTest {

    @Test
    fun `main activity starts without crashing`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertNotNull(activity.window.decorView)
            }
        }
    }
}
