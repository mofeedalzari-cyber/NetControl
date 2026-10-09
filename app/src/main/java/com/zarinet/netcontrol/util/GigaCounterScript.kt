package com.zarinet.netcontrol.util

/**
 * سكربت عدّاد الجيجات اليومي/الكلي لكل واجهة (يحفظ العدادات في /ip proxy direct)،
 * ويعطّل مشترك PPPoE عند تجاوز الحد المحدد في المسار (path=/date_limit).
 * يُشغَّل عبر مجدول باسم NAME.
 */
object GigaCounterScript {
    const val NAME = "gigaCounter"

    val SOURCE =
        "{;:local currentDate [/system clock get date];:local logPrefix \"TrafficCounter:\";" +
        ":local schedulerComment [/system scheduler get [find name=\"gigaCounter\"] comment];" +
        ":local storedDate \$schedulerComment;:if (\$storedDate != \$currentDate) do={;" +
        "/system scheduler set [find name=\"gigaCounter\"] comment=\$currentDate;};" +
        ":log info \"\$logPrefix Starting processing for \$currentDate\";" +
        ":foreach i in=[/interface find] do={;:local ifname [/interface get \$i name];" +
        ":local rxCurrent [/interface get \$i rx-byte];:local txCurrent [/interface get \$i tx-byte];" +
        "/interface reset-counters \$ifname;:local entryExists false;:local entryId \"\";:local todayRx 0;" +
        ":local todayTx 0;:local allDaysRx 0;:local allDaysTx 0;:local todayRuns 1;:local creationDate \"\";" +
        ":local dateChanged false;:local userLimit 0;:foreach entry in=[/ip proxy direct find] do={;" +
        ":if ([/ip proxy direct get \$entry comment] = \$ifname) do={;:set entryExists true;" +
        ":set entryId \$entry;:local pathValue [/ip proxy direct get \$entry path];" +
        ":set creationDate [:pick \$pathValue 1 [:len \$pathValue]];" +
        ":local secondSlashPos [:find \$pathValue \"_\" 1];:if (\$secondSlashPos > 0) do={;" +
        ":set creationDate [:pick \$pathValue 1 \$secondSlashPos];" +
        ":set userLimit [:tonum [:pick \$pathValue (\$secondSlashPos + 1) [:len \$pathValue]]];};" +
        ":local dstHost [/ip proxy direct get \$entry dst-host];" +
        ":local methodStr [/ip proxy direct get \$entry method];" +
        ":set todayRx [:tonum [:pick \$dstHost ([:find \$dstHost \"rx:\"] + 3) ([:find \$dstHost \" tx:\"])]];" +
        ":set todayTx [:tonum [:pick \$dstHost ([:find \$dstHost \"tx:\"] + 3) [:len \$dstHost]]];" +
        ":set allDaysRx [:tonum [:pick \$methodStr ([:find \$methodStr \"rx:\"] + 3) ([:find \$methodStr \" tx:\"])]];" +
        ":set allDaysTx [:tonum [:pick \$methodStr ([:find \$methodStr \"tx:\"] + 3) [:len \$methodStr]]];" +
        ":set todayRuns [/ip proxy direct get \$entry dst-port];:if (\$storedDate != \$currentDate) do={;" +
        ":set dateChanged true;:local totalRx (\$allDaysRx + \$todayRx + \$rxCurrent);" +
        ":local totalTx (\$allDaysTx + \$todayTx + \$txCurrent);" +
        "/ip proxy direct set \$entry  dst-host=\"rx:0 tx:0\" method=\"rx:\$totalRx tx:\$totalTx\" dst-port=1  comment=\$ifname;" +
        ":log info \"\$logPrefix \$ifname - Date changed. Reset daily, Updated total: RX:\$totalRx TX:\$totalTx\";" +
        "};};};:if (!\$dateChanged) do={;:if (\$entryExists) do={;:local newRx (\$todayRx + \$rxCurrent);" +
        ":local newTx (\$todayTx + \$txCurrent);:local newRuns (\$todayRuns + 1);" +
        "/ip proxy direct set \$entryId  dst-host=\"rx:\$newRx tx:\$newTx\" method=\"rx:\$allDaysRx tx:\$allDaysTx\" dst-port=\$newRuns  comment=\$ifname;" +
        ":log info \"\$logPrefix \$ifname - Updated daily: RX:\$newRx TX:\$newTx Runs:\$newRuns\";" +
        ":if (\$userLimit > 0) do={;:local totalUsage (\$allDaysRx + \$allDaysTx + \$newRx + \$newTx);" +
        ":if (\$totalUsage > \$userLimit) do={;:local username \"\";" +
        ":if ([:find \$ifname \"<pppoe-\"] = 0) do={;:set username [:pick \$ifname 7 ([:len \$ifname] - 1)];" +
        "} else={;:set username \$ifname;};:do {;/ppp secret disable [find name=\$username];" +
        ":log warning \"\$logPrefix \$ifname - LIMIT EXCEEDED! Usage: \$totalUsage, Limit: \$userLimit - PPP user \$username disabled\";" +
        ":do {;/ppp active remove [find name=\$username];" +
        ":log info \"\$logPrefix \$ifname - Active PPP connection for \$username removed\";} on-error={;" +
        ":log info \"\$logPrefix \$ifname - No active PPP connection found for \$username or already disconnected\";" +
        "};} on-error={;" +
        ":log error \"\$logPrefix \$ifname - Failed to disable PPP user \$username (user may not exist in PPP secrets)\";" +
        "};} else={;:local remaining (\$userLimit - \$totalUsage);" +
        ":log info \"\$logPrefix \$ifname - Usage: \$totalUsage/\$userLimit, Remaining: \$remaining\";};};" +
        "} else={;" +
        "/ip proxy direct add  dst-host=\"rx:\$rxCurrent tx:\$txCurrent\" method=\"rx:0 tx:0\" path=\"/\$currentDate\" dst-port=1  comment=\$ifname;" +
        ":log info \"\$logPrefix \$ifname - New entry created\";};};};" +
        ":log info \"\$logPrefix Processing completed for \$currentDate\";" +
        ":log info \"======================================\";};"
}
