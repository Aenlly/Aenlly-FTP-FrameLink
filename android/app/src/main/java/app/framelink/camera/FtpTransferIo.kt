package app.framelink.camera

import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** 文件流结束时的字节数与指纹；只有调用方完成落盘确认后才可发布文件 */
data class FtpTransferReceipt(val bytes: Long, val sha256: String)

/** FTP数据流使用固定内存，接收合并小块写入，客户端下载期间计算原片指纹 */
object FtpTransferIo {
    const val BUFFER_BYTES = 256 * 1024

    /** 不关闭调用方的文件流，返回后仍需fsync，再发送FTP成功回复 */
    fun receive(output: OutputStream, transfer: (OutputStream) -> Long): Long {
        // Apache复用传入的BufferedOutputStream，以此替代其默认8KiB写入缓冲
        // 接收指纹继续在ACK后的后台任务计算，避免相机等待校验后才能发送下一张
        val buffered = BufferedOutputStream(output, BUFFER_BYTES)
        val bytes = transfer(buffered)
        buffered.flush()
        return bytes
    }

    fun copy(
        input: InputStream,
        output: OutputStream,
        fingerprint: Boolean = false,
        progress: (Long) -> Unit,
    ): FtpTransferReceipt {
        val digest = if (fingerprint) MessageDigest.getInstance("SHA-256") else null
        val buffer = ByteArray(BUFFER_BYTES)
        var done = 0L
        while (true) {
            var count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                buffer[0] = single.toByte()
                count = 1
            }
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
            done += count
            progress(done)
        }
        output.flush()
        progress(done)
        return FtpTransferReceipt(done, digest?.let(::hex).orEmpty())
    }

    private fun hex(digest: MessageDigest): String =
        digest.digest().joinToString("") { "%02x".format(it) }
}
