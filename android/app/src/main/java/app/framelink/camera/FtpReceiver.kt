package app.framelink.camera

import android.content.Context
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.command.CommandFactoryFactory
import org.apache.ftpserver.ftplet.DefaultFtpReply
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpReply
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.Md5PasswordEncryptor
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission

/** 仅接收相机上传，原片持久化和任务落库完成后才回复成功 */
class FtpReceiver(
    private val context: Context,
    private val db: LocalDatabase,
    private val scope: CoroutineScope,
    private val status: MutableStateFlow<String>,
    private val notice: MutableStateFlow<String>,
    private val onReady: () -> Unit = {},
) {
    private val root = File(context.filesDir, "media/ftp").apply { mkdirs() }
    private var server: FtpServer? = null
    private val clients = AtomicInteger()

    fun start(address: String, port: Int, username: String, password: String) {
        check(port in 1024..65535) { "接收端口应为1024至65535" }
        check(username.isNotBlank() && password.length >= 8) { "请填写账号和至少8位接收密码" }
        check(address in addresses() || address == "127.0.0.1") { "请选择手机当前实际网络地址" }
        val factory = FtpServerFactory()
        val usersFile = File(context.cacheDir, "ftp-users.properties").apply { writeText("") }
        val users =
            PropertiesUserManagerFactory()
                .apply {
                    file = usersFile
                    passwordEncryptor = Md5PasswordEncryptor()
                }
                .createUserManager()
        try{users.save(
            BaseUser().apply {
                name = username
                setPassword(password)
                homeDirectory = root.absolutePath
                authorities = listOf(WritePermission())
            }
        )}finally{usersFile.delete()}
        factory.userManager = users
        val data =
            DataConnectionConfigurationFactory().apply {
                passiveAddress = address
                passiveExternalAddress = address
                passivePorts = "50000-50030"
            }
        val listener =
            ListenerFactory().apply {
                setPort(port)
                serverAddress = address
                dataConnectionConfiguration = data.createDataConnectionConfiguration()
            }
        factory.addListener("default", listener.createListener())
        val commands = CommandFactoryFactory()
        commands.commandMap["STOR"] = DurableStor(root, db, status, onReady)
        factory.commandFactory = commands.createCommandFactory()
        factory.ftplets =
            mutableMapOf(
                "framelink" to
                    object : DefaultFtplet() {
                        override fun onConnect(session: FtpSession): FtpletResult {
                            clients.incrementAndGet()
                            status.value = "设备已连接，等待登录"
                            return FtpletResult.DEFAULT
                        }

                        override fun onDisconnect(session: FtpSession): FtpletResult {
                            if (clients.decrementAndGet() <= 0) status.value = "服务运行中，等待相机连接"
                            return FtpletResult.DEFAULT
                        }

                        override fun afterCommand(session: FtpSession, request: FtpRequest, reply: FtpReply): FtpletResult {
                            if (request.command == "PASS") {
                                status.value = if (reply.code == 230) "设备已登录，等待照片"
                                    else "登录未成功，请检查相机中的账号和密码"
                            }
                            return super.afterCommand(session, request, reply) ?: FtpletResult.DEFAULT
                        }

                        override fun beforeCommand(
                            session: FtpSession,
                            request: FtpRequest,
                        ): FtpletResult {
                            if (
                                request.command in
                                    setOf("DELE", "RMD", "RETR", "REST", "APPE", "RNFR", "RNTO")
                            ) {
                                session.write(
                                    DefaultFtpReply(
                                        502,
                                        "Receive-only server; resume requires a complete resend",
                                    )
                                )
                                return FtpletResult.SKIP
                            }
                            return FtpletResult.DEFAULT
                        }
                    }
            )
        server = factory.createServer().also { it.start() }
        status.value = "服务已启动 · $address:$port · 等待相机登录"
    }

    fun stop() {
        server?.stop()
        server = null
        clients.set(0)
    }

    companion object {
        fun addresses(): List<String> =
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter {
                    it.isUp &&
                        !it.isLoopback &&
                        !it.isVirtual &&
                        !it.name.matches(Regex("^(rmnet|ccmni|pdp|tun|ppp).*"))
                }
                .sortedBy { if (it.name.matches(Regex("^(wlan|ap|swlan).*"))) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
    }
}
