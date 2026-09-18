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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import kr.co.navi.mobility.ui.components.DemoDivider
import kr.co.navi.mobility.ui.components.DemoGlassCard
import kr.co.navi.mobility.ui.components.DemoSymbol
import kr.co.navi.mobility.ui.components.DemoVectorIcon
import kr.co.navi.mobility.ui.components.DemoSegment
import kr.co.navi.mobility.ui.components.FrontendDemoPage
import kr.co.navi.mobility.ui.components.FrontendDemoTopBar
import kr.co.navi.mobility.ui.components.PrimaryActionButton
import kr.co.navi.mobility.ui.components.PrismaticNaviMark
import kr.co.navi.mobility.ui.components.SecondaryActionButton
import kr.co.navi.mobility.ui.theme.NaviBlock
import kr.co.navi.mobility.ui.theme.NaviBlue
import kr.co.navi.mobility.ui.theme.NaviBlueSoft
import kr.co.navi.mobility.ui.theme.NaviCaution
import kr.co.navi.mobility.ui.theme.NaviCyan
import kr.co.navi.mobility.ui.theme.NaviDimens
import kr.co.navi.mobility.ui.theme.NaviGlass
import kr.co.navi.mobility.ui.theme.NaviIndigo
import kr.co.navi.mobility.ui.theme.NaviInk
import kr.co.navi.mobility.ui.theme.NaviInkMuted
import kr.co.navi.mobility.ui.theme.NaviInkSoft
import kr.co.navi.mobility.ui.theme.NaviLine
import kr.co.navi.mobility.ui.theme.NaviPass
import kr.co.navi.mobility.ui.theme.NaviPassSoft
import kr.co.navi.mobility.ui.theme.NaviViolet
import kr.co.navi.mobility.ui.theme.NaviVioletSoft

@Composable
fun FrontendSplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        // Android 12+ 시스템 스플래시가 걷힌 뒤에도 ZIP의 01 화면이 충분히 보이도록 유지한다.
        delay(2_500)
        onFinished()
    }
    FrontendDemoPage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.88f))
            PrismaticNaviMark(Modifier.size(112.dp))
            Spacer(Modifier.height(22.dp))
            Text("NaVi", style = MaterialTheme.typography.headlineLarge, color = NaviInk)
            Spacer(Modifier.height(10.dp))
            Text(
                "빛이 지나는 길만\n먼저 보여드릴게요.",
                style = MaterialTheme.typography.bodyLarge,
                color = NaviInkSoft,
            )
            Spacer(Modifier.weight(1.35f))
            DemoGlassCard(radius = 20) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(NaviLine, CircleShape),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.62f)
                                .height(3.dp)
                                .background(
                                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(NaviBlue, NaviViolet),
                                    ),
                                    CircleShape,
                                ),
                        )
                    }
                    Text("접근성 경로 데이터를 준비하고 있어요", style = MaterialTheme.typography.bodySmall, color = NaviInkSoft)
                    Text(
                        "연구용 PoC · 전북대 전주캠퍼스 · 일부 접근성 정보는 검증 전 데이터예요  v0.4.0",
                        style = MaterialTheme.typography.labelMedium,
                        color = NaviInkMuted,
                    )
                }
            }
        }
    }
}

@Composable
fun FrontendOnboardingScreen(
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    FrontendDemoPage {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp),
        ) {
            Text(
                "건너뛰기",
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(onClick = onSkip)
                    .padding(10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = NaviInkMuted,
            )
            Spacer(Modifier.height(4.dp))
            DemoGlassCard(modifier = Modifier.height(278.dp), radius = 26) {
                OnboardingRouteIllustration(Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DemoGlassCard(modifier = Modifier.weight(1f), radius = 18) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("일반 최단", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                        Text("1,082m", style = MaterialTheme.typography.titleLarge, color = NaviInkMuted)
                        Text("계단 2곳 · 통과 불가", style = MaterialTheme.typography.labelMedium, color = NaviBlock)
                    }
                }
                DemoGlassCard(modifier = Modifier.weight(1f), radius = 18) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("접근 가능", style = MaterialTheme.typography.labelMedium, color = NaviBlue)
                        Text("1,303m", style = MaterialTheme.typography.titleLarge, color = NaviBlue)
                        Text("+221m · 끝까지 통과", style = MaterialTheme.typography.labelMedium, color = NaviBlue)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "돌아가는 이유까지\n같이 알려드려요.",
                style = MaterialTheme.typography.headlineMedium,
                color = NaviInk,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "어떤 구간을 왜 피했는지, 그 정보가 현장에서 확인된 것인지 화면에서 바로 볼 수 있어요.",
                style = MaterialTheme.typography.bodyLarge,
                color = NaviInkSoft,
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(NaviLine, CircleShape))
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(width = 22.dp, height = 7.dp).background(NaviViolet, CircleShape))
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(7.dp).background(NaviLine, CircleShape))
            }
            Spacer(Modifier.height(18.dp))
            PrimaryActionButton("다음", onNext)
        }
    }
}

@Composable
private fun OnboardingRouteIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color(0xFFE8EDF4), topLeft = Offset(size.width * 0.03f, size.height * 0.04f), size = Size(size.width * 0.28f, size.height * 0.20f))
        drawRect(Color(0xFFE7E3F7), topLeft = Offset(size.width * 0.55f, size.height * 0.05f), size = Size(size.width * 0.39f, size.height * 0.23f))
        drawRect(Color(0xFFE1F1F3), topLeft = Offset(size.width * 0.04f, size.height * 0.55f), size = Size(size.width * 0.35f, size.height * 0.30f))
        drawRect(Color(0xFFE2E7EE), topLeft = Offset(size.width * 0.63f, size.height * 0.70f), size = Size(size.width * 0.30f, size.height * 0.17f))
        val dashed = PathEffect.dashPathEffect(floatArrayOf(8f, 12f))
        drawLine(Color(0xFFAFB9C8), Offset(size.width * 0.18f, size.height * 0.78f), Offset(size.width * 0.18f, size.height * 0.63f), 6f, StrokeCap.Round, dashed)
        drawLine(Color(0xFFAFB9C8), Offset(size.width * 0.18f, size.height * 0.63f), Offset(size.width * 0.74f, size.height * 0.63f), 6f, StrokeCap.Round, dashed)
        drawLine(Color(0xFFAFB9C8), Offset(size.width * 0.74f, size.height * 0.63f), Offset(size.width * 0.74f, size.height * 0.28f), 6f, StrokeCap.Round, dashed)
        drawLine(NaviViolet, Offset(size.width * 0.17f, size.height * 0.79f), Offset(size.width * 0.17f, size.height * 0.52f), 10f, StrokeCap.Round)
        drawLine(NaviBlue, Offset(size.width * 0.17f, size.height * 0.52f), Offset(size.width * 0.57f, size.height * 0.52f), 10f, StrokeCap.Round)
        drawLine(NaviBlue, Offset(size.width * 0.57f, size.height * 0.52f), Offset(size.width * 0.57f, size.height * 0.14f), 10f, StrokeCap.Round)
        drawLine(NaviBlue, Offset(size.width * 0.57f, size.height * 0.14f), Offset(size.width * 0.91f, size.height * 0.14f), 10f, StrokeCap.Round)
        drawCircle(NaviBlue, 10f, Offset(size.width * 0.17f, size.height * 0.79f))
        drawCircle(NaviInk, 12f, Offset(size.width * 0.91f, size.height * 0.14f))
        drawRoundRect(
            NaviBlock,
            topLeft = Offset(size.width * 0.53f, size.height * 0.54f),
            size = Size(size.width * 0.18f, size.height * 0.16f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f),
        )
        val stairLeft = size.width * 0.56f
        val stairTop = size.height * 0.575f
        val stairStepX = size.width * 0.026f
        val stairStepY = size.height * 0.025f
        for (step in 0..3) {
            drawLine(
                Color.White,
                Offset(stairLeft + stairStepX * step, stairTop + stairStepY * (3 - step)),
                Offset(stairLeft + stairStepX * (step + 1), stairTop + stairStepY * (3 - step)),
                strokeWidth = 4f,
                cap = StrokeCap.Square,
            )
            if (step < 3) {
                drawLine(
                    Color.White,
                    Offset(stairLeft + stairStepX * (step + 1), stairTop + stairStepY * (3 - step)),
                    Offset(stairLeft + stairStepX * (step + 1), stairTop + stairStepY * (2 - step)),
                    strokeWidth = 4f,
                    cap = StrokeCap.Square,
                )
            }
        }
    }
}

@Composable
fun FrontendPermissionScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onLater: () -> Unit,
) {
    FrontendDemoPage {
        Column(Modifier.fillMaxSize()) {
            FrontendDemoTopBar(title = "", onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("필요한 권한만\n필요한 순간에.", style = MaterialTheme.typography.headlineMedium, color = NaviInk)
                Spacer(Modifier.height(8.dp))
                Text(
                    "지금 모두 허용하지 않아도 괜찮아요. 거부해도 쓸 수 있는 방법을 함께 적어 두었어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NaviInkSoft,
                )
                Spacer(Modifier.height(16.dp))
                PermissionCard(DemoSymbol.Pin, "위치", "필수", "현재 위치에서 출발지를 잡고 이동 중 남은 거리를 계산해요. 앱 사용 중에만 받고 서버에 저장하지 않아요.", NaviBlue, NaviBlueSoft)
                Spacer(Modifier.height(14.dp))
                PermissionCard(DemoSymbol.Camera, "카메라", "AR 안내에만", "카메라 화면 위에 방향을 겹쳐 보여줄 때만 켜요. 영상은 저장·전송·AI 판독하지 않아요.\n거부해도 지도 안내로 끝까지 이동할 수 있어요.", NaviViolet, NaviVioletSoft)
                Spacer(Modifier.height(14.dp))
                PermissionCard(DemoSymbol.Notification, "알림", "선택", "내 제보가 검수되면 결과를 알려드려요. 나중에 설정에서 켤 수 있어요.", NaviInkSoft, Color(0xFFF4F6FA))
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, NaviLine, RoundedCornerShape(14.dp))
                        .background(NaviGlass.SoftFallback.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("OS 권한 대화상자 (시스템 제공)", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                        Text("이 화면에서 [계속]을 누른 직후에만 시스템 대화상자를 띄웁니다.", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryActionButton("계속", onContinue)
                SecondaryActionButton("나중에 설정할게요", onLater)
            }
        }
    }
}

@Composable
private fun PermissionCard(
    symbol: DemoSymbol,
    title: String,
    badge: String,
    body: String,
    tint: Color,
    background: Color,
) {
    DemoGlassCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).background(background, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                DemoVectorIcon(symbol, title, Modifier.size(23.dp), tint)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = NaviInk)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge,
                        modifier = Modifier.background(background, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = NaviInkMuted)
            }
        }
    }
}

@Composable
fun FrontendMobilityProfileScreen(
    initialProfile: String,
    onBack: () -> Unit,
    onStart: (String) -> Unit,
) {
    var selected by rememberSaveable(initialProfile) {
        mutableStateOf(if (initialProfile == "default") "기본 도보" else "수동 휠체어")
    }
    var avoidStairs by rememberSaveable { mutableStateOf(true) }
    var requireElevator by rememberSaveable { mutableStateOf(true) }
    var curb by rememberSaveable { mutableFloatStateOf(2f) }
    var slope by rememberSaveable { mutableFloatStateOf(8f) }
    FrontendDemoPage {
        Column(Modifier.fillMaxSize()) {
            FrontendDemoTopBar("내 이동 조건", onBack = onBack, progress = "4 / 4")
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    buildAnnotatedString {
                        append("장애 유형이 아니라 ")
                        withStyle(SpanStyle(color = NaviInk, fontWeight = FontWeight.Bold)) { append("지금 이동에 필요한 조건") }
                        append("을 물어요. 언제든 바꿀 수 있어요.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NaviInkSoft,
                )
                Spacer(Modifier.height(14.dp))
                Text("이동 방식", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                Spacer(Modifier.height(8.dp))
                val choices = listOf(
                    "수동 휠체어" to "계단·높은 턱 제외",
                    "전동 휠체어" to "회전 반경·경사 보수적",
                    "유아차 · 보행보조" to "턱 낮음 우선",
                    "기본 도보" to "거리 우선",
                )
                choices.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        row.forEach { (label, caption) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(76.dp)
                                    .background(
                                        if (selected == label) NaviBlueSoft else NaviGlass.InteractiveFallback,
                                        RoundedCornerShape(16.dp),
                                    )
                                    .border(
                                        if (selected == label) 2.dp else 1.dp,
                                        if (selected == label) NaviBlue else NaviLine,
                                        RoundedCornerShape(16.dp),
                                    )
                                    .clickable { selected = label }
                                    .padding(12.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected == label) NaviBlue else NaviInk)
                                    Text(caption, style = MaterialTheme.typography.labelMedium, color = if (selected == label) NaviBlue else NaviInkMuted)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                DemoGlassCard {
                    Column {
                        MobilitySwitchRow("계단 구간 피하기", "계단 Edge를 경로에서 제외", avoidStairs) { avoidStairs = it }
                        DemoDivider(Modifier.padding(vertical = 10.dp))
                        MobilitySwitchRow("엘리베이터가 있어야 함", "지하·육교 구간 판단에 사용", requireElevator) { requireElevator = it }
                        DemoDivider(Modifier.padding(vertical = 10.dp))
                        SliderRow("넘을 수 있는 턱 높이", "${curb.toInt()} cm 이하", curb, 0f..6f) { curb = it }
                        Spacer(Modifier.height(8.dp))
                        SliderRow("견딜 수 있는 경사", "${slope.toInt()}% 이하", slope, 2f..14f) { slope = it }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(NaviBlueSoft.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        "ⓘ  전북대 실증 데이터 연결 전에는 우회 거리와 제외 구간을 확정하지 않아요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NaviBlue,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                PrimaryActionButton("이 조건으로 시작하기", { onStart(if (selected == "기본 도보") "default" else "wheelchair") })
            }
        }
    }
}

@Composable
private fun MobilitySwitchRow(
    title: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = NaviInk)
            Text(caption, style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFB9C7F8),
                checkedTrackColor = NaviBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = NaviLine,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = NaviInk)
            Text(valueLabel, style = MaterialTheme.typography.titleMedium, color = NaviBlue)
        }
        val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val startX = 14.dp.toPx()
                val endX = size.width - 14.dp.toPx()
                val y = size.height / 2f
                val thumbX = startX + (endX - startX) * fraction
                drawLine(NaviLine, Offset(startX, y), Offset(endX, y), 7.dp.toPx(), StrokeCap.Round)
                drawLine(NaviBlue, Offset(startX, y), Offset(thumbX, y), 7.dp.toPx(), StrokeCap.Round)
                drawCircle(Color.White, 11.dp.toPx(), Offset(thumbX, y))
                drawCircle(NaviBlue, 11.dp.toPx(), Offset(thumbX, y), style = Stroke(width = 3.dp.toPx()))
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                modifier = Modifier.fillMaxSize(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
fun FrontendSearchScreen(
    onBack: () -> Unit,
    onDestinationSelected: () -> Unit,
) {
    FrontendDemoPage {
        Column(Modifier.fillMaxSize()) {
            FrontendDemoTopBar(
                title = "전북대학교 중앙도서관",
                onBack = onBack,
                trailing = {
                    Text("×", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge, color = NaviInkMuted)
                    DemoVectorIcon(
                        DemoSymbol.Microphone,
                        "음성 검색",
                        Modifier.padding(end = 14.dp).size(34.dp).padding(6.dp),
                        NaviInk,
                    )
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DemoSegment("⌂ 현재 위치", true, {}, Modifier.weight(1f))
                    DemoSegment("지도에서 선택", false, {}, Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                Text("검색 결과", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                Spacer(Modifier.height(4.dp))
                SearchResult(
                    title = "전북대학교 중앙도서관",
                    address = "전북특별자치도 전주시 덕진구 백제대로 567 · 1.3km",
                    badges = listOf("✓ 경사로 확인" to NaviPass, "⚠ 출입구 정보 미검증" to NaviCaution),
                    onClick = onDestinationSelected,
                )
                DemoDivider()
                SearchResult(
                    title = "전북대학교 중앙도서관 출입구",
                    address = "백제대로 567 · 1.3km",
                    badges = listOf("엘리베이터 정보 없음" to NaviInkMuted),
                    onClick = onDestinationSelected,
                )
                Spacer(Modifier.height(14.dp))
                Text("최근 검색", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("전북대학교 정문", "전북대학교 박물관", "중앙도서관").forEach {
                        Text(
                            it,
                            modifier = Modifier
                                .background(NaviGlass.InteractiveFallback, RoundedCornerShape(18.dp))
                                .border(1.dp, NaviLine, RoundedCornerShape(18.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = NaviInkSoft,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                DemoGlassCard {
                    Text(
                        buildAnnotatedString {
                            append("ⓘ 지금은 ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = NaviInk)) { append("전북대 전주캠퍼스") }
                            append(" 안에서만 경로를 계산할 수 있어요. 범위 밖 장소는 위치만 보여드려요.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NaviInkMuted,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(246.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Color(0xFFE5E9EF), Color(0xFFF2F4F7)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("소프트 키보드 영역 · 약 250dp", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
            }
        }
    }
}

@Composable
private fun SearchResult(
    title: String,
    address: String,
    badges: List<Pair<String, Color>>,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("⌖", style = MaterialTheme.typography.titleLarge, color = NaviBlue)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = NaviInk)
            Spacer(Modifier.height(3.dp))
            Text(address, style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                badges.forEach { (text, color) -> Text(text, style = MaterialTheme.typography.labelMedium, color = color) }
            }
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = NaviInkMuted)
    }
}
