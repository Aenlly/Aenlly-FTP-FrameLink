package app.framelink.camera

import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject

data class CloudProject(
    val id: String,
    val name: String,
    val state: String,
    val roundNo: Int,
    val selectionVersion: Int,
    val mediaCount: Int = 0,
    val media: List<CloudMedia> = emptyList(),
    val revisions: List<CloudRevision> = emptyList(),
    val shares: List<CloudShare> = emptyList(),
)

data class CloudShare(
    val id: String,
    val revoked: Boolean,
    val allowDownload: Boolean,
    val expiresAt: Long,
)

data class CloudMedia(
    val id: String,
    val name: String,
    val kind: String,
    val parentId: String? = null,
    val selected: Boolean = false,
    val commentText: String = "",
)

data class CloudRevision(val id: String, val roundNo: Int, val submittedAt: Long)

/** 云网络使用默认互联网路径，上传时只读取单个文件流 */
class CloudClient(private val settings: SecureSettings) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    val configured
        get() = settings.get("cloud.url").isNotBlank()

    val signedIn
        get() = settings.get("cloud.token").isNotBlank()

    val baseUrl
        get() = settings.get("cloud.url")

    fun configure(raw: String) {
        val uri = URI(raw.trim().trimEnd('/'))
        val host = uri.host ?: error("请输入有效的云服务地址")
        val local =
            host == "localhost" ||
                host == "127.0.0.1" ||
                host.startsWith("192.168.") ||
                host.startsWith("10.") ||
                (host.startsWith("172.") && host.split('.').getOrNull(1)?.toIntOrNull() in 16..31)
        check(uri.scheme == "https" || uri.scheme == "http" && local) {
            "公开云服务需要HTTPS，HTTP仅用于局域网开发"
        }
        if (baseUrl != uri.toString()) settings.remove("cloud.token")
        settings.put("cloud.url", uri.toString())
    }

    fun login(email: String, password: String, invite: String = "") {
        val body =
            JSONObject().put("email", email).put("password", password).put("inviteCode", invite)
        val response =
            call("/auth/" + if (invite.isBlank()) "login" else "register", "POST", body.toString())
        settings.put("cloud.token", JSONObject(response).getString("token"))
        settings.put("cloud.email", email)
    }

    fun logout() {
        runCatching { call("/auth/logout", "POST") }
        settings.remove("cloud.token")
    }

    fun projects(): List<CloudProject> = Json.list(call("/projects"))

    fun account(): JSONObject = JSONObject(call("/auth/me"))

    fun project(id: String): CloudProject = Json.decode(call("/projects/$id"))

    fun createProject(name: String): CloudProject =
        Json.decode(call("/projects", "POST", JSONObject().put("name", name).toString()))

    fun share(id: String): String =
        call("/projects/$id/shares", "POST", "{\"days\":7,\"allowDownload\":false}")

    fun confirm(id: String, revision: String) {
        call("/projects/$id/confirm", "POST", JSONObject().put("revisionId", revision).toString())
    }

    fun reopen(id: String) {
        call("/projects/$id/reopen", "POST")
    }

    fun updateShare(projectId: String, shareId: String, allowDownload: Boolean?, revoke: Boolean) {
        call(
            "/projects/$projectId/shares/$shareId",
            if (revoke) "DELETE" else "PATCH",
            if (allowDownload == null) "{}"
            else JSONObject().put("allowDownload", allowDownload).toString(),
        )
    }

    fun upload(
        projectId: String,
        file: File,
        sourceKey: String,
        parentId: String?,
        progress: (Long, Long) -> Unit,
    ): String {
        val content =
            object : RequestBody() {
                override fun contentType() = MediaFiles.mime(file.name).toMediaType()

                override fun contentLength() = file.length()

                override fun writeTo(sink: BufferedSink) {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            sink.write(buffer, 0, n)
                            done += n
                            progress(done, file.length())
                        }
                    }
                }
            }
        val builder =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("sourceKey", sourceKey)
                .addFormDataPart("kind", if (parentId == null) "PREVIEW" else "DELIVERY")
                .addFormDataPart("file", file.name, content)
        if (parentId != null) builder.addFormDataPart("parentId", parentId)
        return execute(
            Request.Builder()
                .url(baseUrl + "/api/projects/$projectId/media")
                .post(builder.build())
                .header("Authorization", "Bearer " + settings.get("cloud.token"))
                .build()
        )
    }

    private fun call(path: String, method: String = "GET", body: String = "{}"): String {
        check(configured) { "请先配置云服务地址" }
        val request =
            Request.Builder()
                .url(baseUrl + "/api" + path)
                .header("Authorization", "Bearer " + settings.get("cloud.token"))
        if (method == "GET") request.get()
        else
            request.method(
                method,
                body.toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
        return execute(request.build())
    }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401) settings.remove("cloud.token")
                error(
                    runCatching { JSONObject(body).getString("message") }
                        .getOrDefault("云服务暂不可用（${response.code}）")
                )
            }
            body
        }
}
