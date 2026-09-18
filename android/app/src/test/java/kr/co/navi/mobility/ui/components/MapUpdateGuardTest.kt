package kr.co.navi.mobility.ui.components

import org.junit.Assert.*
import org.junit.Test

class MapUpdateGuardTest {
    @Test fun delayedOldStyleCannotOverwriteNewRoute() {
        val guard=MapUpdateGuard()
        var shown=""
        val first=guard.begin()
        val firstStyleLoaded={if(first.isCurrent())shown="old"}
        val second=guard.begin()
        val secondStyleLoaded={if(second.isCurrent())shown="rerouted"}
        secondStyleLoaded()
        firstStyleLoaded()
        assertEquals("rerouted",shown)
    }

    @Test fun queuedCameraAndBadgesCannotRestorePreviousEndpoints() {
        val guard=MapUpdateGuard()
        val first=guard.begin()
        val mutations=mutableListOf<String>()
        val pending=listOf({if(first.isCurrent())mutations+="old camera"},{if(first.isCurrent())mutations+="old badges"})
        first.dispose()
        val second=guard.begin()
        if(second.isCurrent())mutations+="new route"
        pending.forEach{it()}
        assertEquals(listOf("new route"),mutations)
    }

    @Test fun leavingMapInvalidatesCallbacksWithoutAnotherRoute() {
        val request=MapUpdateGuard().begin()
        assertTrue(request.isCurrent())
        request.dispose()
        assertFalse(request.isCurrent())
    }
}
