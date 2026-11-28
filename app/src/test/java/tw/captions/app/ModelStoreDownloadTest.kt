package tw.captions.app

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.captions.app.engine.ModelStore
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

private const val CRLF = "\r\n"

class ModelStoreDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    private val body = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private var supportRange = true
    @Volatile private var lastRange: String? = null
    private lateinit var server: ServerSocket
    private val url get() = "http://127.0.0.1:${server.localPort}/model.bin"

    /** A tiny HTTP server (com.sun.net.httpserver is not visible to Android unit tests). */
    @Before
    fun startServer() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var range: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                    }
                    lastRange = range
                    val from = range?.takeIf { supportRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                    val out = s.getOutputStream()
                    val head = if (from != null) {
                        listOf("HTTP/1.1 206 Partial Content", "Content-Range: bytes $from-${body.size - 1}/${body.size}", "Content-Length: ${body.size - from}", "Connection: close", "", "").joinToString(CRLF)
                    } else {
                        listOf("HTTP/1.1 200 OK", "Content-Length: ${body.size}", "Connection: close", "", "").joinToString(CRLF)
                    }
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    val start = from ?: 0
                    out.write(body, start, body.size - start)
                    out.flush()
                }
            }
        }
    }

    @After
    fun stopServer() = server.close()

    private fun store() = ModelStore(tmp.root, freeBytes = { Long.MAX_VALUE })

    @Test
    fun resumesAPartialFile() = runBlocking {
        val part = File(tmp.root, "model.bin.part")
        part.writeBytes(body.copyOf(50_000))
        var lastProgress = 0L
        store().download(url, part, body.size.toLong()) { lastProgress = it }
        assertEquals("bytes=50000-", lastRange)
        assertArrayEquals(body, part.readBytes())
        assertEquals(body.size.toLong(), lastProgress)
    }

    @Test
    fun startsOverWhenTheServerIgnoresRange() = runBlocking {
        supportRange = false
        val part = File(tmp.root, "model.bin.part")
        part.writeBytes(ByteArray(50_000) { 7 })
        store().download(url, part, body.size.toLong()) {}
        assertArrayEquals(body, part.readBytes())
    }

    @Test
    fun freshDownloadSendsNoRange() = runBlocking {
        val part = File(tmp.root, "model.bin.part")
        store().download(url, part, body.size.toLong()) {}
        assertEquals(null, lastRange)
        assertArrayEquals(body, part.readBytes())
    }

    @Test
    fun refusesWhenThePhoneIsFull() = runBlocking {
        val store = ModelStore(tmp.root, freeBytes = { 1_000L })
        try {
            store.ensure(listOf(ModelStore.SHARED)) {}
            fail("expected NotEnoughSpace")
        } catch (e: ModelStore.NotEnoughSpace) {
            // Twice the download: room to download and to unpack.
            assertEquals(ModelStore.SHARED.downloadBytes * 2, e.neededBytes)
        }
        assertTrue(ModelStore.isNoSpace(java.io.IOException("write failed: ENOSPC (No space left on device)")))
    }
}
