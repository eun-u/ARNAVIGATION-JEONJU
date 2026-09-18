"use strict";

const DEMO_GRAPH = {
  type: "FeatureCollection",
  metadata: {
    source: "synthetic_frontend_fallback",
    accessibility_attributes: "synthetic",
    demo: {
      origin_node: "DEMO-A",
      destination_node: "DEMO-B",
      block_edge: "DEMO-E15",
      expected: {
        standard: { distance_m: 130.7 },
        accessible_before: { distance_m: 130.7 },
        accessible_after: { distance_m: 153.5 },
      },
    },
  },
  features: [
    pointFeature("DEMO-A", "전북대학교 정문 방향 · 합성 데모", 35.8463514, 127.1319861),
    pointFeature("DEMO-M", "접근성 분기점 · 합성 데모", 35.8458917, 127.1319832),
    pointFeature("DEMO-B", "전북대 캠퍼스 도착점 · 합성 데모", 35.845787, 127.1312826),
    {
      type: "Feature",
      properties: { feature_type: "edge", edge_id: "DEMO-E15", blocked: false, source: "synthetic", verified: false },
      geometry: { type: "LineString", coordinates: [[127.1319832, 35.8458917], [127.13172, 35.84583]] },
    },
  ],
};

const DEMO_ROUTES = {
  standard: [[127.1319861, 35.8463514], [127.1319832, 35.8458917], [127.1312826, 35.845787]],
  before: [[127.1319861, 35.8463514], [127.1319832, 35.8458917], [127.13172, 35.84583], [127.1312826, 35.845787]],
  after: [[127.1319861, 35.8463514], [127.13212, 35.84602], [127.13191, 35.8457], [127.13152, 35.84561], [127.1312826, 35.845787]],
};

const state = {
  graph: null,
  nodes: new Map(),
  edges: new Map(),
  comparison: null,
  previousRoute: null,
  sessionId: sessionStorage.getItem("navi.routeSessionId"),
  dataSource: "loading",
  demoBlocked: false,
  map: null,
  mapLayers: [],
  mapBounds: null,
  routeLayer: "accessible",
  cameraStream: null,
  busy: false,
  toastTimer: null,
  rerouteTimer: null,
  returnFromReport: "plan",
  pendingAfterDemo: null,
  preferences: {
    profile: localStorage.getItem("navi.profile") || "wheelchair",
    voice: localStorage.getItem("navi.setting.voice") !== "false",
    haptics: localStorage.getItem("navi.setting.haptics") !== "false",
    contrast: localStorage.getItem("navi.setting.contrast") === "true",
  },
};

const elements = {};

document.addEventListener("DOMContentLoaded", () => {
  cacheElements();
  bindEvents();
  restorePreferences();
  initialize().catch(showError);
});

function pointFeature(nodeId, name, lat, lon) {
  return {
    type: "Feature",
    properties: { feature_type: "node", node_id: nodeId, name, display_selectable: true, source: "synthetic", verified: false },
    geometry: { type: "Point", coordinates: [lon, lat] },
  };
}

function cacheElements() {
  document.querySelectorAll("[id]").forEach((element) => { elements[element.id] = element; });
}

function bindEvents() {
  elements["enter-app-button"].addEventListener("click", () => {
    sessionStorage.setItem("navi.welcomed", "true");
    go("home");
  });

  elements["route-form"].addEventListener("submit", async (event) => {
    event.preventDefault();
    state.previousRoute = null;
    if (await compareRoutes()) go("route");
  });

  document.querySelectorAll("[data-go]").forEach((button) => {
    button.addEventListener("click", () => go(button.dataset.go));
  });
  document.querySelectorAll("[data-open-report]").forEach((button) => {
    button.addEventListener("click", () => openReport(button.dataset.openReport));
  });
  document.querySelectorAll("[data-profile]").forEach((button) => {
    button.addEventListener("click", () => selectProfile(button.dataset.profile));
  });
  document.querySelectorAll("[data-setting]").forEach((button) => {
    button.addEventListener("click", () => toggleSetting(button.dataset.setting));
  });
  document.querySelectorAll("[data-route-layer]").forEach((button) => {
    button.addEventListener("click", () => selectRouteLayer(button.dataset.routeLayer));
  });

  elements["swap-location-button"].addEventListener("click", swapLocations);
  elements["open-explain-button"].addEventListener("click", () => go("explain"));
  elements["start-ar-button"].addEventListener("click", () => go("navigate"));
  elements["exit-ar-button"].addEventListener("click", () => go("route"));
  elements["map-button"].addEventListener("click", () => go("route"));
  elements["open-report-from-plan"].addEventListener("click", () => openReport("plan"));
  elements["report-from-ar-button"].addEventListener("click", () => openReport("navigate"));
  elements["close-report-button"].addEventListener("click", closeReport);
  elements["return-after-report"].addEventListener("click", closeReport);
  elements["simulate-obstacle-button"].addEventListener("click", toggleObstacleDemo);
  elements["audio-button"].addEventListener("click", toggleAudio);
  elements["route-focus-button"].addEventListener("click", focusRouteMap);
  elements["report-note"].addEventListener("input", updateReportCount);
  elements["report-photo"].addEventListener("change", previewReportPhoto);
  elements["report-form"].addEventListener("submit", saveReportDraft);
  elements["continue-demo-button"].addEventListener("click", continueWithDemo);
  elements["retry-api-button"].addEventListener("click", retryApi);
  elements["replay-onboarding-button"].addEventListener("click", replayOnboarding);
  window.addEventListener("popstate", () => renderView(viewFromLocation(), { fromHistory: true }));
  window.addEventListener("pagehide", stopCamera);
}

async function initialize() {
  const welcomed = sessionStorage.getItem("navi.welcomed") === "true";
  const requested = viewFromLocation();
  const initial = requested === "welcome" && welcomed ? "home" : requested;
  elements["enter-app-button"].disabled = true;
  renderView(initial, { replace: true });
  await loadLiveData();
}

function restorePreferences() {
  document.querySelectorAll("[data-setting]").forEach((button) => {
    const enabled = Boolean(state.preferences[button.dataset.setting]);
    button.classList.toggle("is-on", enabled);
    button.setAttribute("aria-checked", String(enabled));
  });
  elements["mobile-app"].classList.toggle("high-contrast-route", state.preferences.contrast);
  if (elements["audio-button"]) {
    elements["audio-button"].setAttribute("aria-pressed", String(state.preferences.voice));
    elements["audio-button"].setAttribute("aria-label", `음성 안내 ${state.preferences.voice ? "켜짐" : "꺼짐"}`);
  }
}

function toggleSetting(key) {
  if (!(key in state.preferences)) return;
  state.preferences[key] = !state.preferences[key];
  localStorage.setItem(`navi.setting.${key}`, String(state.preferences[key]));
  restorePreferences();
  if (key === "voice") elements["audio-button"].setAttribute("aria-pressed", String(state.preferences.voice));
}

function replayOnboarding() {
  sessionStorage.removeItem("navi.welcomed");
  go("welcome");
}

function syncNavigation(view) {
  document.querySelectorAll("[data-nav]").forEach((item) => {
    const selected = item.dataset.nav === view;
    item.classList.toggle("is-active", selected);
    if (selected) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  });
}

function renderHomeSummary() {
  if (!state.comparison) {
    elements["home-recent-route"].dataset.go = "plan";
    return;
  }
  const destination = state.nodes.get(elements["destination-select"].value);
  elements["home-route-title"].textContent = destination?.properties?.name || "최근 접근 경로";
  elements["home-route-meta"].textContent = `${formatDistance(state.comparison.accessible.distance_m)} · 약 ${state.comparison.accessible.estimated_minutes}분`;
  elements["home-recent-route"].querySelector("small").textContent = "최근에 확인한 접근 경로";
  elements["home-recent-route"].dataset.go = "route";
}

async function loadLiveData() {
  setSource("loading");
  showToast("접근성 데이터를 연결하는 중입니다.");
  try {
    const graph = await api("/graph", {}, 8000);
    setGraph(graph);
    setSource("live");
    populateControls();
    elements["enter-app-button"].disabled = false;
    hideFallback();
    hideToast();
  } catch (error) {
    console.error(error);
    state.pendingAfterDemo = null;
    showFallback("실제 그래프 API를 불러오지 못했습니다. 화면 흐름은 명시된 합성 데모 데이터로 확인할 수 있습니다.");
  }
}

async function api(path, options = {}, timeoutMs = 10000) {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(path, {
      headers: { "Content-Type": "application/json", ...(options.headers || {}) },
      signal: controller.signal,
      ...options,
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const detail = Array.isArray(payload.detail) ? payload.detail[0]?.msg : payload.detail;
      const error = new Error(payload.message || detail || "요청을 처리하지 못했습니다.");
      error.status = payload.status || "request_failed";
      throw error;
    }
    return payload;
  } finally {
    window.clearTimeout(timeout);
  }
}

function setGraph(graph) {
  state.graph = graph;
  state.nodes.clear();
  state.edges.clear();
  graph.features.forEach((feature) => {
    const props = feature.properties || {};
    if (props.feature_type === "node") state.nodes.set(props.node_id, feature);
    if (props.feature_type === "edge") state.edges.set(props.edge_id, feature);
  });
}

function setSource(source) {
  state.dataSource = source;
  elements["mobile-app"].dataset.source = source;
  const labels = { loading: "연결 확인 중", live: "PoC API 연결", demo: "합성 데모 데이터" };
  document.querySelectorAll(".data-mode-copy").forEach((node) => { node.textContent = labels[source] || source; });
}

function activateDemo() {
  setGraph(JSON.parse(JSON.stringify(DEMO_GRAPH)));
  state.sessionId = "frontend-demo-session";
  state.demoBlocked = false;
  setSource("demo");
  populateControls();
  elements["enter-app-button"].disabled = false;
  hideFallback();
  showToast("합성 데모 데이터로 전환했습니다.", "success");
}

async function continueWithDemo() {
  const pending = state.pendingAfterDemo;
  activateDemo();
  if (pending === "route") {
    await compareRoutes();
    go("route");
  }
  state.pendingAfterDemo = null;
}

async function retryApi() {
  hideFallback();
  await loadLiveData();
}

function showFallback(message) {
  elements["fallback-copy"].textContent = message;
  elements["system-fallback"].hidden = false;
}

function hideFallback() {
  elements["system-fallback"].hidden = true;
}

function populateControls() {
  const demo = state.graph?.metadata?.demo;
  const selectable = [...state.nodes.values()].filter((feature) => feature.properties.display_selectable);
  const choices = selectable.length ? selectable : [...state.nodes.values()];
  const options = choices.map((feature) => `<option value="${escapeHtml(feature.properties.node_id)}">${escapeHtml(feature.properties.name || feature.properties.node_id)}</option>`).join("");
  elements["origin-select"].innerHTML = options;
  elements["destination-select"].innerHTML = options;
  if (demo) {
    elements["origin-select"].value = demo.origin_node;
    elements["destination-select"].value = demo.destination_node;
  }
  selectProfile(state.preferences.profile);
  updateDestinationTitle();
}

function selectProfile(profile) {
  state.preferences.profile = profile;
  localStorage.setItem("navi.profile", profile);
  elements["profile-select"].value = profile;
  document.querySelectorAll("[data-profile]").forEach((button) => {
    const selected = button.dataset.profile === profile;
    button.classList.toggle("is-selected", selected);
    button.setAttribute("aria-checked", String(selected));
  });
  elements["condition-list"].innerHTML = profile === "wheelchair"
    ? '<span><i aria-hidden="true"></i>계단 제외</span><span><i aria-hidden="true"></i>높은 턱 제외</span><span><i aria-hidden="true"></i>차단 구간 제외</span>'
    : '<span><i aria-hidden="true"></i>거리 중심</span><span><i aria-hidden="true"></i>차단 구간 제외</span>';
  elements["home-profile-name"].textContent = profile === "wheelchair" ? "휠체어 접근" : "기본 이동";
  elements["home-profile-detail"].textContent = profile === "wheelchair" ? "계단 · 높은 턱 · 차단 구간 제외" : "거리 · 차단 상태 우선";
}

function swapLocations() {
  const origin = elements["origin-select"].value;
  elements["origin-select"].value = elements["destination-select"].value;
  elements["destination-select"].value = origin;
  updateDestinationTitle();
  showToast("출발지와 목적지를 바꿨습니다.");
}

function viewFromLocation() {
  const view = window.location.hash.replace(/^#\/?/, "") || (sessionStorage.getItem("navi.welcomed") === "true" ? "home" : "welcome");
  return ["welcome", "home", "plan", "route", "explain", "navigate", "report", "settings"].includes(view) ? view : "home";
}

function go(view, { replace = false } = {}) {
  if (["route", "explain", "navigate"].includes(view) && !state.comparison) view = "plan";
  const url = `#/${view}`;
  if (replace) history.replaceState({ view }, "", url);
  else if (window.location.hash !== url) history.pushState({ view }, "", url);
  renderView(view);
}

function renderView(view, options = {}) {
  if (["route", "explain", "navigate"].includes(view) && !state.comparison) view = state.graph ? "plan" : "welcome";
  if (view !== "navigate") stopCamera();
  document.querySelectorAll(".app-view[data-view]").forEach((screen) => { screen.hidden = screen.dataset.view !== view; });
  elements["mobile-app"].dataset.mode = view;
  syncNavigation(view);

  if (view === "route") {
    ensureMap();
    renderComparison();
    window.setTimeout(() => { state.map?.invalidateSize(); focusRouteMap(); }, 80);
  }
  if (view === "explain") renderExplain();
  if (view === "navigate") {
    renderArState();
    startCamera();
  }
  if (view === "report") prepareReport();
  if (view === "home") renderHomeSummary();
  if (!options.fromHistory) window.scrollTo({ top: 0, behavior: "auto" });
}

function buildRoutePayload() {
  const origin = state.nodes.get(elements["origin-select"].value);
  const destination = state.nodes.get(elements["destination-select"].value);
  if (!origin || !destination) throw new Error("출발지와 목적지를 다시 선택해 주세요.");
  const [originLon, originLat] = origin.geometry.coordinates;
  const [destinationLon, destinationLat] = destination.geometry.coordinates;
  return {
    origin: { lat: originLat, lon: originLon },
    destination: { lat: destinationLat, lon: destinationLon },
    profile: elements["profile-select"].value,
    ...(state.dataSource === "live" && state.sessionId ? { session_id: state.sessionId } : {}),
  };
}

async function compareRoutes(silent = false) {
  if (!state.graph) {
    showFallback("경로 데이터가 아직 준비되지 않았습니다. 데모 데이터로 계속할 수 있습니다.");
    state.pendingAfterDemo = "route";
    return false;
  }
  setBusy(true);
  if (!silent) showToast("이동 조건을 반영해 두 경로를 비교하고 있습니다.");
  try {
    if (state.dataSource === "demo") {
      await new Promise((resolve) => window.setTimeout(resolve, 180));
      state.comparison = buildDemoComparison(state.demoBlocked, elements["profile-select"].value);
    } else {
      state.comparison = await api("/route/compare", { method: "POST", body: JSON.stringify(buildRoutePayload()) });
      state.sessionId = state.comparison.session_id;
      sessionStorage.setItem("navi.routeSessionId", state.sessionId);
    }
    renderComparison();
    if (!silent) hideToast();
    return true;
  } catch (error) {
    if (error.status === "route_session_not_found" && state.sessionId) {
      state.sessionId = null;
      sessionStorage.removeItem("navi.routeSessionId");
      setBusy(false);
      return compareRoutes(silent);
    }
    if (error.status === "no_accessible_route") {
      showToast("현재 조건에서 접근 가능한 경로가 없습니다.", "error");
      return false;
    }
    console.error(error);
    state.pendingAfterDemo = "route";
    showFallback("경로 계산 API에 연결하지 못했습니다. 합성 데모 경로로 전체 흐름을 계속할 수 있습니다.");
    return false;
  } finally {
    setBusy(false);
  }
}

function buildDemoComparison(blocked, profile) {
  const wheelchair = profile === "wheelchair";
  const standard = demoRoute("standard", 1081.9, 15, DEMO_ROUTES.standard, profile);
  const accessibleDistance = blocked ? 1736.3 : (wheelchair ? 1302.5 : 1081.9);
  const geometry = blocked ? DEMO_ROUTES.after : (wheelchair ? DEMO_ROUTES.before : DEMO_ROUTES.standard);
  const accessible = demoRoute("accessible", accessibleDistance, blocked ? 29 : (wheelchair ? 22 : 15), geometry, profile);
  accessible.reasons = blocked ? ["blocked", "stairs"] : (wheelchair ? ["stairs", "high_curb"] : []);
  accessible.excluded_edges = wheelchair ? [
    { edge_id: "DEMO-STAIRS", name: "계단 구간", reasons: ["stairs"] },
    ...(blocked ? [{ edge_id: "DEMO-E15", name: "공사 구간", reasons: ["blocked"] }] : []),
  ] : [];
  return {
    status: "ok",
    standard,
    accessible,
    difference_m: accessibleDistance - standard.distance_m,
    difference_pct: ((accessibleDistance - standard.distance_m) / standard.distance_m) * 100,
    reasons: accessible.reasons,
    warnings: ["frontend_demo_data", "synthetic_accessibility_attributes"],
    session_id: "frontend-demo-session",
    graph_revision: blocked ? 2 : 1,
  };
}

function demoRoute(type, distance, minutes, geometry, profile) {
  return {
    status: "ok",
    distance_m: distance,
    estimated_minutes: minutes,
    route_type: type,
    profile,
    origin_node: "DEMO-A",
    destination_node: "DEMO-B",
    node_ids: ["DEMO-A", "DEMO-M", "DEMO-B"],
    edge_ids: type === "standard" ? ["DEMO-S1", "DEMO-S2"] : ["DEMO-A1", "DEMO-E15", "DEMO-A2"],
    geometry,
    excluded_edges: [],
    reasons: [],
    warnings: ["synthetic_frontend_fallback"],
    provenance: { sources: ["synthetic"], accessibility_sources: ["synthetic"], contains_synthetic: true, verified_edges: 0, unverified_edges: 3 },
    session_id: "frontend-demo-session",
    graph_revision: state.demoBlocked ? 2 : 1,
  };
}

function renderComparison() {
  if (!state.comparison) return;
  const { standard, accessible } = state.comparison;
  const difference = accessible.distance_m - standard.distance_m;
  const provenance = accessible.provenance || {};
  const needsVerification = provenance.contains_synthetic || Number(provenance.unverified_edges || 0) > 0;
  elements["accessible-time"].textContent = `약 ${accessible.estimated_minutes}분`;
  elements["accessible-distance"].textContent = formatDistance(accessible.distance_m);
  elements["distance-delta"].textContent = `일반 경로보다 ${formatSignedDistance(difference)}`;
  elements["standard-distance"].textContent = formatDistance(standard.distance_m);
  elements["comparison-delta"].textContent = formatSignedDistance(difference);
  elements["remaining-distance"].textContent = formatDistance(accessible.distance_m);
  elements["arrival-time"].textContent = arrivalClock(accessible.estimated_minutes);
  elements["route-status-badge"].textContent = needsVerification ? "현장 확인 필요" : "검증된 경로";
  elements["route-status-badge"].className = `status-chip ${needsVerification ? "status-warning" : "status-success"}`;
  elements["route-notice"].hidden = !needsVerification;
  const wheelchair = elements["profile-select"].value === "wheelchair";
  elements["route-kicker"].textContent = wheelchair ? "휠체어 접근 경로" : "기본 이동 경로";
  elements["ar-profile-copy"].textContent = `${wheelchair ? "휠체어 · 계단 제외" : "기본 이동"} · ${needsVerification ? "현장 미검증" : "검증됨"}`;
  const reasons = collectReasons();
  elements["route-reason-inline"].textContent = reasons.length ? reasonCopy(reasons[0]).title : "거리 기준으로 계산했습니다.";
  elements["legend-previous"].hidden = !state.previousRoute;
  updateDestinationTitle();
  renderMapRoutes();
  renderExplain();
  syncObstacleState();
  renderHomeSummary();
}

function collectReasons() {
  const direct = state.comparison?.reasons || state.comparison?.accessible?.reasons || [];
  const excluded = (state.comparison?.accessible?.excluded_edges || []).flatMap((edge) => edge.reasons || []);
  return [...new Set([...direct, ...excluded].map(String))];
}

function reasonCopy(reason) {
  const key = String(reason).toLowerCase();
  if (key.includes("stair") || key.includes("계단")) return { title: "계단 구간을 피했어요", detail: "설정한 휠체어 이동 조건에서 계단 구간을 제외했습니다.", tone: "warning" };
  if (key.includes("block") || key.includes("construction") || key.includes("공사") || key.includes("차단")) return { title: "통행 제한 구간을 피했어요", detail: "현재 시연에서 차단된 구간을 제외하고 경로를 다시 계산했습니다.", tone: "danger" };
  if (key.includes("curb") || key.includes("턱")) return { title: "높은 턱 기준을 반영했어요", detail: "실험용 프리셋의 최대 턱 높이를 넘는 구간을 제외했습니다.", tone: "warning" };
  if (key.includes("slope") || key.includes("경사")) return { title: "급경사 구간을 피했어요", detail: "실험용 최대 경사 기준을 반영했습니다.", tone: "warning" };
  if (key.includes("width") || key.includes("폭")) return { title: "좁은 통행 구간을 피했어요", detail: "실험용 최소 통행 폭 기준을 반영했습니다.", tone: "warning" };
  return { title: humanize(reason), detail: "접근성 경로 엔진이 이 조건을 필수 통과 조건으로 반영했습니다.", tone: "warning" };
}

function renderExplain() {
  if (!state.comparison) return;
  const { standard, accessible } = state.comparison;
  const difference = accessible.distance_m - standard.distance_m;
  elements["explain-standard-distance"].textContent = formatDistance(standard.distance_m);
  elements["explain-accessible-distance"].textContent = formatDistance(accessible.distance_m);
  elements["explain-delta"].textContent = `${formatSignedDistance(difference)} 우회`;
  const reasons = collectReasons();
  elements["evidence-reasons"].innerHTML = (reasons.length ? reasons : ["distance_only"]).map((reason) => {
    const copy = reason === "distance_only"
      ? { title: "추가 접근성 제외 조건이 없습니다", detail: "현재 프로필에서는 거리 기준 경로와 동일합니다.", tone: "warning" }
      : reasonCopy(reason);
    return `<article class="evidence-item ${copy.tone === "danger" ? "danger" : ""}"><span aria-hidden="true">${copy.tone === "danger" ? "×" : "!"}</span><div><strong>${escapeHtml(copy.title)}</strong><small>${escapeHtml(copy.detail)}</small></div></article>`;
  }).join("");
  const provenance = accessible.provenance || {};
  elements["verified-count"].textContent = String(provenance.verified_edges ?? 0);
  elements["unverified-count"].textContent = String(provenance.unverified_edges ?? 0);
  const sources = [...new Set([...(provenance.sources || []), ...(provenance.accessibility_sources || [])])];
  elements["provenance-sources"].innerHTML = (sources.length ? sources : ["정보 없음"]).map((source) => `<span>${escapeHtml(sourceLabel(source))}</span>`).join("");
}

function sourceLabel(source) {
  const labels = { osm: "OSM 공간 데이터", osm_tags: "OSM 접근성 속성", manual: "수동 입력", public_data: "공공데이터", video_review: "영상 검수", ai_candidate: "AI 후보", synthetic: "합성 실험값", synthetic_frontend_fallback: "프론트 합성 데모" };
  return labels[source] || source;
}

function ensureMap() {
  if (state.map) return;
  if (!window.L) {
    elements["map-fallback"].hidden = false;
    return;
  }
  state.map = L.map("map", { zoomControl: true, preferCanvas: true, attributionControl: true });
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 20, attribution: "&copy; OpenStreetMap" }).addTo(state.map);
  renderMapRoutes();
}

function selectRouteLayer(layer) {
  state.routeLayer = layer;
  document.querySelectorAll("[data-route-layer]").forEach((button) => {
    const selected = button.dataset.routeLayer === layer;
    button.classList.toggle("is-selected", selected);
    button.setAttribute("aria-selected", String(selected));
  });
  renderMapRoutes();
}

function renderMapRoutes() {
  if (!state.map || !state.comparison) return;
  state.mapLayers.forEach((layer) => layer.remove());
  state.mapLayers = [];
  const { standard, accessible } = state.comparison;
  const accessibleFirst = state.routeLayer === "accessible";
  const colors = {
    ink: cssToken("--color-ink"),
    unknown: cssToken("--color-unknown"),
    violet: cssToken("--color-violet"),
    accent: cssToken("--color-accent"),
    danger: cssToken("--color-danger"),
  };
  drawRoute(standard, { color: accessibleFirst ? colors.unknown : colors.ink, weight: accessibleFirst ? 4 : 7, opacity: accessibleFirst ? .52 : .96, dashArray: "9 8", label: "일반 최단경로" });
  if (state.previousRoute) drawRoute(state.previousRoute, { color: colors.violet, weight: 6, opacity: .62, dashArray: "3 8", label: "변경 전 접근 경로" });
  drawRoute(accessible, { color: colors.accent, weight: accessibleFirst ? 7 : 4, opacity: accessibleFirst ? .96 : .42, label: "접근 가능 경로" });

  const demoEdge = state.edges.get(state.graph.metadata.demo.block_edge);
  if (demoEdge?.properties.blocked || state.demoBlocked) {
    const blocked = L.polyline(toLatLngs(demoEdge.geometry.coordinates), { color: colors.danger, weight: 9, opacity: .95, dashArray: "8 6" }).addTo(state.map).bindTooltip("공사로 통행 제한", { sticky: true });
    state.mapLayers.push(blocked);
  }
  [[accessible.origin_node, "A"], [accessible.destination_node, "B"]].forEach(([nodeId, label]) => {
    const node = state.nodes.get(nodeId);
    if (!node) return;
    const [lon, lat] = node.geometry.coordinates;
    const marker = L.marker([lat, lon], { icon: L.divIcon({ className: "route-endpoint-marker", html: `<span class="route-endpoint-icon"><b>${label}</b></span>`, iconSize: [44, 44], iconAnchor: [22, 39] }), zIndexOffset: 900 }).addTo(state.map);
    state.mapLayers.push(marker);
  });
  const bounded = state.mapLayers.filter((layer) => typeof layer.getBounds === "function" || typeof layer.getLatLng === "function");
  const bounds = L.featureGroup(bounded).getBounds();
  if (bounds.isValid()) state.mapBounds = bounds.pad(.16);
  focusRouteMap();
}

function drawRoute(route, style) {
  const latLngs = toLatLngs(route.geometry);
  const casing = L.polyline(latLngs, { color: cssToken("--color-surface"), weight: style.weight + 4, opacity: .86, interactive: false }).addTo(state.map);
  const line = L.polyline(latLngs, style).addTo(state.map).bindTooltip(`${style.label} · ${formatDistance(route.distance_m)}`);
  state.mapLayers.push(casing, line);
}

function focusRouteMap() {
  if (state.map && state.mapBounds?.isValid()) state.map.fitBounds(state.mapBounds, { animate: false, maxZoom: 17 });
}

async function startCamera() {
  elements["perception-copy"].textContent = "카메라 연결 중 · 분석은 시뮬레이션";
  if (!navigator.mediaDevices?.getUserMedia) {
    elements["perception-copy"].textContent = "카메라 미지원 · 정적 AR 시뮬레이션";
    return;
  }
  try {
    const request = navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" }, width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false });
    const timeout = new Promise((_, reject) => window.setTimeout(() => reject(new Error("camera_timeout")), 2600));
    state.cameraStream = await Promise.race([request, timeout]);
    elements["camera-feed"].srcObject = state.cameraStream;
    await elements["camera-feed"].play();
    elements["ar-screen"].classList.add("camera-ready");
    elements["camera-fallback"].hidden = true;
    elements["perception-copy"].textContent = "카메라 연결됨 · 영상 분석은 시뮬레이션";
  } catch (_error) {
    elements["perception-copy"].textContent = "카메라 없이 정적 AR 시뮬레이션";
    elements["camera-fallback"].hidden = false;
  }
}

function stopCamera() {
  if (state.cameraStream) state.cameraStream.getTracks().forEach((track) => track.stop());
  state.cameraStream = null;
  if (elements["camera-feed"]) elements["camera-feed"].srcObject = null;
  elements["ar-screen"]?.classList.remove("camera-ready");
  if (elements["camera-fallback"]) elements["camera-fallback"].hidden = false;
}

async function toggleObstacleDemo() {
  if (state.busy || !state.comparison) return;
  const edgeId = state.graph.metadata.demo.block_edge;
  const feature = state.edges.get(edgeId);
  const currentlyBlocked = state.dataSource === "demo" ? state.demoBlocked : feature?.properties.blocked === true;
  const blocked = !currentlyBlocked;
  setBusy(true);
  elements["perception-copy"].textContent = blocked ? "전방 통행 장애 후보를 반영하는 중" : "시연 상태를 초기화하는 중";
  try {
    if (state.dataSource === "demo") {
      state.previousRoute = blocked ? state.comparison.accessible : null;
      state.demoBlocked = blocked;
      feature.properties.blocked = blocked;
      state.comparison = buildDemoComparison(blocked, elements["profile-select"].value);
      await new Promise((resolve) => window.setTimeout(resolve, 260));
      renderComparison();
    } else {
      const result = await api(`/edges/${encodeURIComponent(edgeId)}/status`, {
        method: "PATCH",
        body: JSON.stringify({ blocked, reason: blocked ? "construction" : null, status_source: "manual", verified: false, actor: "모바일 PoC 시뮬레이션", session_id: state.sessionId }),
      });
      state.previousRoute = blocked ? result.previous_route : null;
      const graph = await api("/graph");
      setGraph(graph);
      await compareRoutes(true);
    }
    applyRerouteUi(blocked);
  } catch (error) {
    showError(error);
  } finally {
    setBusy(false);
  }
}

function applyRerouteUi(blocked) {
  elements["ar-screen"].classList.toggle("is-rerouting", blocked);
  elements["reroute-banner"].hidden = !blocked;
  elements["turn-distance"].textContent = blocked ? "120m" : "50m";
  elements["turn-copy"].textContent = blocked ? "새 경로에서 오른쪽으로 이동" : "앞에서 오른쪽 방향입니다";
  elements["perception-copy"].textContent = blocked ? "임시 장애물 반영 · 새 경로 안내 중" : "온디바이스 인식 시뮬레이션";
  window.clearTimeout(state.rerouteTimer);
  if (blocked) state.rerouteTimer = window.setTimeout(() => { elements["reroute-banner"].hidden = true; }, 3600);
  renderArState();
  if (!blocked) showToast("장애물 시연을 초기화했습니다.", "success");
}

function syncObstacleState() {
  if (!state.graph) return;
  const feature = state.edges.get(state.graph.metadata.demo.block_edge);
  const blocked = state.dataSource === "demo" ? state.demoBlocked : feature?.properties.blocked === true;
  elements["simulate-obstacle-button"].classList.toggle("is-reset", blocked);
  elements["obstacle-button-copy"].textContent = blocked ? "시연 초기화" : "장애물 감지 시연";
}

function renderArState() {
  if (!state.comparison) return;
  const accessible = state.comparison.accessible;
  elements["remaining-distance"].textContent = formatDistance(accessible.distance_m);
  elements["arrival-time"].textContent = arrivalClock(accessible.estimated_minutes);
  const wheelchair = elements["profile-select"].value === "wheelchair";
  const unverified = accessible.provenance?.contains_synthetic || Number(accessible.provenance?.unverified_edges || 0) > 0;
  elements["ar-profile-copy"].textContent = `${wheelchair ? "휠체어 · 계단 제외" : "기본 이동"} · ${unverified ? "현장 미검증" : "검증됨"}`;
  syncObstacleState();
}

function toggleAudio() {
  state.preferences.voice = !state.preferences.voice;
  localStorage.setItem("navi.setting.voice", String(state.preferences.voice));
  restorePreferences();
  elements["audio-button"].setAttribute("aria-pressed", String(state.preferences.voice));
  elements["audio-button"].setAttribute("aria-label", `음성 안내 ${state.preferences.voice ? "켜짐" : "꺼짐"}`);
  showToast(`음성 안내를 ${state.preferences.voice ? "켰습니다" : "껐습니다"}.`);
}

function openReport(returnView) {
  state.returnFromReport = returnView;
  go("report");
}

function closeReport() {
  go(state.returnFromReport || "plan");
}

function prepareReport() {
  elements["report-form"].hidden = false;
  elements["report-complete"].hidden = true;
  const destination = state.nodes.get(elements["destination-select"].value);
  elements["report-location-title"].textContent = destination?.properties?.name || "현재 경로 주변";
}

function updateReportCount() {
  elements["report-note-count"].textContent = String(elements["report-note"].value.length);
}

function previewReportPhoto() {
  const file = elements["report-photo"].files?.[0];
  const preview = elements["photo-preview"];
  if (!file) {
    preview.classList.remove("has-image");
    preview.style.backgroundImage = "";
    return;
  }
  const reader = new FileReader();
  reader.addEventListener("load", () => {
    preview.style.backgroundImage = `url("${reader.result}")`;
    preview.classList.add("has-image");
  });
  reader.readAsDataURL(file);
}

function saveReportDraft(event) {
  event.preventDefault();
  if (state.busy) return;
  const form = new FormData(elements["report-form"]);
  const type = form.get("report-type");
  if (!type) {
    showToast("현장 상황 유형을 선택해 주세요.", "error");
    return;
  }
  setBusy(true);
  try {
    const existing = JSON.parse(localStorage.getItem("navi.reportDrafts") || "[]");
    const draft = {
      draft_id: `local-${Date.now()}`,
      type,
      note: elements["report-note"].value.trim(),
      image_name: elements["report-photo"].files?.[0]?.name || null,
      destination_node: elements["destination-select"].value || null,
      route_session_id: state.sessionId,
      status: "local_draft",
      graph_applied: false,
      created_at: new Date().toISOString(),
    };
    localStorage.setItem("navi.reportDrafts", JSON.stringify([draft, ...existing].slice(0, 10)));
    elements["report-form"].hidden = true;
    elements["report-complete"].hidden = false;
    hideToast();
  } catch (_error) {
    showToast("기기 저장 공간을 사용할 수 없습니다.", "error");
  } finally {
    setBusy(false);
  }
}

function updateDestinationTitle() {
  const feature = state.nodes.get(elements["destination-select"].value);
  if (feature) elements["route-title"].textContent = feature.properties.name || feature.properties.node_id;
}

function setBusy(busy) {
  state.busy = busy;
  ["find-route-button", "start-ar-button", "simulate-obstacle-button", "save-report-button"].forEach((id) => {
    if (elements[id]) elements[id].disabled = busy;
  });
}

function showToast(message, tone = "info") {
  window.clearTimeout(state.toastTimer);
  elements["system-status"].textContent = message;
  elements["system-status"].dataset.tone = tone;
  elements["system-status"].classList.add("is-visible");
  state.toastTimer = window.setTimeout(() => elements["system-status"].classList.remove("is-visible"), 3200);
}

function hideToast() {
  window.clearTimeout(state.toastTimer);
  elements["system-status"].classList.remove("is-visible");
}

function showError(error) {
  console.error(error);
  showToast(error.message || "예상하지 못한 오류가 발생했습니다.", "error");
}

function toLatLngs(coordinates) { return coordinates.map(([lon, lat]) => [lat, lon]); }
function formatDistance(value) { return value === null || value === undefined || Number.isNaN(Number(value)) ? "—" : `${Math.round(Number(value)).toLocaleString("ko-KR")}m`; }
function formatSignedDistance(value) { const rounded = Math.round(Number(value)); return `${rounded >= 0 ? "+" : ""}${rounded.toLocaleString("ko-KR")}m`; }
function arrivalClock(minutes) { return new Intl.DateTimeFormat("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(Date.now() + minutes * 60000)); }
function humanize(value) { return String(value).replaceAll("_", " "); }
function escapeHtml(value) { return String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;"); }
function cssToken(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }
