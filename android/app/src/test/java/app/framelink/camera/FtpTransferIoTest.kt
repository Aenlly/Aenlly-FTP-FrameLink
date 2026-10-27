package app.framelink.camera

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.apache.ftpserver.util.IoUtils
import org.junit.Assert.*
import org.junit.Test

class FtpTransferIoTest {
    @Test
    fun receiverCoalescesApacheBlocksAndKeepsTheExactOriginal() {
        val bytes = ByteArray(FtpTransferIo.BUFFER_BYTES * 3 + 73) { (it % 251).toByte() }
        val destination = RecordingOutput()
        val receipt =
            FtpTransferIo.receive(destination) { output ->
                assertSame(output, IoUtils.getBufferedOutputStream(output))
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(4096, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
                bytes.size.toLong()
            }
        assertEquals(4, destination.writes)
        assertEquals(bytes.size.toLong(), receipt)
        assertArrayEquals(bytes, destination.toByteArray())
        assertFalse(destination.closed)
        assertTrue(destination.flushed)
    }

    @Test
    fun copyingHandlesShortReadsAndNeverClosesCallerStreams() {
        val bytes = ByteArray(FtpTransferIo.BUFFER_BYTES + 91) { (it % 253).toByte() }
        val input =
            object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, minOf(length, 137))
            }
        val destination = RecordingOutput()
        val progress = mutableListOf<Long>()
        val receipt = FtpTransferIo.copy(input, destination, fingerprint = true) { progress += it }
        assertArrayEquals(bytes, destination.toByteArray())
        assertEquals(hash(bytes), receipt.sha256)
        assertEquals(bytes.size.toLong(), receipt.bytes)
        assertEquals(receipt.bytes, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        assertFalse(destination.closed)
    }

    @Test
    fun emptyAndZeroLengthReadsAreHandledWithoutLosingBytes() {
        val empty =
            FtpTransferIo.copy(ByteArrayInputStream(byteArrayOf()), RecordingOutput(), true) {}
        assertEquals(0L, empty.bytes)
        assertEquals(hash(byteArrayOf()), empty.sha256)
        val bytes = byteArrayOf(0, -1, 1)
        val input =
            object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
            }
        val destination = RecordingOutput()
        val receipt = FtpTransferIo.copy(input, destination) {}
        assertEquals("", receipt.sha256)
        assertArrayEquals(bytes, destination.toByteArray())
    }

    @Test
    fun cancellationAndStorageErrorsNeverReturnSuccess() {
        val bytes = ByteArray(FtpTransferIo.BUFFER_BYTES * 2)
        val destination = RecordingOutput()
        assertThrows(IllegalStateException::class.java) {
            FtpTransferIo.copy(ByteArrayInputStream(bytes), destination) { error("任务已取消") }
        }
        assertEquals(FtpTransferIo.BUFFER_BYTES, destination.size())
        val broken =
            object : OutputStream() {
                override fun write(value: Int) {
                    throw IOException("磁盘已满")
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    throw IOException("磁盘已满")
                }
            }
        assertThrows(IOException::class.java) {
            FtpTransferIo.receive(broken) {
                it.write(byteArrayOf(1, 2, 3))
                3L
            }
        }
        val disconnected =
            object : InputStream() {
                override fun read(): Int {
                    throw IOException("连接中断")
                }
            }
        assertThrows(IOException::class.java) {
            FtpTransferIo.copy(disconnected, RecordingOutput()) {}
        }
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class RecordingOutput : ByteArrayOutputStream() {
        var writes = 0
        var closed = false
        var flushed = false

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writes++
            super.write(bytes, offset, length)
        }

        override fun flush() {
            flushed = true
        }

        override fun close() {
            closed = true
        }
    }
}
