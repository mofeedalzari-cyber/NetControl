package com.zarinet.netcontrol.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class RouterOsException(message: String) : Exception(message)

/** نتيجة أمر: الصفوف (!re) وخصائص !done (مثل ret). */
data class ApiResult(
    val rows: List<Map<String, String>>,
    val done: Map<String, String>
)

/**
 * عميل RouterOS API أصلي عبر TCP مباشر (المنفذ 8728 افتراضياً) — بدون WebView ولا مكتبات خارجية.
 * يدعم تسجيل الدخول الحديث (6.43+ / v7) والقديم (challenge MD5).
 * كل الأوامر تمر عبر Mutex حتى لا تتداخل جمل التحديث التلقائي مع أوامر المستخدم.
 * القراءات المتطابقة المتزامنة تُدمج في طلب واحد، ونتائج القراءة تُخزَّن مؤقتاً لمدة قصيرة حسب نوعها.
 */
open class RouterOsClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val connectTimeoutMs: Int = 8000
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val mutex = Mutex()

    open val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    open suspend fun connect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeQuietly()
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                s.soTimeout = 20000
                s.tcpNoDelay = true
                s.keepAlive = true
                socket = s
                input = BufferedInputStream(s.getInputStream())
                output = BufferedOutputStream(s.getOutputStream())
                login()
            } catch (e: RouterOsException) {
                closeQuietly(); throw e
            } catch (e: IOException) {
                closeQuietly()
                throw RouterOsException("تعذر الاتصال بـ $host:$port — ${e.message ?: "خطأ شبكة"}")
            }
        }
    }

    private fun login() {
        val r = talk(listOf("/login", "=name=$user", "=password=$password"))
        val challenge = r.done["ret"]
        if (!challenge.isNullOrEmpty()) {
            // RouterOS أقدم من 6.43
            val md = MessageDigest.getInstance("MD5")
            md.update(0.toByte())
            md.update(password.toByteArray(Charsets.UTF_8))
            md.update(hexToBytes(challenge))
            talk(listOf("/login", "=name=$user", "=response=00" + bytesToHex(md.digest())))
        }
    }

    /**
     * تنفيذ أمر.
     * @param params خصائص (=key=value)
     * @param queries استعلامات بصيغتها الخام مثل "?disabled=no"
     * @param proplist الحقول المطلوبة فقط (يسرّع القوائم الكبيرة)
     */
    open suspend fun exec(
        command: String,
        params: Map<String, String> = emptyMap(),
        queries: List<String> = emptyList(),
        proplist: List<String>? = null
    ): ApiResult {
        val words = ArrayList<String>()
        words += command
        params.forEach { (k, v) -> words += "=$k=$v" }
        if (proplist != null) words += "=.proplist=" + proplist.joinToString(",")
        words += queries
        return request(words)
    }

    /** طلبات جارية (لدمج القراءات المتطابقة) + ذاكرة مؤقتة للقراءات. */
    private val inflight = HashMap<List<String>, Deferred<ApiResult>>()
    private val cache = HashMap<List<String>, Cached>()
    private val stateLock = Any()
    private class Cached(val at: Long, val ttl: Long, val result: ApiResult)

    /** عدد الأوامر المرسلة فعلياً للراوتر (للقياس والاختبارات). */
    @Volatile var sentCount = 0
        private set

    private suspend fun request(words: List<String>): ApiResult {
        val read = isRead(words)
        val now = System.currentTimeMillis()
        val mine = CompletableDeferred<ApiResult>()
        var other: Deferred<ApiResult>? = null
        var hit: ApiResult? = null
        synchronized(stateLock) {
            if (read) cache[words]?.let { c -> if (now - c.at < c.ttl) hit = c.result else cache.remove(words) }
            if (hit == null) {
                other = inflight[words]
                if (other == null) {
                    inflight[words] = mine
                    // أي أمر تعديل يُبطل الذاكرة المؤقتة
                    if (!isLiveRead(words)) cache.clear()
                }
            }
        }
        hit?.let { return it }
        other?.let { d ->
            return try {
                d.await()
            } catch (e: CancellationException) {
                // أُلغي الطلب الأصلي (أُغلقت الشاشة التي بدأته) — نعيد المحاولة بأنفسنا إن كنا ما زلنا نشطين
                if (coroutineContext[Job]?.isActive == false) throw e
                request(words)
            }
        }
        try {
            val r = withContext(Dispatchers.IO) { transport(words) }
            synchronized(stateLock) {
                if (read) ttlFor(words).takeIf { it > 0 }?.let { cache[words] = Cached(System.currentTimeMillis(), it, r) }
                else if (!isLiveRead(words)) cache.clear()
            }
            mine.complete(r)
            return r
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            synchronized(stateLock) { if (inflight[words] === mine) inflight.remove(words) }
        }
    }

    fun invalidateCache() {
        synchronized(stateLock) { cache.clear() }
    }

    /** الإرسال الفعلي للراوتر (يُستبدل في الاختبارات). */
    protected open suspend fun transport(words: List<String>): ApiResult {
        sentCount++
        return locked { talk(words) }
    }

    private fun isRead(words: List<String>) = words.firstOrNull()?.endsWith("/print") == true

    /** قراءات لا تغيّر شيئاً (لا تُبطل الذاكرة المؤقتة). */
    private fun isLiveRead(words: List<String>): Boolean {
        val last = words.firstOrNull()?.substringAfterLast('/') ?: return false
        return last == "monitor-traffic" || last == "monitor" || last == "getall" || last == "ping" || last == "print"
    }

    /** مدة صلاحية نتيجة القراءة: البيانات شبه الثابتة 30 ثانية، الحية (عدادات/وقت تشغيل) بدون تخزين، والباقي 1.5 ثانية. */
    private fun ttlFor(words: List<String>): Long {
        val path = words.first().removeSuffix("/print")
        val props = words.firstOrNull { it.startsWith("=.proplist=") }?.removePrefix("=.proplist=")?.split(",")?.toSet()
        return when {
            path in SLOW_PATHS -> 30_000L
            path == "/interface" && props != null && props.all { it in setOf("name", "type", ".id", "comment", "disabled") } -> 30_000L
            path in LIVE_PATHS -> 0L
            props != null && props.any { it.contains("byte") || it.contains("bytes") || it == "uptime" } -> 0L
            else -> 1500L
        }
    }

    /** أمر واحد ضمن دفعة [batch]. */
    data class Cmd(
        val command: String,
        val params: Map<String, String> = emptyMap(),
        val queries: List<String> = emptyList(),
        val proplist: List<String>? = null
    )

    /**
     * إرسال عدة أوامر دفعة واحدة (Pipelining بوسم .tag) ثم قراءة كل الردود:
     * رحلة ذهاب وإياب واحدة لكل [chunk] أمر بدل رحلة لكل أمر — فرق كبير عبر السحابة (زمن ~200ms+ للرحلة).
     * كل أمر يعيد نتيجته أو خطأه بشكل مستقل وبنفس ترتيب الإدخال.
     */
    open suspend fun batch(cmds: List<Cmd>, chunk: Int = 40): List<Result<ApiResult>> = withContext(Dispatchers.IO) {
        if (cmds.isEmpty()) return@withContext emptyList()
        if (cmds.any { !it.command.endsWith("/print") }) invalidateCache()
        val out = ArrayList<Result<ApiResult>>(cmds.size)
        for (part in cmds.chunked(chunk.coerceAtLeast(1))) {
            sentCount += part.size
            out += locked {
                part.forEachIndexed { i, c ->
                    val words = ArrayList<String>()
                    words += c.command
                    c.params.forEach { (k, v) -> words += "=$k=$v" }
                    if (c.proplist != null) words += "=.proplist=" + c.proplist.joinToString(",")
                    words += c.queries
                    words += ".tag=$i"
                    writeSentence(words)
                }
                val rows = Array(part.size) { ArrayList<Map<String, String>>() }
                val done = arrayOfNulls<Map<String, String>>(part.size)
                val traps = arrayOfNulls<String>(part.size)
                var left = part.size
                while (left > 0) {
                    val sentence = readSentence()
                    if (sentence.isEmpty()) continue
                    if (sentence[0] == "!fatal") {
                        closeQuietly()
                        throw RouterOsException(sentence.drop(1).joinToString(" ").ifBlank { "fatal" })
                    }
                    val tag = sentence.firstOrNull { it.startsWith(".tag=") }?.removePrefix(".tag=")?.toIntOrNull() ?: continue
                    if (tag !in part.indices) continue
                    val attrs = parseAttrs(sentence)
                    when (sentence[0]) {
                        "!re" -> rows[tag] += attrs
                        "!trap" -> if (traps[tag] == null) traps[tag] = attrs["message"] ?: "خطأ غير معروف"
                        "!done" -> if (done[tag] == null) { done[tag] = attrs; left-- }
                    }
                }
                part.indices.map { i ->
                    traps[i]?.let { Result.failure<ApiResult>(RouterOsException(it)) }
                        ?: Result.success(ApiResult(rows[i], done[i] ?: emptyMap()))
                }
            }
        }
        out
    }

    /** قراءات متعددة دفعة واحدة؛ القراءة الفاشلة تعيد قائمة فارغة (مثل runCatching). */
    suspend fun printAll(vararg reqs: Cmd): List<List<Map<String, String>>> =
        batch(reqs.map { it.copy(command = if (it.command.endsWith("/print")) it.command else it.command + "/print") })
            .map { r -> r.getOrNull()?.rows ?: emptyList() }

    suspend fun print(path: String, proplist: List<String>? = null, queries: List<String> = emptyList()) =
        exec("$path/print", queries = queries, proplist = proplist).rows

    suspend fun count(path: String, queries: List<String> = emptyList()): Int =
        exec("$path/print", mapOf("count-only" to ""), queries).done["ret"]?.toIntOrNull() ?: 0

    /** للـ API Console: يعيد الجمل كما هي. */
    open suspend fun raw(words: List<String>): List<List<String>> = withContext(Dispatchers.IO) {
        invalidateCache()
        locked {
            val out = ArrayList<List<String>>()
            writeSentence(words)
            while (true) {
                val s = readSentence()
                out += s
                val t = s.firstOrNull()
                if (t == "!done" || t == "!fatal") break
            }
            out
        }
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock {
        if (!isConnected) throw RouterOsException("غير متصل بالجهاز")
        try {
            block()
        } catch (e: RouterOsException) {
            throw e
        } catch (e: IOException) {
            closeQuietly()
            throw RouterOsException("انقطع الاتصال: ${e.message ?: ""}")
        }
    }

    private fun talk(words: List<String>): ApiResult {
        writeSentence(words)
        val rows = ArrayList<Map<String, String>>()
        var done: Map<String, String> = emptyMap()
        var trap: String? = null
        while (true) {
            val sentence = readSentence()
            if (sentence.isEmpty()) continue
            val attrs = parseAttrs(sentence)
            when (sentence[0]) {
                "!re" -> rows += attrs
                "!trap" -> if (trap == null) trap = attrs["message"] ?: "خطأ غير معروف"
                "!fatal" -> {
                    closeQuietly()
                    throw RouterOsException(sentence.drop(1).joinToString(" ").ifBlank { "fatal" })
                }
                "!done" -> { done = attrs; break }
                // !empty (v7.18+) وغيرها تُتجاهل
            }
        }
        trap?.let { throw RouterOsException(it) }
        return ApiResult(rows, done)
    }

    private fun parseAttrs(sentence: List<String>): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (i in 1 until sentence.size) {
            val w = sentence[i]
            if (w.startsWith("=")) {
                val idx = w.indexOf('=', 1)
                if (idx > 0) m[w.substring(1, idx)] = w.substring(idx + 1)
                else m[w.substring(1)] = ""
            }
        }
        return m
    }

    // ---------------- البروتوكول الخام ----------------

    private fun writeSentence(words: List<String>) {
        val out = output ?: throw IOException("socket closed")
        for (w in words) {
            val bytes = w.toByteArray(Charsets.UTF_8)
            writeLength(out, bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.flush()
    }

    private fun writeLength(out: OutputStream, len: Int) {
        when {
            len < 0x80 -> out.write(len)
            len < 0x4000 -> {
                val v = len or 0x8000
                out.write(v shr 8 and 0xFF); out.write(v and 0xFF)
            }
            len < 0x200000 -> {
                val v = len or 0xC00000
                out.write(v shr 16 and 0xFF); out.write(v shr 8 and 0xFF); out.write(v and 0xFF)
            }
            len < 0x10000000 -> {
                val v = len or 0xE0000000.toInt()
                out.write(v ushr 24 and 0xFF); out.write(v shr 16 and 0xFF)
                out.write(v shr 8 and 0xFF); out.write(v and 0xFF)
            }
            else -> {
                out.write(0xF0)
                out.write(len ushr 24 and 0xFF); out.write(len shr 16 and 0xFF)
                out.write(len shr 8 and 0xFF); out.write(len and 0xFF)
            }
        }
    }

    private fun readSentence(): List<String> {
        val words = ArrayList<String>()
        while (true) {
            val len = readLength()
            if (len == 0) break
            words += String(readFully(len), Charsets.UTF_8)
        }
        return words
    }

    private fun readByte(): Int {
        val b = (input ?: throw IOException("socket closed")).read()
        if (b < 0) throw EOFException("أغلق الراوتر الاتصال")
        return b
    }

    private fun readLength(): Int {
        val b = readByte()
        return when {
            b and 0x80 == 0 -> b
            b and 0xC0 == 0x80 -> ((b and 0x3F) shl 8) or readByte()
            b and 0xE0 == 0xC0 -> ((b and 0x1F) shl 16) or (readByte() shl 8) or readByte()
            b and 0xF0 == 0xE0 -> ((b and 0x0F) shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()
            b == 0xF0 -> (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()
            else -> throw IOException("طول غير صالح: $b")
        }
    }

    private fun readFully(n: Int): ByteArray {
        val inp = input ?: throw IOException("socket closed")
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return buf
    }

    open fun close() = closeQuietly()

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    private fun hexToBytes(h: String) = ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    private fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    companion object {
        /** مسارات بياناتها نادراً ما تتغير (تُخزَّن 30 ثانية). */
        val SLOW_PATHS = setOf(
            "/system/identity", "/system/routerboard", "/system/package", "/ppp/profile", "/ip/pool",
            "/ip/hotspot/user/profile", "/ip/hotspot/profile", "/user-manager/profile", "/user-manager/limitation",
            "/user-manager/profile-limitation", "/tool/user-manager/profile", "/tool/user-manager/profile/limitation",
            "/tool/user-manager/profile/profile-limitation", "/tool/user-manager/customer"
        )
        /** مسارات حية (لا تُخزَّن أبداً). */
        val LIVE_PATHS = setOf("/interface", "/ip/hotspot/active", "/ppp/active", "/system/resource", "/system/clock", "/system/health")
    }
}
