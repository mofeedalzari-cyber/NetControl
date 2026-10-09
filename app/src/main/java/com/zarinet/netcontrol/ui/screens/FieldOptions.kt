package com.zarinet.netcontrol.ui.screens

import com.zarinet.netcontrol.data.Session

/**
 * خيارات جاهزة لحقول نماذج الإضافة/التعديل العامة (جدار الحماية، NAT، العناوين، المسارات، DHCP...).
 * الخيار = (القيمة، وصف قصير). الحقل يبقى قابلاً للكتابة اليدوية أيضاً.
 */
object FieldOptions {
    private val filterActions = listOf(
        "accept" to "سماح", "drop" to "إسقاط بصمت", "reject" to "رفض مع رد", "fasttrack-connection" to "تسريع الاتصال",
        "jump" to "انتقال لسلسلة", "return" to "رجوع", "passthrough" to "تمرير", "log" to "تسجيل فقط", "tarpit" to "إبطاء",
        "add-src-to-address-list" to "إضافة المصدر لقائمة", "add-dst-to-address-list" to "إضافة الوجهة لقائمة")
    private val natActions = listOf(
        "masquerade" to "مشاركة الإنترنت", "src-nat" to "تغيير المصدر", "dst-nat" to "تحويل منفذ/عنوان", "redirect" to "تحويل للراوتر",
        "netmap" to "ربط شبكة بشبكة", "accept" to "سماح", "return" to "رجوع", "jump" to "انتقال لسلسلة", "passthrough" to "تمرير", "log" to "تسجيل فقط")
    private val protocols = listOf("tcp" to "TCP", "udp" to "UDP", "icmp" to "Ping", "gre" to "GRE", "ipsec-esp" to "IPsec ESP",
        "ipsec-ah" to "IPsec AH", "ospf" to "OSPF", "igmp" to "IGMP", "sctp" to "SCTP")
    private val ports = listOf("80" to "HTTP", "443" to "HTTPS", "53" to "DNS", "8291" to "Winbox", "8728" to "API", "8729" to "API-SSL",
        "22" to "SSH", "23" to "Telnet", "21" to "FTP", "3389" to "RDP", "1723" to "PPTP", "1701" to "L2TP", "500,4500" to "IPsec",
        "5060" to "SIP", "8080" to "HTTP بديل", "80,443" to "الويب")
    private val speeds = listOf("1M/1M", "2M/2M", "2M/4M", "5M/5M", "5M/10M", "10M/10M", "20M/20M", "50M/50M", "100M/100M").map { it to "رفع/تنزيل" }
    private val mangleActions = listOf(
        "mark-connection" to "تعليم الاتصال", "mark-packet" to "تعليم الحزمة", "mark-routing" to "تعليم التوجيه",
        "change-mss" to "تغيير MSS", "change-ttl" to "تغيير TTL", "change-dscp" to "تغيير DSCP", "set-priority" to "الأولوية",
        "accept" to "سماح", "passthrough" to "تمرير", "jump" to "انتقال لسلسلة", "return" to "رجوع", "log" to "تسجيل فقط",
        "add-src-to-address-list" to "إضافة المصدر لقائمة", "add-dst-to-address-list" to "إضافة الوجهة لقائمة", "route" to "توجيه")
    private val rawActions = listOf(
        "accept" to "سماح", "drop" to "إسقاط", "notrack" to "بدون تتبع", "jump" to "انتقال لسلسلة", "return" to "رجوع",
        "log" to "تسجيل فقط", "add-src-to-address-list" to "إضافة المصدر لقائمة", "add-dst-to-address-list" to "إضافة الوجهة لقائمة")
    private val yesNo = listOf("yes" to "نعم", "no" to "لا")

    /** خيارات ثابتة حسب اسم الحقل (ونوع القسم). */
    fun static(sectionKey: String, field: String): List<Pair<String, String>> = when (field) {
        "chain" -> when (sectionKey) {
            "nat" -> listOf("srcnat" to "من الداخل للخارج", "dstnat" to "من الخارج للداخل")
            "mangle" -> listOf("prerouting" to "قبل التوجيه", "input" to "إلى الراوتر نفسه", "forward" to "عابر عبر الراوتر",
                "output" to "من الراوتر", "postrouting" to "بعد التوجيه")
            "raw" -> listOf("prerouting" to "قبل التوجيه", "output" to "من الراوتر")
            else -> listOf("input" to "إلى الراوتر نفسه", "forward" to "عابر عبر الراوتر", "output" to "من الراوتر")
        }
        "action" -> when (sectionKey) {
            "nat" -> natActions
            "mangle" -> mangleActions
            "raw" -> rawActions
            else -> filterActions
        }
        "protocol" -> protocols
        "dst-port", "src-port", "to-ports", "port" -> ports
        "max-limit" -> speeds
        "rate-limit" -> speeds
        "timeout" -> listOf("1h" to "ساعة", "1d" to "يوم", "7d" to "أسبوع", "30d" to "شهر", "" to "دائم")
        "service" -> listOf("any" to "الكل", "pppoe" to "PPPoE", "pptp" to "PPTP", "l2tp" to "L2TP", "ovpn" to "OpenVPN", "sstp" to "SSTP")
        "group" -> listOf("full" to "كامل", "write" to "كتابة", "read" to "قراءة فقط")
        "distance" -> listOf("1" to "", "2" to "", "10" to "")
        "routing-table" -> listOf("main" to "الجدول الرئيسي")

        // ===== جدار الحماية =====
        "connection-state" -> listOf("established,related" to "القائمة والمرتبطة",
            "established,related,untracked" to "مع غير المتتبعة", "new" to "جديد", "invalid" to "غير صالح",
            "established" to "قائم", "related" to "مرتبط", "untracked" to "غير متتبع")
        "connection-nat-state" -> listOf("dstnat" to "حُوّلت وجهته", "srcnat" to "حُوّل مصدره",
            "srcnat,dstnat" to "الاثنين", "!dstnat" to "لم تُحوَّل وجهته")
        "connection-type" -> listOf("ftp", "h323", "irc", "pptp", "quake3", "sip", "tftp").map { it to "" }
        "reject-with" -> listOf("icmp-network-unreachable" to "", "icmp-host-unreachable" to "", "icmp-port-unreachable" to "",
            "icmp-admin-prohibited" to "", "tcp-reset" to "")
        "address-list-timeout" -> listOf("none-dynamic" to "حتى إعادة التشغيل", "none-static" to "دائم",
            "1h" to "ساعة", "1d" to "يوم", "7d" to "أسبوع")
        "new-mss" -> listOf("clamp-to-pmtu" to "حسب MTU", "1360" to "", "1400" to "", "1452" to "PPPoE")
        "enabled" -> listOf("auto" to "تلقائي", "yes" to "نعم", "no" to "لا")
        "log", "passthrough" -> yesNo

        // ===== DHCP =====
        "lease-time", "bootp-lease-time" -> listOf("00:10:00" to "10 دقائق", "00:30:00" to "30 دقيقة", "01:00:00" to "ساعة",
            "1d" to "يوم", "3d" to "3 أيام", "7d" to "أسبوع", "forever" to "دائم", "lease-time" to "نفس مدة العقد")
        "authoritative" -> listOf("yes" to "نعم", "no" to "لا", "after-2sec-delay" to "بعد ثانيتين", "after-10sec-delay" to "بعد 10 ثوانٍ")
        "bootp-support" -> listOf("none" to "لا شيء", "static" to "ثابت", "dynamic" to "ديناميكي")
        "use-radius" -> listOf("no" to "لا", "yes" to "نعم", "accounting" to "محاسبة فقط")
        "dynamic-lease-identifiers" -> listOf("client-mac" to "MAC العميل", "client-id" to "معرّف العميل", "client-mac,client-id" to "الاثنين")
        "insert-queue-before" -> listOf("first" to "الأول", "bottom" to "الأخير")
        "client-mac-limit" -> listOf("-1" to "بدون حد", "1" to "", "2" to "", "5" to "")
        "delay-threshold" -> listOf("none" to "بدون", "1s" to "", "2s" to "", "5s" to "")
        "relay" -> listOf("0.0.0.0" to "بدون")
        "use-reconfigure", "use-framed-as-classless", "support-broadband-tr101", "always-broadcast", "add-arp",
        "conflict-detection", "allow-dual-stack-queue", "block-access", "dns-none", "ntp-none", "accounting",
        "use-peer-dns", "use-peer-ntp", "allow-reconfigure" -> yesNo
        "store-leases-disk" -> listOf("immediately" to "فوراً", "5m" to "كل 5 دقائق", "30m" to "كل 30 دقيقة",
            "1h" to "كل ساعة", "never" to "أبداً")
        "interim-update" -> listOf("0s" to "بدون", "1m" to "دقيقة", "5m" to "5 دقائق")
        "netmask" -> listOf("24" to "255.255.255.0", "16" to "255.255.0.0", "8" to "255.0.0.0", "0" to "من الشبكة")
        "dns-server" -> listOf("8.8.8.8,1.1.1.1" to "Google + Cloudflare", "8.8.8.8,8.8.4.4" to "Google", "1.1.1.1,1.0.0.1" to "Cloudflare")
        "ntp-server" -> listOf("162.159.200.1" to "time.cloudflare.com")
        "code" -> listOf("3" to "Router", "6" to "DNS", "15" to "Domain", "42" to "NTP", "43" to "Vendor", "60" to "Vendor Class",
            "61" to "Client ID", "66" to "TFTP Server", "67" to "Boot File", "82" to "Relay Agent", "114" to "Captive Portal", "138" to "CAPWAP")
        "matching-type" -> listOf("exact" to "مطابقة تامة", "substring" to "جزء من القيمة")
        "alert-timeout" -> listOf("1h" to "ساعة", "1d" to "يوم", "none" to "بدون")
        "add-default-route" -> listOf("yes" to "نعم", "no" to "لا", "special-classless" to "Classless خاص")
        "default-route-distance" -> listOf("1" to "", "2" to "", "10" to "")
        "check-gateway" -> listOf("none" to "بدون", "arp" to "ARP", "ping" to "Ping")
        "vlan-priority" -> (0..7).map { it.toString() to "" }
        else -> emptyList()
    }

    /** خيارات من الراوتر نفسه: الواجهات، الشبكات، قوائم العناوين، البروفايلات، العلامات، خيارات DHCP... */
    suspend fun dynamic(sectionKey: String, fields: Set<String>, rows: List<Map<String, String>>): Map<String, List<Pair<String, String>>> {
        val api = Session.api()
        val out = HashMap<String, List<Pair<String, String>>>()
        suspend fun safe(block: suspend () -> List<Pair<String, String>>) = runCatching { block() }.getOrDefault(emptyList())

        val ifaceKeys = setOf("interface", "in-interface", "out-interface")
        if (fields.any { it in ifaceKeys } || "gateway" in fields) {
            val ifs = safe { api.print("/interface", listOf("name", "type", "comment")).mapNotNull { r -> r["name"]?.let { it to (r["comment"] ?: r["type"] ?: "") } } }
            ifaceKeys.filter { it in fields }.forEach { out[it] = ifs }
            if ("gateway" in fields) out["gateway"] = ifs
        }
        val addrKeys = setOf("src-address", "dst-address", "target", "to-addresses", "address")
        if (fields.any { it in addrKeys }) {
            val ips = runCatching { api.print("/ip/address", listOf("address", "network", "interface")) }.getOrDefault(emptyList())
            val networks = ips.mapNotNull { r -> val a = r["address"] ?: return@mapNotNull null; val n = r["network"] ?: return@mapNotNull null
                "$n/${a.substringAfter('/', "32")}" to (r["interface"] ?: "") }.distinct()
            val own = ips.mapNotNull { r -> r["address"]?.substringBefore('/')?.let { it to "عنوان الراوتر (${r["interface"] ?: ""})" } }
            val hosts = safe { api.print("/ip/dhcp-server/lease", listOf("address", "host-name", "comment")).take(200)
                .mapNotNull { r -> r["address"]?.let { it to (r["comment"] ?: r["host-name"] ?: "") } } }
            fields.filter { it in addrKeys }.forEach { k ->
                out[k] = when (k) {
                    "to-addresses" -> own + hosts
                    "address" -> if (sectionKey == "ip") emptyList() else networks + hosts
                    else -> networks + own + hosts
                }
            }
        }
        // قوائم الواجهات
        val ifListKeys = setOf("in-interface-list", "out-interface-list")
        if (fields.any { it in ifListKeys }) {
            val base = listOf("all" to "الكل", "none" to "لا شيء", "dynamic" to "ديناميكي", "static" to "ثابت")
            val lists = base + safe { api.print("/interface/list", listOf("name", "comment")).mapNotNull { r -> r["name"]?.let { it to (r["comment"] ?: "") } } }
            fields.filter { it in ifListKeys }.forEach { out[it] = lists.distinctBy { p -> p.first } }
        }
        // العلامات (من قواعد Mangle) وجداول التوجيه
        val markKeys = setOf("packet-mark", "connection-mark", "routing-mark", "new-packet-mark", "new-connection-mark", "new-routing-mark")
        if (fields.any { it in markKeys }) {
            val mangle = runCatching { api.print("/ip/firewall/mangle", listOf("new-packet-mark", "new-connection-mark", "new-routing-mark")) }.getOrDefault(emptyList())
            val pkt = mangle.mapNotNull { it["new-packet-mark"] }.distinct().map { it to "" }
            val conn = mangle.mapNotNull { it["new-connection-mark"] }.distinct().map { it to "" }
            val tables = safe { api.print("/routing/table", listOf("name")).mapNotNull { r -> r["name"]?.let { it to "جدول توجيه" } } }
            val routing = (listOf("main" to "الجدول الرئيسي") + mangle.mapNotNull { it["new-routing-mark"] }.distinct().map { it to "" } + tables)
                .distinctBy { it.first }
            if ("packet-mark" in fields) out["packet-mark"] = listOf("no-mark" to "بدون علامة") + pkt
            if ("connection-mark" in fields) out["connection-mark"] = listOf("no-mark" to "بدون علامة") + conn
            if ("routing-mark" in fields) out["routing-mark"] = routing
            if ("new-packet-mark" in fields) out["new-packet-mark"] = pkt
            if ("new-connection-mark" in fields) out["new-connection-mark"] = conn
            if ("new-routing-mark" in fields) out["new-routing-mark"] = routing
        }
        if ("jump-target" in fields)   // السلاسل الموجودة
            out["jump-target"] = rows.mapNotNull { it["chain"] }.distinct().map { it to "" }
        val listKeys = setOf("src-address-list", "dst-address-list", "list", "address-list")
        if (fields.any { it in listKeys }) {
            val lists = safe { api.print("/ip/firewall/address-list", listOf("list")).mapNotNull { it["list"] }.distinct().map { it to "" } }
            fields.filter { it in listKeys }.forEach { out[it] = lists }
        }
        if ("profile" in fields && sectionKey.startsWith("ppp"))
            out["profile"] = safe { api.print("/ppp/profile", listOf("name")).mapNotNull { r -> r["name"]?.let { it to "" } } }

        // ===== DHCP =====
        if (sectionKey.startsWith("dhcp")) {
            if ("address-pool" in fields)
                out["address-pool"] = listOf("static-only" to "ثابت فقط") +
                    safe { api.print("/ip/pool", listOf("name", "ranges")).mapNotNull { r -> r["name"]?.let { it to (r["ranges"] ?: "") } } }
            if (fields.any { it in setOf("dhcp-option-set", "option-set") }) {
                val sets = listOf("none" to "بدون") +
                    safe { api.print("/ip/dhcp-server/option/sets", listOf("name")).mapNotNull { r -> r["name"]?.let { it to "" } } }
                out["dhcp-option-set"] = sets
                out["option-set"] = sets
            }
            if (fields.any { it in setOf("dhcp-option", "options") }) {
                val opts = safe { api.print("/ip/dhcp-server/option", listOf("name", "code")).mapNotNull { r -> r["name"]?.let { it to "code ${r["code"] ?: ""}" } } }
                out["dhcp-option"] = opts
                out["options"] = opts
            }
            if ("dhcp-options" in fields)
                out["dhcp-options"] = listOf("hostname,clientid" to "الافتراضي") +
                    safe { api.print("/ip/dhcp-client/option", listOf("name", "code")).mapNotNull { r -> r["name"]?.let { it to "code ${r["code"] ?: ""}" } } }
            if ("parent-queue" in fields)
                out["parent-queue"] = listOf("none" to "بدون") +
                    safe { api.print("/queue/simple", listOf("name")).mapNotNull { r -> r["name"]?.let { it to "" } } }
            if (sectionKey == "dhcpnet") {   // عناوين الراوتر كبوابة / DNS / NTP
                val ips = safe { api.print("/ip/address", listOf("address", "interface")).mapNotNull { r ->
                    r["address"]?.substringBefore('/')?.let { it to (r["interface"] ?: "") } } }
                out["gateway"] = ips
                out["dns-server"] = ips + static(sectionKey, "dns-server")
                out["ntp-server"] = ips + static(sectionKey, "ntp-server")
                out["caps-manager"] = ips
                out["next-server"] = ips
            }
            if ("default-route-tables" in fields)
                out["default-route-tables"] = listOf("main" to "الجدول الرئيسي") +
                    safe { api.print("/routing/table", listOf("name")).mapNotNull { r -> r["name"]?.let { it to "" } } }
        }
        if ("server" in fields && (sectionKey == "dhcp" || sectionKey == "dhcpmatch"))
            out["server"] = safe { api.print("/ip/dhcp-server", listOf("name", "interface")).mapNotNull { r -> r["name"]?.let { it to (r["interface"] ?: "") } } }
        if ("chain" in fields) {   // السلاسل المخصصة الموجودة
            val custom = rows.mapNotNull { it["chain"] }.distinct().filter { it !in setOf("input", "forward", "output", "srcnat", "dstnat") }
            if (custom.isNotEmpty()) out["chain"] = custom.map { it to "مخصصة" }
        }
        return out
    }
}
