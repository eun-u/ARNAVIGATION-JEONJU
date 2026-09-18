package kr.co.navi.mobility.demo

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kr.co.navi.mobility.data.remote.NaviApiException

@Composable
fun ServerConnectionSettings(activity: Activity, backend: String, enabled: Boolean) {
    val store = remember { ServerConnectionStore(activity.applicationContext) }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(backend) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("서버: $backend", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = enabled, onClick = {
        url = backend
        val saved = store.load()
        token = activity.intent.getStringExtra("backend_token") ?: if(saved.url == backend) saved.token else ""
        error = null
        open = true
    }) { Text("서버 연결 설정") }
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text("서버 연결") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("외부 실증에서는 LTE·5G로 접속할 수 있는 HTTPS 주소를 사용하세요.")
                OutlinedTextField(url, { url = it; error = null }, label = { Text("서버 주소") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it; error = null }, label = { Text("접속 코드 (필요한 경우)") },
                    singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Text("전주 데이터 버전을 확인한 뒤 주소를 저장하고 다시 준비합니다.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val connection = ServerConnection(url, token)
                        store.verify(connection)
                        store.save(connection)
                        activity.intent.removeExtra("backend_url")
                        activity.intent.removeExtra("backend_token")
                        activity.intent.removeExtra("run_id")
                        activity.intent.putExtra("auto_start", false)
                        activity.recreate()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = when (failure) {
                            is NaviApiException -> if (failure.statusCode == 401) "접속 코드를 확인하세요." else "서버 응답 오류 (${failure.statusCode})"
                            is IllegalArgumentException, is IllegalStateException -> failure.message
                            else -> "서버에 연결하지 못했습니다. 주소와 인터넷 연결을 확인하세요."
                        }
                    } finally { busy = false }
                }
            }) { Text(if (busy) "연결 확인 중…" else "확인 후 저장") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("취소") } },
    )
}
