package app.yoru.tv.pairing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

data class PairResult(val ok: Boolean, val messages: List<String>)

/**
 * Крошечный HTTP-сервер в локальной сети: телефон открывает страницу по QR-коду
 * и вставляет туда код Shikimori и токен Anime365 — печатать их пультом не нужно.
 * Работает, только пока открыт экран подключения.
 */
class PairingServer(
    private val ctx: Context,
    private val page: () -> Map<String, String>,
    private val onSubmit: suspend (Map<String, String>) -> PairResult,
) {
    val key: String = buildString {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        repeat(6) { append(abc[rnd.nextInt(abc.length)]) }
    }
    private var socket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var port: Int = 0
        private set

    fun start(): Boolean {
        for (p in listOf(8365, 8366, 8367, 0)) {
            try {
                socket = ServerSocket(p)
                port = socket!!.localPort
                break
            } catch (_: Exception) {
            }
        }
        val s = socket ?: return false
        scope.launch {
            while (isActive) {
                val client = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                launch { handle(client) }
            }
        }
        return true
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    fun urls(): List<String> = lanAddresses().map { "http://$it:$port/?k=$key" }

    private suspend fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 15000
                val input = s.getInputStream()
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val parts = lines.first().split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val headers = lines.drop(1).mapNotNull { l ->
                    val i = l.indexOf(':')
                    if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
                }.toMap()
                val path = target.substringBefore('?')
                val query = parseForm(target.substringAfter('?', ""))
                val len = headers["content-length"]?.toIntOrNull()?.coerceAtMost(64 * 1024) ?: 0
                val body = if (len > 0) readBody(input, len) else ""
                val out = s.getOutputStream()
                when {
                    method == "GET" && path == "/" -> {
                        if (query["k"] != key) {
                            respond(out, 403, "text/html; charset=utf-8", denied())
                        } else {
                            respond(out, 200, "text/html; charset=utf-8", render())
                        }
                    }
                    method == "POST" && path == "/submit" -> {
                        val form = parseForm(body)
                        val json = if (form["k"] != key) {
                            result(PairResult(false, listOf("Ссылка устарела: отсканируйте QR-код на телевизоре заново.")))
                        } else {
                            result(onSubmit(form))
                        }
                        respond(out, 200, "application/json; charset=utf-8", json)
                    }
                    else -> respond(out, 404, "text/plain; charset=utf-8", "Not found")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun render(): String {
        var html = ctx.assets.open("pair.html").bufferedReader().use { it.readText() }
        val vars = page() + ("KEY" to key)
        for ((k, v) in vars) html = html.replace("%%$k%%", htmlAttr(v))
        return html
    }

    private fun denied(): String =
        "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
            "<body style='font-family:sans-serif;background:#141020;color:#ece7f7;padding:24px'>" +
            "<h2>Ссылка устарела</h2><p>Откройте экран подключения на телевизоре и отсканируйте QR-код ещё раз.</p>"

    private fun result(r: PairResult): String = buildJsonObject {
        put("ok", r.ok)
        putJsonArray("messages") { r.messages.forEach { add(it) } }
    }.toString()

    private fun respond(out: java.io.OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            else -> "Not Found"
        }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString("UTF-8").trimEnd()
        }
        return null
    }

    private fun readBody(input: InputStream, len: Int): String {
        val data = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(data, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(data, 0, read, Charsets.UTF_8)
    }

    companion object {
        fun parseForm(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            dec(k) to dec(v)
        }

        private fun dec(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }

        /** Значение для подстановки в HTML (и в атрибуты, и в JSON внутри <script>). */
        fun htmlAttr(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        fun lanAddresses(): List<String> {
            val all = try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
            } catch (_: Exception) {
                emptyList()
            }
            return all.sortedBy { if (it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.")) 0 else 1 }
        }

        fun qr(text: String, size: Int = 512): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }

        @Suppress("unused")
        private fun js(s: String) = JsonPrimitive(s).toString()
    }
}
