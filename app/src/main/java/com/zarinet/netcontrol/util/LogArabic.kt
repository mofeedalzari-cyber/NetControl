package com.zarinet.netcontrol.util

import java.util.regex.Matcher

/**
 * ترجمة سجل الراوتر (Log) إلى العربية: رسائل معروفة بقواعد نمطية، ثم استبدال العبارات الشائعة،
 * وأسماء المواضيع (topics).
 */
object LogArabic {
    /** طريقة الدخول للراوتر. */
    private fun via(v: String): String = when (v.lowercase()) {
        "winbox" -> "Winbox"
        "web" -> "الويب (WebFig)"
        "ssh" -> "SSH"
        "telnet" -> "Telnet"
        "api" -> "API"
        "api-ssl" -> "API-SSL"
        "ftp" -> "FTP"
        "dude" -> "The Dude"
        "tcp-msg" -> "رسائل TCP"
        "local" -> "محلياً"
        else -> v
    }

    /** أسباب الخروج/فشل الدخول (هوتسبوت، PPP، يوزر منجر). */
    private val reasons = listOf(
        "invalid username or password" to "اسم المستخدم أو كلمة المرور غير صحيحة",
        "invalid password" to "كلمة المرور غير صحيحة",
        "invalid username" to "اسم المستخدم غير صحيح",
        "user not found" to "المستخدم غير موجود",
        "no more sessions are allowed for user" to "الكرت مستخدم على جهاز آخر (لا يُسمح بجلسات إضافية)",
        "uptime limit reached" to "انتهى الوقت المسموح",
        "session timeout" to "انتهت مدة الجلسة",
        "keepalive timeout" to "انقطع الجهاز (انتهت مهلة الاستجابة)",
        "idle timeout" to "خمول الجهاز",
        "traffic limit reached" to "انتهى رصيد البيانات",
        "limit-bytes-total reached" to "انتهى رصيد البيانات",
        "lost-service" to "فقدان الخدمة",
        "lost service" to "فقدان الخدمة",
        "admin" to "بواسطة المدير",
        "user request" to "بطلب المستخدم",
        "nas request" to "بطلب الراوتر",
        "radius server is not responding" to "خادم RADIUS لا يستجيب",
        "radius timeout" to "انتهت مهلة RADIUS",
        "user has no active profiles" to "لا توجد باقة فعالة للكرت",
        "your session has expired" to "انتهت صلاحية الكرت",
        "your mac address is not allowed" to "عنوان MAC غير مسموح (الكرت مربوط بجهاز آخر)",
        "caller id mismatch" to "الكرت مربوط بجهاز آخر",
        "peer is not responding" to "الطرف الآخر لا يستجيب",
        "hungup" to "أُغلق الاتصال",
        "authentication failed" to "فشل التحقق",
        "disabled" to "معطل"
    )

    private fun reason(r: String): String {
        val t = r.trim()
        return reasons.firstOrNull { t.equals(it.first, ignoreCase = true) }?.second ?: phrases(t)
    }

    private val IP = "(\\d{1,3}(?:\\.\\d{1,3}){3}|[0-9a-fA-F:]{2,}:[0-9a-fA-F:]+)"
    private val MAC = "([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})"

    /** قواعد الرسائل المعروفة (تُطابق الرسالة كاملة). */
    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("^login failure for user (.+?) from $IP via (\\S+)$", RegexOption.IGNORE_CASE) to { m -> "فشل دخول المستخدم ${m.gv(1)} إلى الراوتر من ${m.gv(2)} عبر ${via(m.gv(3))}" },
        Regex("^user (.+?) logged in from $IP via (\\S+)$", RegexOption.IGNORE_CASE) to { m -> "دخل المستخدم ${m.gv(1)} إلى الراوتر من ${m.gv(2)} عبر ${via(m.gv(3))}" },
        Regex("^user (.+?) logged in via (\\S+)$", RegexOption.IGNORE_CASE) to { m -> "دخل المستخدم ${m.gv(1)} إلى الراوتر عبر ${via(m.gv(2))}" },
        Regex("^user (.+?) logged out from $IP via (\\S+)$", RegexOption.IGNORE_CASE) to { m -> "خرج المستخدم ${m.gv(1)} من الراوتر (${m.gv(2)} عبر ${via(m.gv(3))})" },
        Regex("^user (.+?) logged out via (\\S+)$", RegexOption.IGNORE_CASE) to { m -> "خرج المستخدم ${m.gv(1)} من الراوتر عبر ${via(m.gv(2))}" },
        Regex("^(.+?) (added|changed|removed|moved|enabled|disabled) by (.+?)$", RegexOption.IGNORE_CASE) to { m ->
            val act = when (m.gv(2).lowercase()) {
                "added" -> "أضاف"
                "changed" -> "عدّل"
                "removed" -> "حذف"
                "moved" -> "نقل"
                "enabled" -> "فعّل"
                else -> "عطّل"
            }
            "$act ${who(m.gv(3))}: ${m.gv(1)}"
        },
        Regex("^configuration changed by (.+)$", RegexOption.IGNORE_CASE) to { m -> "عُدّلت الإعدادات بواسطة ${who(m.gv(1))}" },
        Regex("^router rebooted.*$", RegexOption.IGNORE_CASE) to { _ -> "أُعيد تشغيل الراوتر" },
        Regex("^router was rebooted without proper shutdown.*$", RegexOption.IGNORE_CASE) to { _ -> "أُعيد تشغيل الراوتر بشكل مفاجئ (غالباً انقطاع كهرباء أو تعليق)" },
        Regex("^kernel failure in previous boot$", RegexOption.IGNORE_CASE) to { _ -> "حدث خطأ في النظام (kernel) في التشغيل السابق" },
        Regex("^out of memory.*$", RegexOption.IGNORE_CASE) to { _ -> "نفدت ذاكرة الراوتر (RAM)" },
        Regex("^system clock changed.*$", RegexOption.IGNORE_CASE) to { _ -> "تغيّرت ساعة النظام" },
        Regex("^installed (.+)$", RegexOption.IGNORE_CASE) to { m -> "تم تثبيت ${m.gv(1)}" },
        Regex("^watchdog.*(reboot|timeout).*$", RegexOption.IGNORE_CASE) to { m -> "المراقب (watchdog) أعاد تشغيل الراوتر: ${m.value}" },
        Regex("^(\\S+) link up(?: \\((.+)\\))?$", RegexOption.IGNORE_CASE) to { m -> "اتصلت الواجهة ${m.gv(1)}" + m.gv(2).let { if (it.isBlank()) "" else " (${phrases(it)})" } },
        Regex("^(\\S+) link down$", RegexOption.IGNORE_CASE) to { m -> "انقطع اتصال الواجهة ${m.gv(1)}" },
        Regex("^(\\S+)@(\\S+): connected(.*)$", RegexOption.IGNORE_CASE) to { m -> "اتصل الجهاز ${m.gv(1)} بالواجهة ${m.gv(2)}" + phrases(m.gv(3)) },
        Regex("^(\\S+)@(\\S+): disconnected,? ?(.*)$", RegexOption.IGNORE_CASE) to { m -> "انفصل الجهاز ${m.gv(1)} عن الواجهة ${m.gv(2)}" + m.gv(3).let { if (it.isBlank()) "" else " — ${phrases(it)}" } },
        Regex("^->: (.+?) \\($IP\\): login failed: (.+)$", RegexOption.IGNORE_CASE) to { m -> "فشل دخول الكرت ${m.gv(1)} (${m.gv(2)}): ${reason(m.gv(3))}" },
        Regex("^->: (.+?) \\($IP\\): logged in$", RegexOption.IGNORE_CASE) to { m -> "دخل الكرت ${m.gv(1)} (${m.gv(2)})" },
        Regex("^->: (.+?) \\($IP\\): logged out: (.+)$", RegexOption.IGNORE_CASE) to { m -> "خرج الكرت ${m.gv(1)} (${m.gv(2)}): ${reason(m.gv(3))}" },
        Regex("^->: (.+?) \\($IP\\): trying to log in by (.+)$", RegexOption.IGNORE_CASE) to { m -> "الكرت ${m.gv(1)} (${m.gv(2)}) يحاول الدخول (${m.gv(3)})" },
        Regex("^(.+?) \\($IP\\): login failed: (.+)$", RegexOption.IGNORE_CASE) to { m -> "فشل دخول الكرت ${m.gv(1)} (${m.gv(2)}): ${reason(m.gv(3))}" },
        Regex("^(.+?) \\($IP\\): logged out: (.+)$", RegexOption.IGNORE_CASE) to { m -> "خرج الكرت ${m.gv(1)} (${m.gv(2)}): ${reason(m.gv(3))}" },
        Regex("^(.+?) \\($IP\\): logged in$", RegexOption.IGNORE_CASE) to { m -> "دخل الكرت ${m.gv(1)} (${m.gv(2)})" },
        Regex("^<?(\\S+?)>?: authenticated$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: تم التحقق" },
        Regex("^<?(\\S+?)>?: connected$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: اتصل" },
        Regex("^<?(\\S+?)>?: disconnected$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: انفصل" },
        Regex("^<?(\\S+?)>?: terminating\\.*\\s*-?\\s*(.*)$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: إنهاء الاتصال" + m.gv(2).let { if (it.isBlank()) "" else " — ${reason(it)}" } },
        Regex("^<?(\\S+?)>?: user (.+?) authentication failed$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: فشل تحقق المستخدم ${m.gv(2)}" },
        Regex("^user (.+?) authentication failed$", RegexOption.IGNORE_CASE) to { m -> "فشل تحقق المستخدم ${m.gv(1)} (اسم أو كلمة مرور خاطئة)" },
        Regex("^<?(\\S+?)>?: initializing\\.*$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: بدء الاتصال..." },
        Regex("^<?(\\S+?)>?: dialing\\.*$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: جاري الاتصال..." },
        Regex("^(\\S+) assigned $IP (?:for|to) $MAC(.*)$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: أُعطي العنوان ${m.gv(2)} للجهاز ${m.gv(3)}${m.gv(4)}" },
        Regex("^(\\S+) deassigned $IP (?:for|from) $MAC(.*)$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: أُلغي العنوان ${m.gv(2)} من الجهاز ${m.gv(3)}${m.gv(4)}" },
        Regex("^(\\S+) offering lease $IP for $MAC without success$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: عرض العنوان ${m.gv(2)} على الجهاز ${m.gv(3)} ولم يستجب" },
        Regex("^(\\S+) failed to give out IP address: (.+)$", RegexOption.IGNORE_CASE) to { m -> "${m.gv(1)}: فشل إعطاء عنوان IP — ${phrases(m.gv(2))}" },
        Regex("^dhcp-client on (\\S+) got IP address $IP$", RegexOption.IGNORE_CASE) to { m -> "عميل DHCP على ${m.gv(1)} حصل على العنوان ${m.gv(2)}" },
        Regex("^dhcp-client on (\\S+) lost IP address $IP(.*)$", RegexOption.IGNORE_CASE) to { m -> "عميل DHCP على ${m.gv(1)} فقد العنوان ${m.gv(2)}" + phrases(m.gv(3)) },
        Regex("^script error: (.+)$", RegexOption.IGNORE_CASE) to { m -> "خطأ في السكربت: ${phrases(m.gv(1))}" },
        Regex("^executing script (.+?) from (.+?) failed.*$", RegexOption.IGNORE_CASE) to { m -> "فشل تنفيذ السكربت ${m.gv(1)} من ${m.gv(2)}" },
        Regex("^error while running customized default configuration script: (.+)$", RegexOption.IGNORE_CASE) to { m -> "خطأ في سكربت الإعداد الافتراضي: ${phrases(m.gv(1))}" },
        Regex("^radius server (\\S+) is not responding$", RegexOption.IGNORE_CASE) to { m -> "خادم RADIUS ${m.gv(1)} لا يستجيب" },
        Regex("^fetch: (.+)$", RegexOption.IGNORE_CASE) to { m -> "التحميل (fetch): ${phrases(m.gv(1))}" },
        Regex("^error sending e-?mail(.*)$", RegexOption.IGNORE_CASE) to { m -> "فشل إرسال البريد" + phrases(m.gv(1)) },
        Regex("^phase1 negotiation failed(.*)$", RegexOption.IGNORE_CASE) to { m -> "فشل التفاوض في المرحلة الأولى لـ IPsec" + phrases(m.gv(1)) }
    )

    private fun MatchResult.gv(i: Int): String = groupValues.let { if (i in it.indices) it[i] else "" }

    /** اسم المستخدم مع طريقة الدخول بين قوسين: "admin (winbox)" → "المدير (admin)". */
    private fun who(s: String): String {
        val t = s.trim()
        val name = t.substringBefore(" (").trim()
        if (name.equals("admin", ignoreCase = true)) return "المدير (admin)"
        val rest = t.substringAfter(" (", "")
        return name + if (rest.isNotBlank()) " (" + phrases(rest) else ""
    }

    /** عبارات شائعة تُستبدل داخل أي رسالة (كلمات كاملة فقط). */
    private val phraseList = listOf(
        "no such item" to "العنصر غير موجود",
        "no such command" to "الأمر غير موجود",
        "expected end of command" to "نهاية أمر غير متوقعة",
        "syntax error" to "خطأ في الصيغة",
        "bad command name" to "اسم أمر خاطئ",
        "missing value" to "قيمة ناقصة",
        "input does not match any value of" to "القيمة غير مقبولة في",
        "invalid value" to "قيمة غير صحيحة",
        "failure:" to "فشل:",
        "not enough permissions" to "صلاحيات غير كافية",
        "already have such" to "يوجد عنصر مماثل مسبقاً",
        "already exists" to "موجود مسبقاً",
        "connection refused" to "رُفض الاتصال",
        "connection timeout" to "انتهت مهلة الاتصال",
        "connection timed out" to "انتهت مهلة الاتصال",
        "could not connect" to "تعذّر الاتصال",
        "failed to resolve" to "فشل تحليل الاسم (DNS)",
        "dns resolving failed" to "فشل تحليل الاسم (DNS)",
        "host unreachable" to "المضيف غير قابل للوصول",
        "network unreachable" to "الشبكة غير قابلة للوصول",
        "no route to host" to "لا يوجد مسار للمضيف",
        "peer is not responding" to "الطرف الآخر لا يستجيب",
        "authentication failed" to "فشل التحقق",
        "login failed" to "فشل الدخول",
        "not responding" to "لا يستجيب",
        "timeout" to "انتهت المهلة",
        "timed out" to "انتهت المهلة",
        "disconnected" to "انفصل",
        "connected" to "متصل",
        "failed" to "فشل",
        "error" to "خطأ",
        "full duplex" to "ثنائي الاتجاه",
        "half duplex" to "أحادي الاتجاه",
        "speed" to "السرعة",
        "extensive data loss" to "فقدان كبير للبيانات",
        "unicast key exchange timeout" to "انتهت مهلة تبادل مفتاح التشفير",
        "group key exchange timeout" to "انتهت مهلة تبادل مفتاح المجموعة",
        "received deauth" to "استقبل طلب فصل",
        "received disassoc" to "استقبل طلب فك ارتباط",
        "sending deauth" to "أرسل طلب فصل",
        "inactivity" to "عدم نشاط"
    )

    private val phraseRegex = phraseList.map { (en, ar) ->
        Regex("(?<![\\w-])" + Regex.escape(en) + "(?![\\w-])", RegexOption.IGNORE_CASE) to ar
    }

    internal fun phrases(s: String): String {
        var out = s
        for ((re, ar) in phraseRegex) out = re.replace(out, Matcher.quoteReplacement(ar))
        return out
    }

    /** ترجمة نص رسالة السجل. */
    fun message(msg: String): String {
        val m = msg.trim()
        if (m.isEmpty()) return m
        for ((re, f) in rules) {
            val r = re.matchEntire(m) ?: continue
            return runCatching { f(r) }.getOrDefault(m)
        }
        return phrases(m)
    }

    private val topicNames = mapOf(
        "system" to "النظام",
        "info" to "معلومات",
        "error" to "خطأ",
        "critical" to "حرج",
        "warning" to "تحذير",
        "debug" to "تفاصيل",
        "account" to "حسابات",
        "hotspot" to "هوتسبوت",
        "interface" to "واجهة",
        "wireless" to "لاسلكي",
        "script" to "سكربت",
        "firewall" to "جدار الحماية",
        "dhcp" to "DHCP",
        "pppoe" to "PPPoE",
        "ppp" to "PPP",
        "l2tp" to "L2TP",
        "sstp" to "SSTP",
        "pptp" to "PPTP",
        "ovpn" to "OpenVPN",
        "ipsec" to "IPsec",
        "radius" to "RADIUS",
        "manager" to "يوزر منجر",
        "dns" to "DNS",
        "e-mail" to "البريد",
        "ntp" to "الوقت (NTP)",
        "route" to "التوجيه",
        "bridge" to "الجسر",
        "watchdog" to "المراقب",
        "web-proxy" to "البروكسي",
        "certificate" to "الشهادات",
        "health" to "صحة الجهاز",
        "fetch" to "التحميل",
        "packet" to "حزم",
        "event" to "حدث",
        "state" to "حالة",
        "ups" to "UPS",
        "lte" to "LTE",
        "async" to "Async",
        "caps" to "CAPsMAN",
        "ospf" to "OSPF",
        "bgp" to "BGP",
        "container" to "الحاويات",
        "raw" to "خام",
        "rip" to "RIP",
        "store" to "التخزين",
        "disk" to "القرص",
        "backup" to "النسخ"
    )

    fun topics(t: String): String =
        t.split(',').filter { it.isNotBlank() }.joinToString("، ") { topicNames[it.trim()] ?: it.trim() }

    fun isError(topics: String): Boolean =
        topics.split(',').any { it.trim() in setOf("error", "critical", "warning") }

    /** صف سجل مترجم: message و topics بالعربية، و original = النص الأصلي إن تغيّر. */
    fun row(r: Map<String, String>): Map<String, String> {
        val raw = r["message"].orEmpty()
        val msg = message(raw)
        val out = LinkedHashMap(r)
        out["message"] = if (isError(r["topics"].orEmpty()) && !msg.startsWith("⚠")) "⚠ $msg" else msg
        out["topics"] = topics(r["topics"].orEmpty())
        if (msg != raw) out["original"] = "\u200e" + raw
        return out
    }
}
