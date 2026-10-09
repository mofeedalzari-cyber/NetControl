package com.zarinet.netcontrol.util

import com.zarinet.netcontrol.data.Session

/**
 * صلاحية كروت الهوتسبوت بالأيام/الساعات (نظام الزري):
 * - سكربت on-login يسجّل تاريخ أول دخول في تعليق المستخدم (d_t قبل العلامة <) ويربط الماك عند الطلب.
 * - مجدول كل 10 دقائق يعطّل الكرت عند انتهاء مدته، ويضبط limit-uptime على المتبقي.
 * - مدة الصلاحية تُحفظ في حقل email بالشكل: 30@zari.net (أيام) أو 12h@zari.net (ساعات)، و bind بدل zari للربط بالماك.
 */
object ZariValidity {
    const val SCHEDULER = "Zari Net - hotspot card expiry"
    const val MARKER = "#ZariValidity"
    private const val SIGNATURE = ":local bind \"bind\";"

    /** سكربت on-login لبروفايلات الهوتسبوت (مع دعم كروت يوزر منجر v7 كاحتياط). */
    val ON_LOGIN =
        "#ZariValidity\n" +
        ":do {\n" +
        "  :local dot \".\";\n" +
        "  :local at \"@\";\n" +
        "  :local bind \"bind\";\n" +
        "  :local x \"_\";\n" +
        "\n" +
        "  :local oldemail [/ip hotspot user get [find where name=\$user] email];\n" +
        "  :local atmark [:find \$oldemail \$at];\n" +
        "  :local dotmark [:find \$oldemail \$dot];\n" +
        "\n" +
        "  :local m \"\";\n" +
        "  :local aid [/ip hotspot active find where user=\$user];\n" +
        "  :if ([:len \$aid] > 0) do={ :set m [/ip hotspot active get (\$aid->0) mac-address]; }\n" +
        "\n" +
        "  :if ([:typeof \$atmark] = \"num\" && [:typeof \$dotmark] = \"num\") do={\n" +
        "    :local binding [:pick \$oldemail \$atmark \$dotmark];\n" +
        "    :if (\$binding = (\$at . \$bind) && [:len \$m] > 0) do={\n" +
        "      /ip hotspot user set [find where name=\$user] mac-address=\$m;\n" +
        "    }\n" +
        "  }\n" +
        "\n" +
        "  :local d [/system clock get date];\n" +
        "  :local t [/system clock get time];\n" +
        "  :local comm [/ip hotspot user get [find where name=\$user disabled=no] comment];\n" +
        "  :local check [:find \$comm \"<\"];\n" +
        "\n" +
        "  :if ([:len \$comm] = 0 || \$check = 0) do={\n" +
        "    /ip hotspot user set [find where name=\$user] comment=(\$d . \$x . \$t . \$comm);\n" +
        "  }\n" +
        "\n" +
        "} on-error={\n" +
        "  :local x \"_\";\n" +
        "  :do {\n" +
        "    :local uid [/user-manager user find where name=\$user];\n" +
        "    :if ([:len \$uid] > 0) do={\n" +
        "      :local comm [/user-manager user get (\$uid->0) comment];\n" +
        "      :if ([:len \$comm] = 0) do={\n" +
        "        :local d [/system clock get date];\n" +
        "        :local t [/system clock get time];\n" +
        "        /user-manager user set (\$uid->0) comment=(\$d . \$x . \$t);\n" +
        "      }\n" +
        "    }\n" +
        "  } on-error={};\n" +
        "}"

    /** سكربت المجدول: يعطّل الكروت المنتهية ويحدّث الوقت المتبقي. */
    val SCHEDULER_SOURCE =
        ":local toHours do={\n" +
        "  :local s \$1;\n" +
        "  :local y 0; :local mo 0; :local dd 0; :local hh 0;\n" +
        "  :if ([:len \$s] < 10) do={ :return 0; }\n" +
        "  :if ([:pick \$s 4 5] = \"-\") do={\n" +
        "    :set y  [:tonum [:pick \$s 0 4]];\n" +
        "    :set mo [:tonum [:pick \$s 5 7]];\n" +
        "    :set dd [:tonum [:pick \$s 8 10]];\n" +
        "    :if ([:len \$s] >= 13) do={ :set hh [:tonum [:pick \$s 11 13]]; }\n" +
        "  } else={\n" +
        "    :local ma [:toarray \"jan,feb,mar,apr,may,jun,jul,aug,sep,oct,nov,dec\"];\n" +
        "    :local mi [:find \$ma [:pick \$s 0 3]];\n" +
        "    :if ([:typeof \$mi] != \"num\") do={ :return 0; }\n" +
        "    :set mo (\$mi + 1);\n" +
        "    :set dd [:tonum [:pick \$s 4 6]];\n" +
        "    :set y  [:tonum [:pick \$s 7 11]];\n" +
        "    :if ([:len \$s] >= 14) do={ :set hh [:tonum [:pick \$s 12 14]]; }\n" +
        "  }\n" +
        "  :local cum [:toarray \"0,31,59,90,120,151,181,212,243,273,304,334\"];\n" +
        "  :local dnum ((\$y * 365) + (\$y / 4) - (\$y / 100) + (\$y / 400) + (\$cum->(\$mo - 1)) + \$dd);\n" +
        "  :if (\$mo > 2) do={\n" +
        "    :if ((((\$y % 4) = 0) && ((\$y % 100) != 0)) || ((\$y % 400) = 0)) do={ :set dnum (\$dnum + 1); }\n" +
        "  }\n" +
        "  :return ((\$dnum * 24) + \$hh);\n" +
        "}\n" +
        "\n" +
        ":local todayHours [\$toHours ([/system clock get date] . \"_\" . [/system clock get time])];\n" +
        "\n" +
        ":foreach i in=[/ip hotspot user find where disabled=no] do={\n" +
        "  :do {\n" +
        "    :local comm [/ip hotspot user get \$i comment];\n" +
        "    :local mail [/ip hotspot user get \$i email];\n" +
        "    :if ([:len \$comm] > 0 && [:len \$mail] > 0) do={\n" +
        "      :local atmark  [:find \$mail \"@\"];\n" +
        "      :local dotmark [:find \$mail \".\"];\n" +
        "      :if ([:typeof \$atmark] = \"num\" && [:typeof \$dotmark] = \"num\") do={\n" +
        "        :local validity [:pick \$mail 0 \$atmark];\n" +
        "        :local inHours false;\n" +
        "        :local hmark [:find \$validity \"h\"];\n" +
        "        :if ([:typeof \$hmark] = \"num\") do={\n" +
        "          :set inHours true;\n" +
        "          :set validity [:pick \$validity 0 \$hmark];\n" +
        "        }\n" +
        "        :local vnum [:tonum \$validity];\n" +
        "        :if ([:typeof \$vnum] = \"num\") do={\n" +
        "        :if (\$vnum > 0) do={\n" +
        "          :local startH [\$toHours \$comm];\n" +
        "          :if (\$startH > 0) do={\n" +
        "            :local totalValidity \$vnum;\n" +
        "            :if (!\$inHours) do={ :set totalValidity (\$vnum * 24); }\n" +
        "            :local name [/ip hotspot user get \$i name];\n" +
        "            :if ((\$startH + \$totalValidity) <= \$todayHours) do={\n" +
        "              /ip hotspot user disable \$i;\n" +
        "              /ip hotspot active remove [find where user=\$name];\n" +
        "            } else={\n" +
        "              :local usedSec [:tonum [/ip hotspot user get \$i uptime]];\n" +
        "              :if ([:typeof \$usedSec] != \"num\") do={ :set usedSec 0; }\n" +
        "              :local leftH ((\$startH + \$totalValidity) - \$todayHours);\n" +
        "              /ip hotspot user set \$i limit-uptime=[:totime ((\$leftH * 3600) + \$usedSec)];\n" +
        "            }\n" +
        "          }\n" +
        "        }\n" +
        "        }\n" +
        "      }\n" +
        "    }\n" +
        "  } on-error={};\n" +
        "}"

    /** نص التعليق بعد إزالة تاريخ أول دخول. */
    fun body(comment: String): String = if (comment.contains('<')) comment.substringAfter('<') else comment

    /** تاريخ أول دخول المسجّل في التعليق (قبل العلامة <)، أو null إن لم يُستخدم الكرت بعد. */
    fun firstLogin(comment: String, now: Long = System.currentTimeMillis()): Long? {
        if (!comment.contains('<') || comment.startsWith("<")) return null
        return RosTime.parse(comment.substringBefore('<').replace('_', ' '), now)
    }

    /** مدة الصلاحية بالساعات من حقل email (30@zari.net = 30 يوماً، 12h@zari.net = 12 ساعة). */
    fun validityHours(email: String): Long {
        val v = email.substringBefore('@', "")
        if (v.isBlank()) return 0
        return if (v.endsWith("h")) v.dropLast(1).toLongOrNull() ?: 0
        else (v.toLongOrNull() ?: 0) * 24
    }

    fun isBind(email: String): Boolean = email.substringAfter('@', "").startsWith("bind")

    /** يبني قيمة email من مدة مثل "12h" أو "30d" أو "2w". */
    fun emailFor(validity: String, bind: Boolean): String {
        val n = validity.dropLast(1).toLongOrNull() ?: 0L
        val v = when (validity.lastOrNull()) {
            'h' -> "${n}h"
            'd' -> "$n"
            'w' -> "${n * 7}"
            else -> "0"
        }
        return "$v@${if (bind) "bind" else "zari"}.net"
    }

    /** المتبقي من صلاحية الكرت بالمللي ثانية (null إن لم يبدأ أو بلا مدة). */
    fun remainingMs(comment: String, email: String, now: Long = System.currentTimeMillis()): Long? {
        val first = firstLogin(comment, now) ?: return null
        val hours = validityHours(email).takeIf { it > 0 } ?: return null
        return (first + hours * 3_600_000 - now).coerceAtLeast(0)
    }

    private fun hasScript(onLogin: String) = onLogin.contains(MARKER) || onLogin.contains(SIGNATURE)

    /** مفعّل = كل بروفايلات الهوتسبوت تحمل السكربت والمجدول موجود. */
    suspend fun isEnabled(): Boolean {
        val api = Session.api()
        val profiles = api.print("/ip/hotspot/user/profile", listOf("name", "on-login"))
        if (profiles.isEmpty()) return false
        return api.count("/system/scheduler", listOf("?name=$SCHEDULER")) > 0 &&
            profiles.all { hasScript(it["on-login"].orEmpty()) }
    }

    /** تثبيت السكربت في كل بروفايل (مع الإبقاء على سكربت المستخدم) وإضافة المجدول إن لم يوجد. */
    suspend fun install() {
        val api = Session.api()
        for (p in api.print("/ip/hotspot/user/profile", listOf(".id", "name", "on-login"))) {
            val cur = p["on-login"].orEmpty()
            if (hasScript(cur)) continue
            // إزالة سكربت MikroAdmin القديم إن وُجد
            val rest = cur.replace(Regex("#MikroAdmin[\\s\\S]*?#/MikroAdmin"), "").trim()
            val script = if (rest.isBlank()) ON_LOGIN else rest + "\r\n" + ON_LOGIN
            api.exec("/ip/hotspot/user/profile/set", mapOf(".id" to p[".id"]!!, "on-login" to script))
        }
        if (api.count("/system/scheduler", listOf("?name=$SCHEDULER")) == 0) {
            api.exec(
                "/system/scheduler/add", linkedMapOf(
                    "name" to SCHEDULER,
                    "interval" to "00:10:00",
                    "on-event" to SCHEDULER_SOURCE,
                    "policy" to "ftp,reboot,read,write,policy,test,password,sniff,sensitive,romon"
                )
            )
        }
    }
}
