package kr.co.navi.mobility.demo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.co.navi.mobility.ar.*
import kr.co.navi.mobility.guidance.contract.*
import kr.co.navi.mobility.ui.components.RouteMap

@Composable
fun JeonjuDemoScreen(activity: Activity,coordinator: JeonjuCoordinator) {
    val state by coordinator.state.collectAsState()
    var cameraGranted by remember {mutableStateOf(ContextCompat.checkSelfPermission(activity,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){cameraGranted=it[Manifest.permission.CAMERA]==true}
    val lifecycle=LocalLifecycleOwner.current
    var arStatus by remember{mutableStateOf("카메라 권한을 허용하세요.")}
    val arView=remember(cameraGranted){if(cameraGranted && coordinator.mode=="Live")ArCoreNavigationView(activity,activity){arStatus=it.message ?: it.mode.name} else null}
    DisposableEffect(arView,lifecycle) {
        arView?.let(coordinator::attach)
        val observer=LifecycleEventObserver {_,event->when(event){
            Lifecycle.Event.ON_RESUME->arView?.resumeSession()
            Lifecycle.Event.ON_PAUSE->{coordinator.onHostPause();arView?.pauseSession()}
            else->Unit
        }}
        lifecycle.lifecycle.addObserver(observer)
        if(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))arView?.resumeSession()
        onDispose {lifecycle.lifecycle.removeObserver(observer);arView?.let{coordinator.detach(it);it.closeSession()}}
    }
    var lat by remember{mutableStateOf("")};var lon by remember{mutableStateOf("")};var accuracy by remember{mutableStateOf("")};var label by remember{mutableStateOf("")}
    var confirmed by remember{mutableStateOf(false)}
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(state.calibration?.revision){confirmed=false}
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("NaVi 전북대 자동 시연",style=MaterialTheme.typography.headlineSmall)
        Text("${coordinator.mode} · ${coordinator.clipId}",style=MaterialTheme.typography.labelLarge)
        Text("demo_jeonju · 접근성 미확인 속성 허용",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelMedium)
        Text(state.message,style=MaterialTheme.typography.bodyLarge)
        if(!state.running && !state.starting)OutlinedButton(onClick={activity.intent.putExtra("jeonju_mode","Collect");activity.recreate()}) {Text("버튼 하나로 사례 수집")}
        ServerConnectionSettings(activity,coordinator.backend,enabled=!state.running && !state.starting && !state.capturingReference)
        if(coordinator.mode=="Live") {
            if(!cameraGranted)Button(onClick={permissions.launch(arrayOf(Manifest.permission.CAMERA,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}){Text("카메라·위치 권한 준비")}
            arView?.let {view->Box(Modifier.fillMaxWidth().height(260.dp)) {
                AndroidView(factory={view},modifier=Modifier.fillMaxSize())
                LiveDetections(state)
            }}
            Text("현재 콘 감지 ${state.detections.count{it.label=="traffic_cone"}}개",style=MaterialTheme.typography.titleMedium)
            if(!state.running)Text("콘 감지는 준비 화면에서도 표시됩니다. 기준점 정합을 마친 뒤 시작하면 경로 영향을 판단합니다.",style=MaterialTheme.typography.bodySmall)
            Text(arStatus,style=MaterialTheme.typography.bodySmall)
            if(!state.running && !state.terminal) {
                Text("정합 준비: 위치가 알려진 두 기준점에 카메라를 차례로 놓고 기록하세요. 기준점은 5m 이상 떨어져 있어야 합니다.",style=MaterialTheme.typography.bodyMedium)
                OutlinedTextField(label={Text("기준점 이름")},value=label,onValueChange={label=it},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label={Text("위도")},value=lat,onValueChange={lat=it},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(label={Text("경도")},value=lon,onValueChange={lon=it},singleLine=true,modifier=Modifier.weight(1f))
                }
                OutlinedTextField(label={Text("확인한 기준점 위치 오차 (m)")},value=accuracy,onValueChange={accuracy=it},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled=!state.capturingReference && !state.starting,onClick={keyboard?.hide();coordinator.captureReference(lat,lon,accuracy,label,false)}){Text("기준점 A 기록")}
                    OutlinedButton(enabled=state.referenceA && !state.capturingReference && !state.starting,onClick={keyboard?.hide();coordinator.captureReference(lat,lon,accuracy,label,true)}){Text("기준점 B 기록")}
                }
                if(state.capturingReference)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                Text(state.referenceMessage ?: "이름·위도·경도·확인한 위치 오차를 입력한 위치에서 A를 기록하세요.",
                    color=if(state.referenceError)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style=MaterialTheme.typography.bodyMedium,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                Text("A: ${if(state.referenceA)"등록 완료" else "미등록"} · B: ${if(state.calibration!=null)"등록 완료" else "미등록"}",style=MaterialTheme.typography.labelLarge)
                Row {Checkbox(checked=confirmed,onCheckedChange={confirmed=it});Text("입력한 기준점과 차도 횡단 없는 촬영 동선을 현장에서 확인했습니다.",modifier=Modifier.padding(top=10.dp))}
            }
        }
        if(coordinator.mode=="Replay") ReplayFrame(state)
        if(state.guidanceValid)state.route?.let {route->
            RouteMap(route,route,emptyList(),state.reroutes>0,Modifier.fillMaxWidth().height(230.dp))
            Text("${state.remainingMeters?.let{"남은 거리 ${it.toInt()}m"} ?: "현재 위치 확인 중"} · 경로 ${state.snapshot?.revision}",style=MaterialTheme.typography.titleMedium)
        }
        if(state.running) {
            Text("프레임 ${state.frames} · 자동 우회 ${state.reroutes}\n${decisionText(state.decision)}",style=MaterialTheme.typography.bodyMedium)
            Button(onClick=coordinator::stop,modifier=Modifier.fillMaxWidth()){Text("종료하고 기록 내보내기")}
        } else if(!state.terminal && coordinator.mode!="SelfTest") {
            if(!state.ttsReady)OutlinedButton(onClick={activity.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))}){Text("한국어 음성 데이터 준비")}
            val startReason=when {
                state.starting -> "현재 위치에서 경로를 준비하고 있습니다."
                state.capturingReference -> "기준점 기록을 마치면 시연을 시작할 수 있습니다."
                !state.prepared -> "서버와 모델 준비가 완료돼야 합니다."
                !state.ttsReady -> "한국어 음성 데이터를 먼저 준비하세요."
                coordinator.mode!="Replay" && !state.referenceA -> "기준점 A를 먼저 기록하세요."
                coordinator.mode!="Replay" && state.calibration==null -> "기준점 B를 기록해 두 지점의 정합을 완료하세요."
                coordinator.mode!="Replay" && !confirmed -> "위의 기준점·촬영 동선 현장 확인 항목을 체크하세요."
                else -> null
            }
            startReason?.let{Text(it,style=MaterialTheme.typography.bodyMedium)}
            Button(onClick=coordinator::start,enabled=startReason==null,modifier=Modifier.fillMaxWidth()){Text(if(state.starting)"경로 준비 중" else "시연 시작")}
        }
        if(state.terminal)OutlinedButton(onClick={activity.intent.removeExtra("run_id");activity.intent.putExtra("auto_start",false);activity.recreate()},modifier=Modifier.fillMaxWidth()){Text("다시 준비")}
    }
}

@Composable private fun LiveDetections(state: JeonjuState) {
    val capture=state.spatial?.capture ?: return
    val transform=capture.imageToView ?: return
    Canvas(Modifier.fillMaxSize()) {
        // Convert upright inference boxes back to CPU pixels, then use ARCore's
        // actual viewport crop/rotation transform. Never stretch boxes to the preview.
        state.detections.forEach { detection ->
            val b=detection.bounds
            val corners=listOf(b.left to b.top,b.right to b.top,b.right to b.bottom,b.left to b.bottom).map { (u,v) ->
                val cpu=when(capture.rotationDegrees){90->v to 1-u;180->1-u to 1-v;270->1-v to u;else->u to v}
                val view=transform.uv(cpu.first*capture.intrinsics.width.toDouble(),cpu.second*capture.intrinsics.height.toDouble())
                Offset((view.first*size.width).toFloat(),(view.second*size.height).toFloat())
            }
            (corners+corners.first()).zipWithNext().forEach{(a,b)->drawLine(Color.Yellow,a,b,2.dp.toPx())}
        }
    }
}

private fun decisionText(reason: String)=when(reason){
    "persistence_pending"->"객체의 지속성을 확인 중입니다."
    "moving_object"->"움직이는 객체 · 경로를 유지합니다."
    "outside_sidewalk_or_mask_uncertain"->"보도 밖 또는 영역 불확실 · 조치를 보류합니다."
    "alignment_unavailable","alignment_uncertain"->"정합을 확인할 수 없어 2D 안내로 전환합니다."
    "stationary_sidewalk_observation"->"정지 객체의 영향 구간을 확인합니다."
    "depth_or_semantics_missing","insufficient_depth_samples"->"공간 정보가 부족해 조치를 보류합니다."
    else->"주변과 경로를 확인하고 있습니다."
}

@Composable private fun ReplayFrame(state: JeonjuState) {
    val file=state.preview ?: return
    val bitmap=remember(file,state.rotation) {
        val raw=BitmapFactory.decodeFile(file.absolutePath)
        if(raw==null || state.rotation==0)raw else android.graphics.Bitmap.createBitmap(raw,0,0,raw.width,raw.height,Matrix().apply{postRotate(state.rotation.toFloat())},true).also{raw.recycle()}
    } ?: return
    DisposableEffect(bitmap){onDispose{bitmap.recycle()}}
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height)) {
        Image(bitmap.asImageBitmap(),"실제 촬영 프레임 재생",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            state.detections.forEach{d->val b=d.bounds;drawRect(Color.Yellow,Offset(b.left*size.width,b.top*size.height),androidx.compose.ui.geometry.Size((b.right-b.left)*size.width,(b.bottom-b.top)*size.height),style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))}
            val s=state.spatial;val c=s?.capture;val pose=s?.localPose;val calibration=c?.calibration;val ground=c?.groundHeightMeters
            if(state.guidanceValid && state.remainingMeters!=null && pose!=null && calibration!=null && ground!=null && (s.accuracy?.horizontalMeters ?: 99.0)<=1.5) {
                val k=c.intrinsics
                val points=state.snapshot?.geometry.orEmpty().map{geo->pose.inverseTransform(calibration.world(geo,ground+0.02))}
                fun project(camera: Vec3): Offset {
                        val u=(camera.x/-camera.z*k.fx+k.cx)/k.width;val v=(-camera.y/-camera.z*k.fy+k.cy)/k.height
                        val uv=when(c.rotationDegrees){90->1-v to u;180->1-u to 1-v;270->v to 1-u;else->u to v}
                        return Offset((uv.first*size.width).toFloat(),(uv.second*size.height).toFloat())
                }
                fun nearIntersection(from: Vec3,to: Vec3): Vec3 {
                    val t=(-0.2-from.z)/(to.z-from.z)
                    return Vec3(from.x+(to.x-from.x)*t,from.y+(to.y-from.y)*t,-0.2)
                }
                clipRect {
                    points.zipWithNext().forEach{(from,to)->
                        if(from.z<=-0.2 || to.z<=-0.2) {
                            val a=if(from.z> -0.2)nearIntersection(from,to) else from
                            val b=if(to.z> -0.2)nearIntersection(to,from) else to
                            drawLine(Color.Cyan,project(a),project(b),8.dp.toPx())
                        }
                    }
                }
            }
        }
    }
}
