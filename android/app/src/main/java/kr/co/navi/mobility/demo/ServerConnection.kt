package kr.co.navi.mobility.demo

import java.net.URI

/** Keep credentials out of URLs, logs and generated data-class toString output. */
class ServerConnection(url: String, token: String = "") {
    val url: String = normalizeServerUrl(url)
    val token: String = token.trim().also {
        require(it.isEmpty() || it.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "접속 코드 형식을 확인하세요." }
        require(it.isEmpty() || this.url.startsWith("https://")) { "접속 코드는 HTTPS 주소에서만 사용할 수 있습니다." }
    }
}

fun normalizeServerUrl(raw: String): String {
    val uri = try { URI(raw.trim().trimEnd('/')) } catch (_: Exception) {
        throw IllegalArgumentException("올바른 서버 주소를 입력하세요.")
    }
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath.orEmpty().isEmpty() && (uri.port == -1 || uri.port in 1..65535)) {
        "http:// 또는 https://로 시작하는 서버 주소만 입력하세요."
    }
    return uri.toASCIIString()
}
