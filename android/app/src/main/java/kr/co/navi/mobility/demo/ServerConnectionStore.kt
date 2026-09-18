package kr.co.navi.mobility.demo

import android.content.Context
import kr.co.navi.mobility.BuildConfig
import kr.co.navi.mobility.data.remote.NaviApiClient
import kr.co.navi.mobility.data.remote.UrlConnectionHttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ServerConnectionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("jeonju_server", Context.MODE_PRIVATE)

    fun load(): ServerConnection {
        prefs.getString("url", null)?.let { url ->
            runCatching { ServerConnection(url, prefs.getString("token", "").orEmpty()) }.getOrNull()?.let { return it }
        }
        // Local provisioning file is ignored by Git and only bundled with private field builds.
        val provisioned = runCatching {
            val obj = org.json.JSONObject(context.assets.open("field_connection.local.json").bufferedReader().use { it.readText() })
            ServerConnection(obj.getString("url"), obj.optString("token"))
        }.getOrNull()
        return provisioned ?: ServerConnection(BuildConfig.BACKEND_BASE_URL)
    }

    fun save(connection: ServerConnection) {
        check(prefs.edit().putString("url", connection.url).putString("token", connection.token).commit()) {
            "서버 주소를 저장하지 못했습니다."
        }
    }

    suspend fun verify(connection: ServerConnection) {
        val actual = NaviApiClient(connection.url, UrlConnectionHttpTransport(5000, 8000), accessToken = connection.token)
            .getJeonjuBootstrap()
        val expected = Json.parseToJsonElement(context.assets.open("jeonju_scope.json").bufferedReader().use { it.readText() }).jsonObject
        check(listOf("region_id", "scope_revision", "dataset_revision", "graph_sha256").all { actual[it] == expected[it] }) {
            "서버의 전주 데이터 버전이 앱과 다릅니다."
        }
    }
}
