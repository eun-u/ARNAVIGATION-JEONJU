package kr.co.navi.mobility.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.navi.mobility.data.NaviSessionState
import kr.co.navi.mobility.ui.PlanViewModel
import kr.co.navi.mobility.ui.RouteViewModel
import kr.co.navi.mobility.ui.components.DemoDivider
import kr.co.navi.mobility.ui.components.DemoDot
import kr.co.navi.mobility.ui.components.DemoGlassCard
import kr.co.navi.mobility.ui.components.DemoSegment
import kr.co.navi.mobility.ui.components.DemoSymbol
import kr.co.navi.mobility.ui.components.DemoVectorIcon
import kr.co.navi.mobility.ui.components.FrontendDemoPage
import kr.co.navi.mobility.ui.components.FrontendDemoTopBar
import kr.co.navi.mobility.ui.components.PrimaryActionButton
import kr.co.navi.mobility.ui.components.SecondaryActionButton
import kr.co.navi.mobility.ui.theme.NaviBlock
import kr.co.navi.mobility.ui.theme.NaviBlockSoft
import kr.co.navi.mobility.ui.theme.NaviBlue
import kr.co.navi.mobility.ui.theme.NaviBlueSoft
import kr.co.navi.mobility.ui.theme.NaviCaution
import kr.co.navi.mobility.ui.theme.NaviCautionSoft
import kr.co.navi.mobility.ui.theme.NaviGlass
import kr.co.navi.mobility.ui.theme.NaviInk
import kr.co.navi.mobility.ui.theme.NaviInkMuted
import kr.co.navi.mobility.ui.theme.NaviInkSoft
import kr.co.navi.mobility.ui.theme.NaviLine
import kr.co.navi.mobility.ui.theme.NaviPass
import kr.co.navi.mobility.ui.theme.NaviPassSoft
import kr.co.navi.mobility.ui.theme.NaviSurfaceRaised
import kr.co.navi.mobility.ui.theme.NaviViolet
import kr.co.navi.mobility.ui.theme.NaviVioletSoft

@Composable
fun FrontendPlanScreen(
    viewModel: PlanViewModel,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onRouteReady: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.routeReady.collect { onRouteReady() }
    }
    FrontendDemoPage {
        Column(Modifier.fillMaxSize()) {
            FrontendDemoTopBar("경로 설정", onBack = onBack)
            Column(Modifier.weight(1f)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    DemoGlassCard {
                        Column {
                            JourneySummaryRow(NaviBlue, "출발", "현재 위치 · 전북대학교 정문", showSwap = true)
                            DemoDivider(Modifier.padding(vertical = 8.dp))
                            JourneySummaryRow(NaviBlock, "도착", "전북대학교 중앙도서관", showSwap = false)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("＋  경유지 추가", style = MaterialTheme.typography.labelMedium, color = NaviBlue)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("적용 중인 이동 조건", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                        Text("수정", modifier = Modifier.clickable(onClick = onEditProfile).padding(8.dp), style = MaterialTheme.typography.labelMedium, color = NaviBlue)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("수동 휠체어", "계단 제외", "턱 2cm").forEachIndexed { index, text ->
                            DemoConditionChip(text, index == 0)
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        DemoConditionChip("경사 8%", false)
                        DemoConditionChip("엘리베이터 필요", false)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("출발 시각", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DemoSegment("지금 출발", true, {}, Modifier.weight(1f))
                        DemoSegment("시간 지정", false, {}, Modifier.weight(1f))
                    }
                    if (uiState.error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            uiState.error.orEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NaviCautionSoft, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = NaviCaution,
                        )
                    }
                }
                DemoRouteMap(
                    mode = DemoMapMode.Plan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
            Surface(color = NaviGlass.InteractiveFallback, shadowElevation = 8.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    PrimaryActionButton(
                        text = "경로 찾기",
                        onClick = viewModel::findRoute,
                        enabled = session.origin != null && session.destination != null && !uiState.loading,
                        loading = uiState.submitting,
                    )
                }
            }
        }
    }
}

@Composable
private fun JourneySummaryRow(color: Color, label: String, title: String, showSwap: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DemoDot(color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
            Text(title, style = MaterialTheme.typography.bodyMedium, color = NaviInk)
        }
        if (showSwap) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(NaviGlass.InteractiveFallback, CircleShape)
                    .border(1.dp, NaviLine, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DemoVectorIcon(DemoSymbol.Swap, "출발지와 도착지 교환", Modifier.size(22.dp), NaviInkMuted)
            }
        }
    }
}

@Composable
private fun DemoConditionChip(text: String, selected: Boolean) {
    Text(
        text,
        modifier = Modifier
            .background(if (selected) NaviBlueSoft else NaviGlass.InteractiveFallback, RoundedCornerShape(18.dp))
            .border(1.dp, if (selected) NaviBlue.copy(alpha = 0.35f) else NaviLine, RoundedCornerShape(18.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) NaviBlue else NaviInkSoft,
    )
}

@Composable
fun FrontendRouteResultScreen(
    viewModel: RouteViewModel,
    onBack: () -> Unit,
    onExplain: () -> Unit,
    onStart: () -> Unit,
) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    FrontendDemoPage {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                DemoRouteMap(DemoMapMode.Result, Modifier.fillMaxSize())
                FrontendDemoTopBar(
                    title = "경로 결과",
                    onBack = onBack,
                    trailing = {
                        Box(
                            Modifier
                                .background(NaviGlass.InteractiveFallback, RoundedCornerShape(14.dp))
                                .padding(horizontal = 11.dp, vertical = 7.dp),
                        ) {
                            Text("실데이터 연결", style = MaterialTheme.typography.labelMedium, color = NaviPass)
                        }
                    },
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 62.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DemoConditionChip("접근 가능", true)
                    DemoConditionChip("일반 최단", false)
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MapLegend(NaviBlue, "접근 가능")
                    MapLegend(NaviInkMuted, "일반")
                    MapLegend(NaviBlock, "제외")
                }
            }
            Surface(
                color = NaviGlass.InteractiveFallback,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                shadowElevation = 14.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).background(NaviInk.copy(alpha = 0.16f), CircleShape))
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("휠체어 접근 경로", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                        Text(
                            "⚠ 현장 확인 필요",
                            modifier = Modifier.background(NaviCautionSoft, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = NaviCaution,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("전북대학교 중앙도서관", style = MaterialTheme.typography.headlineSmall, color = NaviInk)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("22", style = MaterialTheme.typography.displaySmall, color = NaviBlue)
                        Text("분", modifier = Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.titleMedium, color = NaviBlue)
                        Spacer(Modifier.width(12.dp))
                        Text("1,303m", modifier = Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.titleMedium, color = NaviInk)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricSummary("일반 경로보다", "+221m", NaviCaution, Modifier.weight(1f))
                        MetricSummary("제외한 구간", "계단 2곳", NaviBlock, Modifier.weight(1f))
                        MetricSummary("검증된 구간", "6 / 18", NaviPass, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    DemoGlassCard(onClick = onExplain) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("◇", style = MaterialTheme.typography.titleMedium, color = NaviViolet)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("왜 이 경로인가요?", style = MaterialTheme.typography.bodyMedium, color = NaviInk)
                                Text("제외한 구간과 데이터 출처 보기", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                            }
                            Text("›", style = MaterialTheme.typography.titleLarge, color = NaviInkMuted)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PrimaryActionButton("안내 시작", onStart)
                }
            }
        }
    }
}

@Composable
private fun MapLegend(color: Color, text: String) {
    Row(
        Modifier
            .background(NaviGlass.InteractiveFallback, RoundedCornerShape(14.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (text == "제외") {
            Canvas(Modifier.size(11.dp)) {
                val marker = Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width / 2f, size.height)
                    lineTo(0f, size.height / 2f)
                    close()
                }
                drawPath(marker, color)
            }
        } else {
            Box(Modifier.size(width = 16.dp, height = 4.dp).background(color, CircleShape))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = NaviInkSoft)
    }
}

@Composable
private fun MetricSummary(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.60f), RoundedCornerShape(12.dp))
            .padding(9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
fun FrontendExplainScreen(
    viewModel: RouteViewModel,
    onBack: () -> Unit,
    onReport: () -> Unit,
    onStart: () -> Unit,
) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    FrontendDemoPage {
        Column(Modifier.fillMaxSize()) {
            FrontendDemoTopBar("추천 근거", onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricSummary("일반 최단", "1,082m", NaviInkMuted, Modifier.weight(1f))
                    Box(
                        Modifier
                            .weight(1f)
                            .background(NaviBlueSoft, RoundedCornerShape(14.dp))
                            .border(1.dp, NaviBlue, RoundedCornerShape(14.dp))
                            .padding(10.dp),
                    ) {
                        Column {
                            Text("접근 가능", style = MaterialTheme.typography.labelMedium, color = NaviBlue)
                            Text("1,303m", style = MaterialTheme.typography.titleLarge, color = NaviBlue)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("이 구간을 제외했어요", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                Spacer(Modifier.height(6.dp))
                ExcludedSection("백제대로 보행 연결부 계단", "합성 데모 · 실증 데이터 연결 전에는 통과 여부를 확정하지 않음")
                DemoDivider()
                ExcludedSection("중앙도서관 북측 계단", "합성 데모 · 우회 거리는 실증 데이터 연결 후 계산")
                Spacer(Modifier.height(12.dp))
                Text("데이터 신뢰 수준", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                Spacer(Modifier.height(7.dp))
                DemoGlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TrustCountRow(NaviPass, "사람이 확인한 구간", "6")
                        TrustCountRow(NaviCaution, "공공데이터 기반 추정", "7")
                        TrustCountRow(NaviInkMuted, "정보 없음 · 합성 속성", "5")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(NaviBlueSoft, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        "ⓘ AI가 찾은 후보와 사람이 검수한 결과는 같은 신뢰 수준으로 표시하지 않아요. 승인 전 후보는 경로 계산에 쓰지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NaviBlue,
                    )
                }
                session.activeRoute?.warnings.orEmpty().take(1).forEach { warning ->
                    Spacer(Modifier.height(8.dp))
                    Text(warning, style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(NaviGlass.InteractiveFallback)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryActionButton("정보가 달라요", onReport, Modifier.weight(1f))
                PrimaryActionButton("안내 시작", onStart, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ExcludedSection(title: String, body: String) {
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⌁", style = MaterialTheme.typography.titleMedium, color = NaviBlock)
            Spacer(Modifier.width(8.dp))
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = NaviInk)
            Text("통행 제한", modifier = Modifier.background(NaviBlockSoft, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = NaviBlock)
        }
        Text(body, style = MaterialTheme.typography.bodySmall, color = NaviInkSoft)
        Text("출처 OSM 스냅샷 · 미검증(합성 속성) · 최종 확인 2026-09-15", style = MaterialTheme.typography.labelMedium, color = NaviCaution)
    }
}

@Composable
private fun TrustCountRow(color: Color, label: String, count: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DemoDot(color)
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = NaviInkSoft)
        Text(count, style = MaterialTheme.typography.titleMedium, color = NaviInk)
    }
}

@Composable
fun FrontendRerouteScreen(
    session: NaviSessionState,
    onKeep: () -> Unit,
    onContinue: () -> Unit,
) {
    FrontendDemoPage {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                DemoRouteMap(DemoMapMode.Reroute, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .background(NaviBlock, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Column {
                        Text("⚠ 앞 구간을 지날 수 없다고 하셨어요", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text("전북대 정문 보행로 공사 후보 · 방금 제보", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.84f))
                    }
                }
            }
            Surface(
                color = NaviGlass.InteractiveFallback,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                shadowElevation = 14.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("재탐색 완료", modifier = Modifier.background(NaviVioletSoft, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = NaviViolet)
                        Spacer(Modifier.width(8.dp))
                        Text("현재 세션에만 적용", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("다른 길을 찾았어요.", style = MaterialTheme.typography.headlineSmall, color = NaviInk)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricSummary("이전 경로", "1,303m · 22분", NaviInkMuted, Modifier.weight(1f))
                        Box(
                            Modifier
                                .weight(1f)
                                .background(NaviVioletSoft, RoundedCornerShape(14.dp))
                                .border(1.dp, NaviViolet, RoundedCornerShape(14.dp))
                                .padding(9.dp),
                        ) {
                            Column {
                                Text("새 경로", style = MaterialTheme.typography.labelMedium, color = NaviViolet)
                                Text("1,736m · 29분", style = MaterialTheme.typography.titleMedium, color = NaviViolet)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("+433m   |   +7분   |   차단 구간 1곳 우회", style = MaterialTheme.typography.labelMedium, color = NaviCaution)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        buildAnnotatedString {
                            append("ⓘ 이 차단은 ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("이번 이동에만") }
                            append(" 반영돼요. 사람 검수를 통과하기 전에는 다른 사용자의 경로를 바꾸지 않습니다.")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NaviCautionSoft, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = NaviCaution,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryActionButton("기존 유지", onKeep, Modifier.weight(1f))
                        PrimaryActionButton("새 경로로 계속", onContinue, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun FrontendArrivalScreen(
    onHome: () -> Unit,
    onSave: () -> Unit,
) {
    FrontendDemoPage {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(26.dp))
            Box(Modifier.size(86.dp).background(NaviPassSoft, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                DemoVectorIcon(DemoSymbol.Check, "도착 완료", Modifier.size(48.dp), NaviPass)
            }
            Spacer(Modifier.height(18.dp))
            Text("전북대학교 중앙도서관에\n도착했어요.", style = MaterialTheme.typography.headlineMedium, color = NaviInk)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricSummary("실제 이동", "26분", NaviInk, Modifier.weight(1f))
                MetricSummary("이동 거리", "1,736m", NaviInk, Modifier.weight(1f))
                MetricSummary("재탐색", "1회", NaviInk, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Text("이 경로, 실제로 지날 수 있었나요?", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = NaviInk)
            Text("답변은 구간 신뢰도를 올리는 데만 쓰이고 바로 반영되지 않아요.", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("문제 없었어요" to NaviPass, "조금 어려웠어요" to NaviCaution, "못 지났어요" to NaviBlock).forEach { (label, color) ->
                    Text(
                        label,
                        modifier = Modifier
                            .weight(1f)
                            .background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            DemoGlassCard(onClick = onSave) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("도착지 출입구 정보를 알려주세요", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = NaviInk)
                        Text("›", style = MaterialTheme.typography.titleLarge, color = NaviCaution)
                    }
                    Text("이 건물 출입구는 아직 정보가 없어요", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                }
            }
            Spacer(Modifier.weight(1f))
            PrimaryActionButton("홈으로", onHome)
            Spacer(Modifier.height(4.dp))
            Text("이동 기록에 저장하기", modifier = Modifier.clickable(onClick = onSave).padding(12.dp), style = MaterialTheme.typography.labelMedium, color = NaviBlue)
        }
    }
}

private enum class DemoMapMode { Plan, Result, Reroute }

@Composable
private fun DemoRouteMap(mode: DemoMapMode, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFFE9EEF3))) {
        val road = Color.White.copy(alpha = 0.92f)
        val block = Color(0xFFDDE4EB)
        val park = Color(0xFFDDEEDC)
        val cellW = size.width / 3.4f
        val cellH = size.height / 4.7f
        for (row in 0..4) {
            for (col in 0..3) {
                val left = col * cellW + 8f
                val top = row * cellH + 8f
                val fill = if (col == 3 && row >= 2) park else block
                drawRect(fill, Offset(left, top), Size(cellW - 18f, cellH - 18f))
            }
        }
        for (col in 1..3) drawRect(road, Offset(col * cellW - 10f, 0f), Size(20f, size.height))
        for (row in 1..4) drawRect(road, Offset(0f, row * cellH - 10f), Size(size.width, 20f))

        val standard = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.86f)
            lineTo(size.width * 0.28f, size.height * 0.55f)
            lineTo(size.width * 0.75f, size.height * 0.55f)
            lineTo(size.width * 0.75f, size.height * 0.22f)
        }
        drawPath(
            standard,
            NaviInkMuted.copy(alpha = 0.62f),
            style = Stroke(width = 7f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)), cap = StrokeCap.Round),
        )
        val path = Path().apply {
            moveTo(size.width * 0.20f, size.height * 0.86f)
            lineTo(size.width * 0.20f, size.height * 0.68f)
            lineTo(size.width * 0.48f, size.height * 0.68f)
            if (mode == DemoMapMode.Reroute) {
                lineTo(size.width * 0.48f, size.height * 0.38f)
                lineTo(size.width * 0.88f, size.height * 0.38f)
            } else {
                lineTo(size.width * 0.68f, size.height * 0.68f)
                lineTo(size.width * 0.68f, size.height * 0.44f)
                lineTo(size.width * 0.84f, size.height * 0.44f)
            }
            lineTo(size.width * 0.84f, size.height * 0.18f)
        }
        drawPath(path, if (mode == DemoMapMode.Reroute) NaviViolet else NaviBlue, style = Stroke(width = 10f, cap = StrokeCap.Round))
        drawCircle(NaviBlue, 10f, Offset(size.width * 0.20f, size.height * 0.86f))
        drawCircle(if (mode == DemoMapMode.Reroute) NaviInk else NaviBlock, 11f, Offset(size.width * 0.84f, size.height * 0.18f))
        if (mode != DemoMapMode.Plan) {
            fun excludedMarker(center: Offset) {
                val r = 14f
                val marker = Path().apply {
                    moveTo(center.x, center.y - r)
                    lineTo(center.x + r, center.y)
                    lineTo(center.x, center.y + r)
                    lineTo(center.x - r, center.y)
                    close()
                }
                drawPath(marker, NaviBlock)
                drawLine(Color.White, Offset(center.x, center.y - 6f), Offset(center.x, center.y + 2f), 3f, StrokeCap.Round)
                drawCircle(Color.White, 2f, Offset(center.x, center.y + 7f))
            }
            excludedMarker(Offset(size.width * 0.28f, size.height * 0.55f))
            excludedMarker(Offset(size.width * 0.75f, size.height * 0.55f))
        }
    }
}
