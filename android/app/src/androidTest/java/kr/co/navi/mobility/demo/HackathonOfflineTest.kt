package kr.co.navi.mobility.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real model/asset preparation with an unreachable server, not camera/field acceptance. */
@RunWith(AndroidJUnit4::class)
class HackathonOfflineTest {
    @Test fun preparesBundledCourseAndModelWithNoReachableBackend()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator=withContext(Dispatchers.Main) {
            JeonjuCoordinator(context,"Hackathon","offline-check","http://127.0.0.1:1")
        }
        try {
            withTimeout(40_000) {
                while(!coordinator.state.value.prepared && !coordinator.state.value.terminal)delay(100)
            }
            assertTrue(coordinator.state.value.message,coordinator.state.value.prepared)
            assertFalse(coordinator.state.value.terminal)
            val east=coordinator.presetMapSegments.single{it.edgeId=="OSM_E_1327522116_93e880e796"}
            assertEquals(35.8457996,east.geometry.last().latitude,0.0000001)
            assertEquals(127.1319938,east.geometry.last().longitude,0.0000001)
        } finally {withContext(Dispatchers.Main){coordinator.close()}}
    }
}
