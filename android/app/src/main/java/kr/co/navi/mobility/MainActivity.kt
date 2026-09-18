package kr.co.navi.mobility

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kr.co.navi.mobility.ui.NaviApp
import kr.co.navi.mobility.ui.theme.NaviTheme
import org.maplibre.android.MapLibre
import kr.co.navi.mobility.demo.JeonjuCoordinator
import kr.co.navi.mobility.demo.JeonjuDemoScreen
import kr.co.navi.mobility.demo.ServerConnection
import kr.co.navi.mobility.demo.ServerConnectionStore
import kr.co.navi.mobility.demo.CaseCollectionCoordinator
import kr.co.navi.mobility.demo.CaseCollectionScreen

class MainActivity : ComponentActivity() {
    private lateinit var container: NaviAppContainer
    private var jeonju: JeonjuCoordinator?=null
    private var collection: CaseCollectionCoordinator?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app uses MapLibre's OpenGL artifact for broad device/emulator compatibility.
        MapLibre.getInstance(this)
        container = NaviAppContainer(applicationContext)
        enableEdgeToEdge()
        val demoMode=intent.getStringExtra("jeonju_mode") ?: "Hackathon"
        if(!intent.getBooleanExtra("legacy_ui",false)) {
            require(demoMode in setOf("Collect","Hackathon","PocLive","Live","Replay","SelfTest"))
            val clip=intent.getStringExtra("clip_id") ?: "C01"
            require(clip.matches(Regex("[A-Za-z0-9_-]{1,60}")))
            val stored = ServerConnectionStore(applicationContext).load()
            val connection = intent.getStringExtra("backend_url")?.let { url ->
                ServerConnection(url, intent.getStringExtra("backend_token") ?: if(url.trimEnd('/')==stored.url)stored.token else "")
            } ?: stored
            if(demoMode=="Collect") collection=CaseCollectionCoordinator(applicationContext,connection)
            else jeonju=JeonjuCoordinator(applicationContext,demoMode,clip,connection.url,
                savedInstanceState==null && intent.getBooleanExtra("auto_start",false),if(savedInstanceState==null)intent.getStringExtra("run_id") else null,connection.token)
        }
        setContent {
            NaviTheme {
                val demo=jeonju
                val capture=collection
                if(capture!=null)CaseCollectionScreen(this,capture)
                else if(demo!=null)JeonjuDemoScreen(this,demo) else NaviApp(container)
            }
        }
    }
    override fun onDestroy(){collection?.close();jeonju?.close();super.onDestroy()}
}
