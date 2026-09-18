package kr.co.navi.mobility.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kr.co.navi.mobility.ar.sensors.HeadingState
import kr.co.navi.mobility.ar.sensors.HeadingTracker
import kr.co.navi.mobility.data.NaviSessionState
import kr.co.navi.mobility.data.NaviSessionStore
import kr.co.navi.mobility.data.model.CoordinateDto
import kr.co.navi.mobility.data.model.GraphEnrichmentCandidateDto
import kr.co.navi.mobility.data.model.GraphEnrichmentSimulationResponseDto
import kr.co.navi.mobility.data.model.GraphEnrichmentSummaryDto
import kr.co.navi.mobility.data.model.ObservationCandidateDto
import kr.co.navi.mobility.data.model.SessionRerouteResponseDto
import kr.co.navi.mobility.data.repository.NaviRepository
import kr.co.navi.mobility.guidance.contract.ArrivalGateConfig
import kr.co.navi.mobility.guidance.contract.ArrivalGateProgress
import kr.co.navi.mobility.guidance.contract.GeoCoordinate
import kr.co.navi.mobility.guidance.contract.updateArrivalGate
import kr.co.navi.mobility.location.LocationState
import kr.co.navi.mobility.location.LocationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class PlanUiState(
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val error: String? = null,
    val errorCanRetry: Boolean = false,
)

class PlanViewModel(
    private val repository: NaviRepository,
    private val sessionStore: NaviSessionStore,
    private val locationTracker: LocationTracker,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PlanUiState())
    val uiState: StateFlow<PlanUiState> = mutableUiState.asStateFlow()
    val sessionState: StateFlow<NaviSessionState> = sessionStore.state
    val locationState: StateFlow<LocationState> = locationTracker.state

    private val mutableRouteReady = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val routeReady: SharedFlow<Unit> = mutableRouteReady.asSharedFlow()

    init {
        loadBootstrap()
    }

    fun loadBootstrap() {
        viewModelScope.launch {
            mutableUiState.value = PlanUiState(loading = true)
            runCatching { repository.bootstrap() }
                .onSuccess {
                    sessionStore.setBootstrap(it)
                    mutableUiState.value = PlanUiState(loading = false)
                }
                .onFailure {
                    mutableUiState.value = PlanUiState(
                        loading = false,
                        error = it.userMessage("경로 데이터를 불러오지 못했습니다."),
                        errorCanRetry = true,
                    )
                }
        }
    }

    fun setProfile(profile: String) = sessionStore.setProfile(profile)

    fun startLocation() = locationTracker.start()

    fun useLatestLocation(): Boolean {
        val available = locationTracker.state.value as? LocationState.Available ?: return false
        val bootstrap = sessionStore.state.value.bootstrap ?: return false
        if (!bootstrap.coverageBounds.contains(available.coordinate)) {
            mutableUiState.value = mutableUiState.value.copy(
                error = "현재 위치는 ${bootstrap.areaName} 실증 Graph 범위 밖입니다. 카메라 기능은 확인할 수 있지만, 이 위치의 실제 경로 계산에는 해당 지역 Graph가 필요합니다.",
                errorCanRetry = false,
            )
            return false
        }
        sessionStore.setOrigin(available.coordinate)
        mutableUiState.value = mutableUiState.value.copy(error = null, errorCanRetry = false)
        return true
    }

    fun findRoute() {
        val state = sessionStore.state.value
        val origin = state.origin ?: return
        val destination = state.destination ?: return
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(submitting = true, error = null)
            runCatching {
                repository.compareRoute(origin, destination, state.profile)
            }.onSuccess {
                sessionStore.setComparison(it)
                mutableUiState.value = mutableUiState.value.copy(submitting = false)
                mutableRouteReady.tryEmit(Unit)
            }.onFailure {
                mutableUiState.value = mutableUiState.value.copy(
                    submitting = false,
                    error = it.userMessage("경로를 계산하지 못했습니다."),
                )
            }
        }
    }

    override fun onCleared() {
        locationTracker.stop()
        super.onCleared()
    }
}

class RouteViewModel(
    sessionStore: NaviSessionStore,
) : ViewModel() {
    val sessionState: StateFlow<NaviSessionState> = sessionStore.state
}

data class GraphCandidateUiState(
    val loading: Boolean = true,
    val rankingLoading: Boolean = false,
    val simulating: Boolean = false,
    val summary: GraphEnrichmentSummaryDto? = null,
    val candidates: List<GraphEnrichmentCandidateDto> = emptyList(),
    val selectedFilter: GraphCandidateFilter = GraphCandidateFilter.IMPACT,
    val simulationsByCandidateId: Map<String, GraphEnrichmentSimulationResponseDto> = emptyMap(),
    val rankingFailedCandidateIds: Set<String> = emptySet(),
    val selectedCandidateId: String? = null,
    val simulation: GraphEnrichmentSimulationResponseDto? = null,
    val error: String? = null,
)

enum class GraphCandidateFilter {
    IMPACT,
    PEDESTRIAN,
    CROSSING,
    CURB,
}

internal fun filterGraphCandidates(
    candidates: List<GraphEnrichmentCandidateDto>,
    filter: GraphCandidateFilter,
): List<GraphEnrichmentCandidateDto> = candidates.filter { candidate ->
    when (filter) {
        GraphCandidateFilter.IMPACT -> candidate.simulationAllowed
        GraphCandidateFilter.PEDESTRIAN -> candidate.type == "pedestrian_area_evidence"
        GraphCandidateFilter.CROSSING -> candidate.type in setOf(
            "crosswalk_geometry_evidence",
            "grade_separated_crossing_evidence",
        )
        GraphCandidateFilter.CURB -> candidate.type == "curb_presence_evidence"
    }
}

data class RankedGraphCandidateImpact(
    val candidate: GraphEnrichmentCandidateDto,
    val simulation: GraphEnrichmentSimulationResponseDto?,
)

internal fun rankGraphCandidateImpacts(
    candidates: List<GraphEnrichmentCandidateDto>,
    simulationsByCandidateId: Map<String, GraphEnrichmentSimulationResponseDto>,
): List<RankedGraphCandidateImpact> = candidates
    .map { candidate ->
        RankedGraphCandidateImpact(candidate, simulationsByCandidateId[candidate.candidateId])
    }
    .sortedWith(
        compareByDescending<RankedGraphCandidateImpact> { impactSeverity(it.simulation) }
            .thenByDescending { it.simulation?.differenceM ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.candidate.candidateId },
    )

private fun impactSeverity(simulation: GraphEnrichmentSimulationResponseDto?): Int = when {
    simulation == null -> 0
    simulation.baseline != null && simulation.simulated == null -> 4
    (simulation.differenceM ?: 0.0) > 0.0 -> 3
    simulation.routeChanged -> 2
    else -> 1
}

private data class CandidateSimulationAttempt(
    val candidateId: String,
    val simulation: GraphEnrichmentSimulationResponseDto? = null,
    val error: Throwable? = null,
)

class GraphCandidateViewModel(
    private val repository: NaviRepository,
    private val sessionStore: NaviSessionStore,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(GraphCandidateUiState())
    val uiState: StateFlow<GraphCandidateUiState> = mutableUiState.asStateFlow()
    private var simulationRequestId = 0
    private var catalogRequestId = 0

    init {
        loadCandidates()
    }

    fun loadCandidates() {
        val requestId = ++catalogRequestId
        simulationRequestId++
        viewModelScope.launch {
            mutableUiState.value = GraphCandidateUiState(loading = true)
            val catalog = try {
                repository.graphEnrichmentCatalog()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestId == catalogRequestId) {
                    mutableUiState.value = GraphCandidateUiState(
                        loading = false,
                        error = error.userMessage("공간데이터 후보를 불러오지 못했습니다."),
                    )
                }
                return@launch
            }
            if (requestId != catalogRequestId) return@launch

            mutableUiState.value = GraphCandidateUiState(
                loading = false,
                rankingLoading = catalog.candidates.any { it.simulationAllowed },
                summary = catalog.summary,
                candidates = catalog.candidates,
            )
            val simulatableCandidates = catalog.candidates.filter { it.simulationAllowed }
            if (simulatableCandidates.isEmpty()) return@launch

            val initialAttempts = simulateCandidates(simulatableCandidates)
            val retryCandidates = initialAttempts
                .filter { it.error != null }
                .mapNotNull { failed ->
                    simulatableCandidates.firstOrNull { it.candidateId == failed.candidateId }
                }
            val retryAttempts = if (retryCandidates.isEmpty()) {
                emptyList()
            } else {
                simulateCandidates(retryCandidates)
            }
            if (requestId != catalogRequestId) return@launch
            val attempts = initialAttempts.associateByTo(mutableMapOf()) { it.candidateId }
                .apply {
                    retryAttempts.forEach { retry -> put(retry.candidateId, retry) }
                }
                .values
            val simulations = attempts.mapNotNull { attempt ->
                attempt.simulation?.let { attempt.candidateId to it }
            }.toMap()
            val failedIds = attempts.filter { it.error != null }.mapTo(mutableSetOf()) {
                it.candidateId
            }
            val current = mutableUiState.value
            val selectedSimulation = current.selectedCandidateId?.let(simulations::get)
            mutableUiState.value = current.copy(
                rankingLoading = false,
                simulationsByCandidateId = simulations,
                rankingFailedCandidateIds = failedIds,
                simulation = selectedSimulation,
                simulating = false,
                error = if (current.selectedCandidateId in failedIds) {
                    "선택한 후보의 경로 영향을 계산하지 못했습니다. 다시 선택해 재시도할 수 있습니다."
                } else {
                    current.error
                },
            )
        }
    }

    fun selectCandidate(candidateId: String) {
        val state = mutableUiState.value
        val candidate = state.candidates.firstOrNull { it.candidateId == candidateId } ?: return
        if (!candidate.simulationAllowed) {
            simulationRequestId++
            mutableUiState.value = state.copy(
                selectedCandidateId = candidateId,
                simulation = null,
                simulating = false,
                error = null,
            )
            return
        }
        val cachedSimulation = state.simulationsByCandidateId[candidateId]
        if (cachedSimulation != null) {
            simulationRequestId++
            mutableUiState.value = state.copy(
                selectedCandidateId = candidateId,
                simulation = cachedSimulation,
                simulating = false,
                error = null,
            )
            return
        }
        if (state.rankingLoading) {
            mutableUiState.value = state.copy(
                selectedCandidateId = candidateId,
                simulation = null,
                simulating = true,
                error = null,
            )
            return
        }

        simulateSelectedCandidate(candidate)
    }

    fun selectFilter(filter: GraphCandidateFilter) {
        val state = mutableUiState.value
        if (filter == state.selectedFilter) return
        simulationRequestId++
        mutableUiState.value = state.copy(
            selectedFilter = filter,
            selectedCandidateId = null,
            simulation = null,
            simulating = false,
            error = null,
        )
    }

    private fun simulateSelectedCandidate(candidate: GraphEnrichmentCandidateDto) {
        val state = mutableUiState.value
        val candidateId = candidate.candidateId
        val geometry = sessionStore.state.value.bootstrap?.edgeGeometries?.get(candidate.edgeId)
        val start = geometry?.firstOrNull()?.takeIf { it.size >= 2 }
        val end = geometry?.lastOrNull()?.takeIf { it.size >= 2 }
        if (start == null || end == null) {
            mutableUiState.value = state.copy(
                selectedCandidateId = candidateId,
                simulation = null,
                error = "후보 Edge의 경로 geometry를 찾을 수 없습니다.",
            )
            return
        }

        val requestId = ++simulationRequestId
        mutableUiState.value = state.copy(
            selectedCandidateId = candidateId,
            simulation = null,
            simulating = true,
            error = null,
        )
        viewModelScope.launch {
            val result = runCatching {
                repository.simulateGraphCandidate(
                    origin = CoordinateDto(lat = start[1], lon = start[0]),
                    destination = CoordinateDto(lat = end[1], lon = end[0]),
                    profile = sessionStore.state.value.profile,
                    candidateId = candidateId,
                )
            }
            if (requestId != simulationRequestId) return@launch
            result.onSuccess { simulation ->
                mutableUiState.value = mutableUiState.value.copy(
                    simulating = false,
                    simulation = simulation,
                    simulationsByCandidateId = mutableUiState.value.simulationsByCandidateId +
                        (candidateId to simulation),
                    rankingFailedCandidateIds = mutableUiState.value.rankingFailedCandidateIds -
                        candidateId,
                )
            }.onFailure {
                mutableUiState.value = mutableUiState.value.copy(
                    simulating = false,
                    error = it.userMessage("후보 경로 영향을 계산하지 못했습니다."),
                )
            }
        }
    }

    private suspend fun simulateCandidates(
        candidates: List<GraphEnrichmentCandidateDto>,
    ): List<CandidateSimulationAttempt> = supervisorScope {
        candidates.map { candidate ->
            async {
                val geometry = sessionStore.state.value.bootstrap
                    ?.edgeGeometries
                    ?.get(candidate.edgeId)
                val start = geometry?.firstOrNull()?.takeIf { it.size >= 2 }
                val end = geometry?.lastOrNull()?.takeIf { it.size >= 2 }
                if (start == null || end == null) {
                    return@async CandidateSimulationAttempt(
                        candidateId = candidate.candidateId,
                        error = IllegalStateException("후보 Edge의 경로 geometry를 찾을 수 없습니다."),
                    )
                }
                try {
                    CandidateSimulationAttempt(
                        candidateId = candidate.candidateId,
                        simulation = repository.simulateGraphCandidate(
                            origin = CoordinateDto(lat = start[1], lon = start[0]),
                            destination = CoordinateDto(lat = end[1], lon = end[0]),
                            profile = sessionStore.state.value.profile,
                            candidateId = candidate.candidateId,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    CandidateSimulationAttempt(
                        candidateId = candidate.candidateId,
                        error = error,
                    )
                }
            }
        }.awaitAll()
    }
}

data class ReportUiState(
    val submitting: Boolean = false,
    val reroute: SessionRerouteResponseDto? = null,
    val candidate: ObservationCandidateDto? = null,
    val error: String? = null,
    val message: String? = null,
)

class NavigationViewModel(
    private val repository: NaviRepository,
    private val sessionStore: NaviSessionStore,
    private val locationTracker: LocationTracker,
    private val headingTracker: HeadingTracker,
) : ViewModel() {
    val sessionState: StateFlow<NaviSessionState> = sessionStore.state
    val locationState: StateFlow<LocationState> = locationTracker.state
    val headingState: StateFlow<HeadingState> = headingTracker.state
    val arrivalGateConfig = ArrivalGateConfig()

    private val mutableReportState = MutableStateFlow(ReportUiState())
    val reportState: StateFlow<ReportUiState> = mutableReportState.asStateFlow()
    private val guidanceActive = MutableStateFlow(false)
    private val mutableArrivalState = MutableStateFlow(ArrivalGateProgress())
    val arrivalState: StateFlow<ArrivalGateProgress> = mutableArrivalState.asStateFlow()
    private val mutableArrivalEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val arrivalEvents: SharedFlow<Unit> = mutableArrivalEvents.asSharedFlow()
    private var arrivalJourneyKey: String? = null

    init {
        viewModelScope.launch {
            combine(sessionState, locationState, guidanceActive) { session, location, active ->
                Triple(session, location, active)
            }.collect { (session, location, active) ->
                val route = session.activeRoute
                val destination = session.arrivalTarget()
                if (route == null || destination == null) {
                    arrivalJourneyKey = null
                    mutableArrivalState.value = ArrivalGateProgress()
                    return@collect
                }

                val sessionKey = session.comparison?.sessionId
                    ?: route.sessionId
                    ?: "${route.originNode}:${route.destinationNode}"
                val journeyKey = "$sessionKey:${route.edgeIds.joinToString(",")}:${route.geometry.hashCode()}"
                if (journeyKey != arrivalJourneyKey) {
                    arrivalJourneyKey = journeyKey
                    mutableArrivalState.value = ArrivalGateProgress(destination = destination)
                }
                if (!active) return@collect

                val available = location as? LocationState.Available
                if (available == null) {
                    mutableArrivalState.value = ArrivalGateProgress(destination = destination)
                    return@collect
                }

                val previous = mutableArrivalState.value
                val next = updateArrivalGate(
                    previous = previous,
                    destination = destination,
                    user = GeoCoordinate(
                        latitude = available.coordinate.lat,
                        longitude = available.coordinate.lon,
                    ),
                    accuracyMeters = available.accuracyMeters,
                    observedAtMillis = available.observedAtMillis.coerceAtLeast(0L),
                    config = arrivalGateConfig,
                )
                mutableArrivalState.value = next
                if (!previous.arrived && next.arrived) {
                    mutableArrivalEvents.tryEmit(Unit)
                }
            }
        }
    }

    fun startSensors() {
        locationTracker.start()
        headingTracker.start()
        guidanceActive.value = true
    }

    fun stopSensors() {
        guidanceActive.value = false
        locationTracker.stop()
        headingTracker.stop()
    }

    fun reportObstacle(type: String, note: String?, observedEdgeId: String? = null) {
        if (mutableReportState.value.submitting) return
        val state = sessionStore.state.value
        val sessionId = state.comparison?.sessionId
        val edgeId = observedEdgeId
        if (sessionId == null || edgeId == null) {
            mutableReportState.value = ReportUiState(error = "관측 위치와 구간의 정합이 필요합니다. 전주 자동 시연에서 공간 관측을 준비하세요.")
            return
        }
        val coordinate = (locationTracker.state.value as? LocationState.Available)?.coordinate
        viewModelScope.launch {
            mutableReportState.value = ReportUiState(submitting = true)
            val reroute = runCatching {
                repository.rerouteSession(sessionId, edgeId, type)
            }.getOrElse {
                mutableReportState.value = ReportUiState(
                    error = it.userMessage("안전한 우회 경로를 계산하지 못했습니다."),
                )
                return@launch
            }
            sessionStore.applyReroute(reroute)

            val candidateResult = runCatching {
                repository.createObservation(
                    sessionId = sessionId,
                    edgeId = edgeId,
                    type = type,
                    note = note,
                    coordinate = coordinate,
                )
            }
            candidateResult.onSuccess(sessionStore::setObservation)
            mutableReportState.value = ReportUiState(
                reroute = reroute,
                candidate = candidateResult.getOrNull(),
                error = candidateResult.exceptionOrNull()?.userMessage("우회는 완료됐지만 현장 후보 저장에 실패했습니다."),
                message = if (candidateResult.isSuccess) {
                    "현재 세션은 즉시 우회했고, 제보는 검수 대기로 저장했습니다."
                } else {
                    "현재 세션의 우회는 완료했습니다."
                },
            )
        }
    }

    fun resetReportState() {
        mutableReportState.value = ReportUiState()
    }

    override fun onCleared() {
        stopSensors()
        super.onCleared()
    }
}

private fun NaviSessionState.arrivalTarget(): GeoCoordinate? {
    val route = activeRoute ?: return null
    val routeEnd = route.geometry.lastOrNull { it.size >= 2 }
    if (routeEnd != null) {
        return runCatching {
            GeoCoordinate(latitude = routeEnd[1], longitude = routeEnd[0])
        }.getOrNull()
    }
    return destination?.let {
        runCatching { GeoCoordinate(latitude = it.lat, longitude = it.lon) }.getOrNull()
    }
}

class NaviViewModelFactory<T : ViewModel>(
    private val create: () -> T,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}

private fun Throwable.userMessage(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback
