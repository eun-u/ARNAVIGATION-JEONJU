package kr.co.navi.mobility.demo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.co.navi.mobility.ar.ArCoreNavigationView

@Composable
fun CaseCollectionScreen(activity: Activity, coordinator: CaseCollectionCoordinator) {
    val state by coordinator.state.collectAsState()
    var cameraRequested by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val camera = result[Manifest.permission.CAMERA] == true || ContextCompat.checkSelfPermission(activity,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
        if(camera) { cameraRequested=true; coordinator.start() } else coordinator.permissionDenied()
    }
    val showCamera=cameraRequested && state.phase!=CollectionPhase.DONE && state.phase!=CollectionPhase.ERROR
    val arView = remember(showCamera) { if(showCamera) ArCoreNavigationView(activity,activity) {} else null }
    DisposableEffect(arView,lifecycle) {
        arView?.let(coordinator::attach)
        val observer = LifecycleEventObserver { _, event -> when(event) {
            Lifecycle.Event.ON_RESUME -> arView?.resumeSession()
            Lifecycle.Event.ON_PAUSE -> { coordinator.onHostPause(); arView?.pauseSession() }
            else -> Unit
        } }
        lifecycle.lifecycle.addObserver(observer)
        if(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))arView?.resumeSession()
        onDispose { lifecycle.lifecycle.removeObserver(observer); arView?.let { coordinator.detach(it); it.closeSession() } }
    }
    DisposableEffect(state.busy) {
        if(state.busy) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("NaVi 사례 수집",style=MaterialTheme.typography.headlineMedium)
        if(state.phase==CollectionPhase.IDLE) {
            Text("한 번 누르고, 안내에 따라 촬영하세요.",style=MaterialTheme.typography.titleMedium)
            Text("앞쪽 보행로 → 길 가장자리 → 지나가는 사람\n각 1분씩 촬영하고 영상과 관측 기록을 자동 저장합니다.")
        }
        if(state.phase==CollectionPhase.RECORDING) {
            Text("${state.step.coerceAtLeast(1)} / 3 · ${state.remainingSeconds/60}:${(state.remainingSeconds%60).toString().padStart(2,'0')} 남음",
                style=MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress={1f-state.remainingSeconds/180f},modifier=Modifier.fillMaxWidth())
        }
        Text(state.instruction,style=MaterialTheme.typography.titleLarge)
        if(state.busy) arView?.let { view -> AndroidView(factory={view},modifier=Modifier.fillMaxWidth().height(280.dp)) }
        when(state.phase) {
            CollectionPhase.IDLE -> {
                Button(modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),onClick={
                    val required=arrayOf(Manifest.permission.CAMERA,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
                    val camera=ContextCompat.checkSelfPermission(activity,required[0])==PackageManager.PERMISSION_GRANTED
                    val location=required.drop(1).any {ContextCompat.checkSelfPermission(activity,it)==PackageManager.PERMISSION_GRANTED}
                    if(camera && location) {cameraRequested=true; coordinator.start()}
                    else permissions.launch(required)
                }) { Text("사례 수집 시작") }
                OutlinedButton(onClick={activity.intent.putExtra("jeonju_mode","Live");activity.recreate()},modifier=Modifier.fillMaxWidth()) {Text("실시간 콘 감지·우회")}
                Text("최초 권한 요청은 허용해주세요. 촬영 중에는 화면을 켜두세요. 인터넷이 끊겨도 기기에 저장됩니다.",style=MaterialTheme.typography.bodySmall)
            }
            CollectionPhase.PREPARING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick={coordinator.stop()}) {Text("취소")} }
            CollectionPhase.RECORDING -> {
                if(state.offline) Text("기기에 기록 중 · 인터넷 연결 없이 수집합니다.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={coordinator.stop()},modifier=Modifier.fillMaxWidth()) { Text("중단하고 여기까지 저장") }
            }
            CollectionPhase.SAVING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            CollectionPhase.DONE -> {
                Text("저장된 프레임 ${state.frames}개\n${state.savedPath.orEmpty()}",style=MaterialTheme.typography.bodyMedium)
                Text("촬영 자료의 장면 종류와 위치 정합은 검토 후 확정합니다.",style=MaterialTheme.typography.bodySmall)
                Button(onClick={activity.recreate()},modifier=Modifier.fillMaxWidth()) { Text("새 사례 수집") }
            }
            CollectionPhase.ERROR -> {
                if(coordinator.directory.isDirectory)Button(onClick=coordinator::retryExport,modifier=Modifier.fillMaxWidth()) {Text("보관한 기록 저장")}
                OutlinedButton(onClick={activity.recreate()},modifier=Modifier.fillMaxWidth()) { Text("다시 준비") }
            }
        }
        if(!state.busy) {
            TextButton(onClick={settings=!settings}) { Text(if(settings)"설정 닫기" else "설정") }
            if(settings) {
                ServerConnectionSettings(activity,coordinator.connection.url,true)
                OutlinedButton(onClick={activity.intent.putExtra("jeonju_mode","Live");activity.recreate()}) { Text("기준점 정합을 사용하는 정밀 시연") }
            }
        }
    }
}
