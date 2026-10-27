/*
 * Adapted from Apache FtpServer 1.2.0 STOR, licensed under Apache-2.0.
 * The ASF licenses the original work under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Local changes: unique receive paths, binary-only originals, fsync and a durable
 * Room task before success acknowledgement. Gallery processing stays asynchronous.
 */
package app.framelink.camera

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.command.AbstractCommand
import org.apache.ftpserver.ftplet.DataType
import org.apache.ftpserver.ftplet.DefaultFtpReply
import org.apache.ftpserver.ftplet.FtpReply
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.impl.FtpIoSession
import org.apache.ftpserver.impl.FtpServerContext
import org.apache.ftpserver.impl.IODataConnectionFactory
import org.apache.ftpserver.impl.ServerFtpStatistics
import org.json.JSONObject

/** 相机接收原片落盘后再ACK，重启可恢复待处理文件 */
class DurableStor(
    private val root: File,
    private val db: LocalDatabase,
    private val status: MutableStateFlow<String>,
    private val onReady: () -> Unit,
) : AbstractCommand() {
    override fun execute(session: FtpIoSession, context: FtpServerContext, request: FtpRequest) {
        var task: TransferTask? = null
        try {
            val argument = request.argument
            if (argument.isNullOrBlank()) {
                session.write(DefaultFtpReply(501, "Missing filename"))
                return
            }
            if (session.dataType != DataType.BINARY) {
                session.write(DefaultFtpReply(550, "Use TYPE I for original camera files"))
                return
            }
            val connectionFactory = session.dataConnection
            if (
                connectionFactory is IODataConnectionFactory &&
                    connectionFactory.inetAddress == null
            ) {
                session.write(DefaultFtpReply(503, "PORT or PASV must be issued first"))
                return
            }
            val name =
                argument
                    .replace('\\', '/')
                    .substringAfterLast('/')
                    .replace(Regex("[\\p{Cntrl}]"), "")
                    .take(160)
                    .ifBlank { "photo.bin" }
            val id = UUID.randomUUID().toString()
            val part = File(root, ".incoming/$id.part").apply { parentFile!!.mkdirs() }
            val destination = File(root, "received/${id}_$name").apply { parentFile!!.mkdirs() }
            task =
                TransferTask(
                    id,
                    TransferKind.FTP_RECEIVE.name,
                    title = name,
                    payload =
                        JSONObject()
                            .put("path", part.absolutePath)
                            .put("name", name)
                            .put("ready", false)
                            .toString(),
                    state = TransferState.RUNNING.name,
                )
            runBlocking { db.tasks().save(task) }
            session
                .write(DefaultFtpReply(150, "Opening binary data connection"))
                .awaitUninterruptibly(10000)
            val connection =
                try {
                    connectionFactory.openConnection()
                } catch (error: Exception) {
                    throw IOException("无法建立数据连接", error)
                }
            val received =
                FileOutputStream(part).use { output ->
                    val count =
                        FtpTransferIo.receive(output) {
                            connection.transferFromClient(session.ftpletSession, it)
                        }
                    output.fd.sync()
                    count
                }
            check(
                received >= 0 &&
                    part.length() == received &&
                    (!CameraFile(name = name).image || received > 0)
            ) {
                "上传长度不符或照片为空，已保留暂存文件"
            }
            check(part.renameTo(destination)) { "原片持久化失败，已保留暂存文件" }
            MediaFiles.syncDirectory(destination.parentFile!!)
            val ready =
                task.copy(
                    payload =
                        JSONObject()
                            .put("path", destination.absolutePath)
                            .put("name", name)
                            .put("ready", true)
                            .toString(),
                    state = TransferState.QUEUED.name,
                    done = received,
                    total = received,
                )
            runBlocking { db.tasks().save(ready) }
            val ftpFile = session.fileSystemView.getFile("received/${destination.name}")
            (context.ftpStatistics as? ServerFtpStatistics)?.setUpload(session, ftpFile, received)
            session.write(
                DefaultFtpReply(
                    FtpReply.REPLY_226_CLOSING_DATA_CONNECTION,
                    "Original saved; gallery processing queued",
                )
            )
            status.value = "原片已保存，正在加入图库"
            onReady()
        } catch (error: Exception) {
            task?.let { incoming ->
                runBlocking {
                    db.tasks()
                        .save(
                            incoming.copy(
                                state = TransferState.FAILED.name,
                                error = error.message ?: "上传未完整结束，请让相机重新发送",
                            )
                        )
                }
            }
            session.write(
                DefaultFtpReply(
                    if (error is SocketException) 426 else 551,
                    "Upload not committed; resend the original file",
                )
            )
        } finally {
            session.resetState()
            session.dataConnection.closeDataConnection()
        }
    }
}
