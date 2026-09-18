package kr.co.navi.mobility.guidance.contract

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteResponseGateTest {
    private val routeA = RouteSnapshot(
        revision = 4,
        graphRevision = 2,
        scopeRevision = "test-scope",
        datasetRevision = "test-dataset",
        sessionId = "session-a",
        geometry = emptyList(),
        segments = emptyList(),
        distanceMeters = 40.0,
    )

    private fun accept(
        active: Boolean = true,
        requestGeneration: Int = 7,
        currentGeneration: Int = 7,
        current: RouteSnapshot? = routeA,
        requestRevision: Int = routeA.revision,
        responseSession: String = routeA.sessionId,
        responseRevision: Int = 5,
        responseGraphRevision: Int = routeA.graphRevision,
    ) = RouteResponseGate.accept(
        active = active,
        requestGeneration = requestGeneration,
        currentGeneration = currentGeneration,
        requestSessionId = routeA.sessionId,
        currentSnapshot = current,
        requestRouteRevision = requestRevision,
        responseSessionId = responseSession,
        responseRouteRevision = responseRevision,
        responseGraphRevision = responseGraphRevision,
        expectedGraphRevision = routeA.graphRevision,
    )

    @Test fun acceptsNewResponseForTheUnchangedActiveRequest() {
        assertTrue(accept())
        // The contract requires monotonic advancement, not an assumed +1.
        assertTrue(accept(responseRevision = 9))
    }

    @Test fun rejectsResponseForRouteAAfterRouteBHasAlreadyBeenApplied() {
        val routeB = routeA.copy(revision = 5, distanceMeters = 60.0)
        assertFalse(accept(current = routeB, responseRevision = 6))
    }

    @Test fun rejectsStoppedNavigationEvenBeforeGenerationChanges() {
        assertFalse(accept(active = false))
    }

    @Test fun rejectsOldGenerationAfterStopAndRestart() {
        assertFalse(accept(active = false, currentGeneration = 8))
        assertFalse(accept(active = true, currentGeneration = 8))
        assertFalse(accept(requestGeneration = 8, currentGeneration = 7))
    }

    @Test fun rejectsMissingSnapshotOrChangedCurrentSession() {
        assertFalse(accept(current = null))
        assertFalse(accept(current = routeA.copy(sessionId = "session-b")))
    }

    @Test fun rejectsResponseFromAnotherSession() {
        assertFalse(accept(responseSession = "session-b"))
    }

    @Test fun rejectsDuplicateAndOlderResponseRevisions() {
        assertFalse(accept(responseRevision = 4))
        assertFalse(accept(responseRevision = 3))
        // Re-delivery after an accepted revision also fails the request-state check.
        assertFalse(accept(current = routeA.copy(revision = 5), responseRevision = 5))
    }

    @Test fun rejectsResponseWhenRequestRevisionDoesNotMatchCurrentRoute() {
        assertFalse(accept(requestRevision = 3))
        assertFalse(accept(requestRevision = 5, responseRevision = 6))
    }

    @Test fun rejectsServerOrCurrentGraphRevisionChanges() {
        assertFalse(accept(responseGraphRevision = 1))
        assertFalse(accept(responseGraphRevision = 3))
        assertFalse(accept(current = routeA.copy(graphRevision = 3)))
    }

    @Test fun gateAlsoProtectsNoRouteResultsBeforeStoppingGuidanceOrSpeech() {
        // Status is deliberately absent from the gate: success and no-route responses
        // must both pass the same lifecycle/session/revision checks before effects.
        val noRoute = routeA.copy(revision = 5, reasonCode = "no_route_within_poc")
        assertTrue(accept(responseRevision = noRoute.revision))
        assertFalse(accept(active = false, responseRevision = noRoute.revision))
        assertFalse(accept(current = noRoute, responseRevision = noRoute.revision))
    }
}
