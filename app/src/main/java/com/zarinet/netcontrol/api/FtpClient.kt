package com.zarinet.netcontrol.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/** عميل FTP بسيط (Passive) لتنزيل ملفات النسخ الاحتياطي من الراوتر. يتطلب تفعيل خدمة ftp. */
object FtpClient {
    suspend fun download(host: String, user: String, pass: String, remote: String, dest: File) = withContext(Dispatchers.IO) {
        val ctl = Socket()
        try {
            ctl.connect(InetSocketAddress(host, 21), 8000)
            ctl.soTimeout = 20000
            val r = BufferedReader(InputStreamReader(ctl.getInputStream(), Charsets.UTF_8))
            val w = OutputStreamWriter(ctl.getOutputStream(), Charsets.UTF_8)
            fun reply(): Pair<Int, String> {
                var line = r.readLine() ?: throw RouterOsException("انقطع اتصال FTP")
                val code = line.take(3)
                val sb = StringBuilder(line)
                if (line.length > 3 && line[3] == '-') {
                    while (true) {
                        line = r.readLine() ?: break
                        sb.append('\n').append(line)
                        if (line.startsWith("$code ")) break
                    }
                }
                return (code.toIntOrNull() ?: 0) to sb.toString()
            }
            fun cmd(c: String): Pair<Int, String> { w.write(c + "\r\n"); w.flush(); return reply() }
            fun expect(res: Pair<Int, String>, ok: (Int) -> Boolean) { if (!ok(res.first)) throw RouterOsException("FTP: ${res.second}") }

            expect(reply()) { it == 220 }
            val u = cmd("USER $user")
            if (u.first == 331) expect(cmd("PASS $pass")) { it == 230 } else expect(u) { it == 230 }
            expect(cmd("TYPE I")) { it == 200 }
            val pasv = cmd("PASV"); expect(pasv) { it == 227 }
            val nums = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(pasv.second)?.groupValues?.drop(1)?.map { it.toInt() }
                ?: throw RouterOsException("FTP: رد PASV غير مفهوم")
            val port = nums[4] * 256 + nums[5]
            val data = Socket()
            data.connect(InetSocketAddress(host, port), 8000)
            data.soTimeout = 30000
            val retr = cmd("RETR $remote")
            if (retr.first != 150 && retr.first != 125) { data.close(); throw RouterOsException("FTP: ${retr.second}") }
            data.getInputStream().use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            data.close()
            reply()
            runCatching { cmd("QUIT") }
        } catch (e: RouterOsException) {
            throw e
        } catch (e: Exception) {
            throw RouterOsException("تعذر التنزيل عبر FTP (تأكد من تفعيل خدمة ftp): ${e.message}")
        } finally {
            runCatching { ctl.close() }
        }
    }
}
