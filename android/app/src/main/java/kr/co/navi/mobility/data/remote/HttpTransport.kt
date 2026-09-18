package kr.co.navi.mobility.data.remote

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HttpMethod { GET, POST }

data class HttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(
    val statusCode: Int,
    val body: String,
)

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class UrlConnectionHttpTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 20_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(ioDispatcher) {
        val connection = connectionFactory(URL(request.url))
        try {
            connection.requestMethod = request.method.name
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            // A changed server address must be explicitly verified; never redirect credentials.
            connection.instanceFollowRedirects = false
            connection.doInput = true
            request.headers.forEach(connection::setRequestProperty)
            request.body?.let { body ->
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(
                statusCode = statusCode,
                body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }
}

class NaviApiException(
    val statusCode: Int,
    val errorCode: String?,
    override val message: String,
) : RuntimeException(message)

class NaviProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
