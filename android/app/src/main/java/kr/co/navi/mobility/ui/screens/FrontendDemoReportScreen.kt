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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.navi.mobility.ui.NavigationViewModel
import kr.co.navi.mobility.ui.components.DemoGlassCard
import kr.co.navi.mobility.ui.components.FrontendDemoPage
import kr.co.navi.mobility.ui.components.FrontendDemoTopBar
import kr.co.navi.mobility.ui.components.PrimaryActionButton
import kr.co.navi.mobility.ui.theme.NaviBlock
import kr.co.navi.mobility.ui.theme.NaviBlockSoft
import kr.co.navi.mobility.ui.theme.NaviBlue
import kr.co.navi.mobility.ui.theme.NaviBlueSoft
import kr.co.navi.mobility.ui.theme.NaviGlass
import kr.co.navi.mobility.ui.theme.NaviInk
import kr.co.navi.mobility.ui.theme.NaviInkMuted
import kr.co.navi.mobility.ui.theme.NaviInkSoft
import kr.co.navi.mobility.ui.theme.NaviLine

@Composable
fun FrontendReportScreen(
    viewModel: NavigationViewModel,
    onBack: () -> Unit,
    onShowReroute: () -> Unit,
) {
    val reportState by viewModel.reportState.collectAsStateWithLifecycle()
    var selectedType by remember { mutableStateOf("blocked_path") }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.resetReportState() }

    FrontendDemoPage {
        Column(Modifier.fillMaxSize()) {
            FrontendDemoTopBar("현장 제보", onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(NaviBlueSoft, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        buildAnnotatedString {
                            append("ⓘ 제보는 ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("검수 후보") }
                            append("로 저장돼요. 사람 검수를 통과해야 다른 사용자의 경로에 반영됩니다. 내 이번 이동에는 바로 적용돼요.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NaviBlue,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("무엇을 보셨나요? *", style = MaterialTheme.typography.titleMedium, color = NaviInk)
                Spacer(Modifier.height(8.dp))
                val types = listOf(
                    Triple("blocked_path", "공사·차단", "⌁"),
                    Triple("stairs", "계단", "⌗"),
                    Triple("high_curb", "높은 턱", "⌐"),
                    Triple("elevator_failure", "엘리베이터 고장", "▣"),
                )
                types.chunked(2).forEach { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { (value, label, glyph) ->
                            val selected = selectedType == value
                            Row(
                                Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .background(if (selected) NaviBlockSoft else NaviGlass.InteractiveFallback, RoundedCornerShape(12.dp))
                                    .border(1.dp, if (selected) NaviBlock else NaviLine, RoundedCornerShape(12.dp))
                                    .clickable { selectedType = value }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(glyph, style = MaterialTheme.typography.bodyMedium, color = if (selected) NaviBlock else NaviInk)
                                Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) NaviBlock else NaviInk)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                DemoGlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⌖", style = MaterialTheme.typography.titleLarge, color = NaviBlue)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("전북대 정문 보행로", style = MaterialTheme.typography.bodyMedium, color = NaviInk)
                            Text("현재 위치에서 자동 지정 · ±8m", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                        }
                        Text("지도에서\n조정", style = MaterialTheme.typography.labelMedium, color = NaviBlue)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("사진 (선택)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .size(78.dp)
                        .background(NaviGlass.InteractiveFallback, RoundedCornerShape(14.dp))
                        .border(1.dp, NaviLine, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▣", style = MaterialTheme.typography.titleLarge, color = NaviInkMuted)
                        Text("추가", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text("사람 얼굴과 차량 번호는 흐리게 처리한 뒤 검수자에게만 보여요.", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                Spacer(Modifier.height(12.dp))
                Text("메모 (선택)", style = MaterialTheme.typography.labelMedium, color = NaviInkMuted)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 180) note = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("예: 보도 절반이 펜스로 막혀 휠체어가 지날 수 없어요", style = MaterialTheme.typography.bodySmall) },
                    minLines = 3,
                    shape = RoundedCornerShape(14.dp),
                )
                reportState.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = NaviBlock)
                }
                Spacer(Modifier.height(18.dp))
            }
            Surface(color = NaviGlass.InteractiveFallback, shadowElevation = 8.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    if (reportState.reroute != null) {
                        PrimaryActionButton("변경된 경로 확인", onShowReroute)
                    } else {
                        PrimaryActionButton(
                            text = "제보하고 다시 길찾기",
                            onClick = { viewModel.reportObstacle(selectedType, note.ifBlank { null }) },
                            loading = reportState.submitting,
                        )
                    }
                }
            }
        }
    }
}
