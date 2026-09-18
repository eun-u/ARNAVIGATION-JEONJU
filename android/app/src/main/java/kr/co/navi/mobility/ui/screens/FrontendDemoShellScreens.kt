package kr.co.navi.mobility.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kr.co.navi.mobility.data.NaviSessionState
import kr.co.navi.mobility.ui.components.DemoDivider
import kr.co.navi.mobility.ui.components.DemoDot
import kr.co.navi.mobility.ui.components.DemoGlassCard
import kr.co.navi.mobility.ui.components.DemoIconTile
import kr.co.navi.mobility.ui.components.DemoSectionLabel
import kr.co.navi.mobility.ui.components.FrontendDemoPage
import kr.co.navi.mobility.ui.components.FrontendDemoTopBar
import kr.co.navi.mobility.ui.components.PrismaticNaviMark
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
import kr.co.navi.mobility.ui.theme.NaviViolet
import kr.co.navi.mobility.ui.theme.NaviVioletSoft

@Composable
fun FrontendHomeScreen(
    session: NaviSessionState,
    onSearch: () -> Unit,
    onActiveGuidance: () -> Unit,
    onEditProfile: () -> Unit,
    onSettings: () -> Unit,
) {
    FrontendDemoPage {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(NaviGlass.InteractiveFallback.copy(alpha = 0.82f))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrismaticNaviMark(Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("NaVi", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = NaviInk)
                Row(
                    Modifier
                        .background(NaviPassSoft, RoundedCornerShape(18.dp))
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DemoDot(NaviPass, Modifier.size(6.dp))
                    Text("실데이터 연결", style = MaterialTheme.typography.labelMedium, color = NaviPass)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "☼",
                    modifier = Modifier.clickable(onClick = onSettings).padding(8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = NaviInk,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .background(Brush.horizontalGradient(listOf(NaviBlue, NaviViolet)), RoundedCornerShape(17.dp))
                        .clickable(onClick = onActiveGuidance)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("➤", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("안내가 진행 중이에요", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(
                                "전북대 캠퍼스 도착점 · 남은 ${session.activeRoute?.let { "${it.distanceM.toInt()}m" } ?: "130m"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "오늘도\n갈 수 있는 길부터 볼게요.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = NaviInk,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(10.dp))
                DemoGlassCard(modifier = Modifier.height(58.dp), radius = 17, onClick = onSearch) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⌕", style = MaterialTheme.typography.titleLarge, color = NaviInkMuted)
                        Spacer(Modifier.width(12.dp))
                        Text("어디로 갈까요?", style = MaterialTheme.typography.titleLarge, color = NaviInkMuted)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("⌂  집", "▢  회사", "＋  추가").forEach { chip ->
                        Text(
                            chip,
                            modifier = Modifier
                                .background(NaviGlass.InteractiveFallback, RoundedCornerShape(20.dp))
                                .border(1.dp, NaviGlass.OpticalEdge, RoundedCornerShape(20.dp))
                                .padding(horizontal = 13.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (chip.contains("추가")) NaviInkMuted else NaviInk,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                DemoGlassCard(onClick = onEditProfile) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DemoIconTile("♿︎", NaviBlue, NaviBlueSoft, "수동 휠체어")
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("수동 휠체어 조건", style = MaterialTheme.typography.titleMedium, color = NaviInk)
                            Text("계단 제외 · 턱 2cm · 경사 8%", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                        }
                        Text("수정", style = MaterialTheme.typography.labelMedium, color = NaviBlue)
                    }
                }
                Spacer(Modifier.height(12.dp))
                DemoSectionLabel("최근 경로", "모두 보기")
                RecentRouteRow("전북대학교 정문 → 중앙도서관", "합성 데모 · 검증 전", true, onSearch)
                DemoDivider()
                RecentRouteRow("전북대학교 박물관 → 중앙도서관", "합성 데모 · 검증 전", false, onSearch)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(NaviCautionSoft.copy(alpha = 0.76f), RoundedCornerShape(14.dp))
                        .border(1.dp, NaviCaution.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("⚠", color = NaviCaution)
                    Text(
                        buildAnnotatedString {
                            append("현재 전북대 실증 데이터는 연결 대기 중이며 화면 예시는 미검증입니다. ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("신뢰도 보기") }
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = NaviCaution,
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun RecentRouteRow(
    title: String,
    caption: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DemoDot(if (highlighted) NaviBlue else Color(0xFFCDD5DF))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = NaviInk)
            Text(caption, style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = NaviInkMuted)
    }
}

@Composable
fun FrontendSavedScreen(
    onBack: (() -> Unit)? = null,
    onOpenReport: () -> Unit,
) {
    FrontendDemoPage {
        Column(Modifier.fillMaxSize()) {
            FrontendDemoTopBar("저장과 기록", onBack = onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("저장한 장소", "이동 기록", "내 제보").forEachIndexed { index, label ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(38.dp)
                                .background(if (index == 2) Color.White else Color.Transparent, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = if (index == 2) NaviInk else NaviInkMuted)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewCountCard("2", "검수 대기", NaviCaution, NaviCautionSoft, Modifier.weight(1f))
                    ReviewCountCard("5", "반영됨", NaviPass, NaviPassSoft, Modifier.weight(1f))
                    ReviewCountCard("1", "반려", NaviInkMuted, Color(0xFFEDEFF3), Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                ReportHistoryRow("전북대 정문 보행로 공사 후보", "합성 예시 · 실제 데이터 아님", "검수 대기", NaviCaution, NaviCautionSoft, onOpenReport)
                DemoDivider()
                ReportHistoryRow("중앙도서관 북측 턱 후보", "합성 예시 · 실제 데이터 아님", "검수 대기", NaviCaution, NaviCautionSoft, onOpenReport)
                DemoDivider()
                ReportHistoryRow("백제대로 경사로 후보", "합성 예시 · 공용 경로 미반영", "검수 대기", NaviCaution, NaviCautionSoft, onOpenReport)
                DemoDivider()
                ReportHistoryRow("전북대학교 정문 경사로 후보", "합성 예시 · 위치 검증 전", "검수 대기", NaviCaution, NaviCautionSoft, onOpenReport)
                Spacer(Modifier.height(18.dp))
                Text("제보가 승인되면 알림으로 알려드려요.", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = NaviInkMuted)
            }
        }
    }
}

@Composable
private fun ReviewCountCard(
    count: String,
    label: String,
    color: Color,
    container: Color,
    modifier: Modifier,
) {
    Column(
        modifier
            .background(container, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(count, style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
    }
}

@Composable
private fun ReportHistoryRow(
    title: String,
    caption: String,
    status: String,
    color: Color,
    container: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = NaviInk)
            Text(
                status,
                modifier = Modifier.background(container, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
        Text(caption, style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
    }
}

@Composable
fun FrontendSettingsScreen(
    session: NaviSessionState,
    onEditProfile: () -> Unit,
) {
    var voice by rememberSaveable { mutableStateOf(true) }
    var vibration by rememberSaveable { mutableStateOf(true) }
    var autoAr by rememberSaveable { mutableStateOf(false) }
    var highContrast by rememberSaveable { mutableStateOf(false) }
    var reduceMotion by rememberSaveable { mutableStateOf(false) }
    FrontendDemoPage {
        Column(Modifier.fillMaxSize()) {
            FrontendDemoTopBar(
                title = "내 정보",
                trailing = { Text("ⓘ", modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.labelMedium, color = NaviInk) },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                DemoGlassCard(onClick = onEditProfile) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DemoIconTile("♿︎", NaviBlue, NaviBlueSoft, "내 이동 조건")
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("내 이동 조건", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                            Text(
                                if (session.profile == "default") "기본 도보 · 거리 우선" else "수동 휠체어 · 계단 제외",
                                style = MaterialTheme.typography.titleMedium,
                                color = NaviInk,
                            )
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge, color = NaviInkMuted)
                    }
                }
                Spacer(Modifier.height(14.dp))
                SettingsGroupLabel("안내")
                DemoGlassCard {
                    Column {
                        SettingToggleRow("음성 안내", null, voice) { voice = it }
                        DemoDivider(Modifier.padding(vertical = 5.dp))
                        SettingToggleRow("회전·위험 구간 진동", null, vibration) { vibration = it }
                        DemoDivider(Modifier.padding(vertical = 5.dp))
                        SettingToggleRow("안내 시작 시 AR 자동 전환", "카메라 권한이 있을 때만", autoAr) { autoAr = it }
                    }
                }
                Spacer(Modifier.height(14.dp))
                SettingsGroupLabel("접근성")
                DemoGlassCard {
                    Column {
                        SettingValueRow("글자 크기", "시스템 설정 따름")
                        DemoDivider(Modifier.padding(vertical = 5.dp))
                        SettingToggleRow("햇빛 아래 고대비 모드", "야외 시인성을 위해 대비를 높여요", highContrast) { highContrast = it }
                        DemoDivider(Modifier.padding(vertical = 5.dp))
                        SettingToggleRow("움직임 줄이기", "반복 애니메이션을 끕니다 · 시스템 따름", reduceMotion) { reduceMotion = it }
                    }
                }
                Spacer(Modifier.height(14.dp))
                SettingsGroupLabel("데이터")
                DemoGlassCard {
                    Column {
                        SettingValueRow("데이터 모드", "실데이터", "서버 연결 실패 시 데모로 전환")
                        DemoDivider(Modifier.padding(vertical = 5.dp))
                        SettingValueRow("오프라인 지도 내려받기", "›", "전북대 전주캠퍼스 · 데이터 준비 중")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "카메라 영상은 화면 표시에만 쓰고 저장·전송하지 않아요. 위치는 앱 사용 중에만 받습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NaviInkMuted,
                )
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun SettingsGroupLabel(label: String) {
    Text(label, modifier = Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
}

@Composable
private fun SettingToggleRow(
    title: String,
    caption: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = NaviInk)
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = NaviBlue,
                checkedThumbColor = Color(0xFFB9C7F8),
                uncheckedTrackColor = NaviLine,
                uncheckedThumbColor = Color.White,
            ),
        )
    }
}

@Composable
private fun SettingValueRow(title: String, value: String, caption: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = NaviInk)
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
        }
        Text(value, style = MaterialTheme.typography.labelMedium, color = NaviBlue)
    }
}
