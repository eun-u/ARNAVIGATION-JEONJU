package kr.co.navi.mobility.guidance.contract

/**
 * Checks a reroute response against the request and the current guidance state.
 * Call before any map, AR, distance or speech side effect, including no-route UI.
 * Stopping/restarting or applying another response invalidates an in-flight request.
 */
object RouteResponseGate {
    fun accept(
        active: Boolean,
        requestGeneration: Int,
        currentGeneration: Int,
        requestSessionId: String,
        currentSnapshot: RouteSnapshot?,
        requestRouteRevision: Int,
        responseSessionId: String,
        responseRouteRevision: Int,
        responseGraphRevision: Int,
        expectedGraphRevision: Int,
    ): Boolean {
        if (!active || requestGeneration != currentGeneration) return false
        val current = currentSnapshot ?: return false
        if (current.sessionId != requestSessionId || responseSessionId != requestSessionId) return false
        if (current.revision != requestRouteRevision || responseRouteRevision <= requestRouteRevision) return false
        if (current.graphRevision != expectedGraphRevision || responseGraphRevision != expectedGraphRevision) return false
        return true
    }
}
