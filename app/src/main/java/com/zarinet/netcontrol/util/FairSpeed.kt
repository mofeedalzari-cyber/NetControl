package com.zarinet.netcontrol.util

import com.zarinet.netcontrol.api.RouterOsClient

/**
 * تقسيم السرعة حسب المتصلين (Dynamic Fair Share) عبر PCQ.
 *
 * ينشئ:
 *  - نوع كيو PCQ للتنزيل  (pcq-classifier=dst-address, pcq-rate=0)
 *  - نوع كيو PCQ للرفع    (pcq-classifier=src-address, pcq-rate=0)
 *  - Simple Queue واحدة للنطاق بحد السرعة الإجمالي ومربوطة بالنوعين (queue=رفع/تنزيل).
 *
 * pcq-rate=0 يجعل الراوتر يقسم الحد الإجمالي بالتساوي على العناوين النشطة فقط تلقائياً:
 * 300M و3 أجهزة نشطة = 100M لكل جهاز، و6 أجهزة = 50M. لا كيو لكل مستخدم ولا عدّ للاتصالات.
 *
 * كل ما ينشئه التطبيق يحمل الوسم [TAG] (في التعليق للـ Simple Queue وفي الاسم لأنواع الكيو
 * لأن /queue/type لا يملك تعليقاً في RouterOS 6). الأوامر نفسها تعمل على RouterOS 6 و 7.
 * لا يُلمس أي عنصر لا يحمل الوسم.
 */
object FairSpeed {
    const val TAG = "mikrotik-shamel:fairspeed"
    const val QUEUE_NAME = TAG
    const val TYPE_DOWN = "$TAG-down"
    const val TYPE_UP = "$TAG-up"
    private const val SIMPLE = "/queue/simple"
    private const val TYPES = "/queue/type"
    const val MAX_MBPS = 100_000.0

    // ───────────────────────── التحقق من المدخلات ─────────────────────────

    data class Plan(
        val target: String, val downBps: Long, val upBps: Long,
        /** pcq-rate لكل جهاز (0 = تقسيم متساوٍ تلقائي) */
        val downRate: Long = 0, val upRate: Long = 0,
        /** pcq-classifier لكل نوع: dst-address أو src-address */
        val downClassifier: String = "dst-address", val upClassifier: String = "src-address",
    ) {
        /** صيغة max-limit في Simple Queue: رفع/تنزيل. */
        val maxLimit get() = "$upBps/$downBps"
    }

    data class Validation(val plan: Plan?, val errors: Map<String, String>) {
        val ok get() = plan != null
    }

    /** يقبل 192.168.1.0/24 (أو عنواناً داخل النطاق فيُصحَّح إلى عنوان الشبكة). */
    fun normalizeCidr(input: String): String? {
        val t = input.trim()
        val m = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d{1,2})$""").matchEntire(t) ?: return null
        val o = (1..4).map { m.groupValues[it].toInt() }
        if (o.any { it > 255 }) return null
        val prefix = m.groupValues[5].toInt()
        if (prefix !in 1..32) return null
        val ip = o.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val net = ip and mask
        return "${net shr 24 and 255}.${net shr 16 and 255}.${net shr 8 and 255}.${net and 255}/$prefix"
    }

    /** Mbps (يقبل الكسور مثل 1.5) ← bps. null إذا كانت القيمة غير صالحة. */
    fun mbpsToBps(input: String): Long? {
        val v = input.trim().replace(',', '.').replace('٫', '.').let(::latinDigits).toDoubleOrNull() ?: return null
        if (v.isNaN() || v <= 0.0 || v > MAX_MBPS) return null
        val bps = Math.round(v * 1_000_000)
        return if (bps < 1000) null else bps
    }

    val CLASSIFIERS = listOf("dst-address", "src-address")

    /** سرعة اختيارية لكل جهاز (فارغ أو 0 = بدون حد لكل جهاز). null = قيمة غير صالحة. */
    fun optionalRate(input: String): Long? {
        val t = latinDigits(input.trim())
        if (t.isEmpty() || t.toDoubleOrNull() == 0.0) return 0L
        return mbpsToBps(t)
    }

    fun validate(network: String, downMbps: String, upMbps: String,
                 downRateMbps: String = "", upRateMbps: String = "",
                 downClassifier: String = "dst-address", upClassifier: String = "src-address"): Validation {
        val errors = LinkedHashMap<String, String>()
        val target = normalizeCidr(latinDigits(network))
        if (network.isBlank()) errors["network"] = "أدخل نطاق الشبكة، مثل 192.168.1.0/24"
        else if (target == null) errors["network"] = "نطاق غير صحيح — الصيغة: 192.168.1.0/24 (البادئة من 1 إلى 32)"
        val down = mbpsToBps(downMbps)
        if (down == null) errors["down"] = "أدخل سرعة تنزيل صحيحة بالميغابت (أكبر من 0 وحتى ${MAX_MBPS.toLong()})"
        val up = mbpsToBps(upMbps)
        if (up == null) errors["up"] = "أدخل سرعة رفع صحيحة بالميغابت (أكبر من 0 وحتى ${MAX_MBPS.toLong()})"
        val dr = optionalRate(downRateMbps)
        if (dr == null) errors["downRate"] = "Rate غير صحيح (اتركه فارغاً أو 0 للتقسيم التلقائي)"
        else if (down != null && dr > down) errors["downRate"] = "Rate أكبر من الإجمالي"
        val ur = optionalRate(upRateMbps)
        if (ur == null) errors["upRate"] = "Rate غير صحيح (اتركه فارغاً أو 0 للتقسيم التلقائي)"
        else if (up != null && ur > up) errors["upRate"] = "Rate أكبر من الإجمالي"
        if (downClassifier !in CLASSIFIERS) errors["downCls"] = "اختر Src أو Dst Address"
        if (upClassifier !in CLASSIFIERS) errors["upCls"] = "اختر Src أو Dst Address"
        return Validation(if (errors.isEmpty()) Plan(target!!, down!!, up!!, dr!!, ur!!, downClassifier, upClassifier) else null, errors)
    }

    /** نصيب كل جهاز عند N متصلين (للعرض فقط — التقسيم الفعلي يقوم به الراوتر). */
    fun perClient(totalBps: Long, active: Int, rate: Long = 0): Long {
        val share = if (active <= 0) totalBps else totalBps / active
        return if (rate > 0) minOf(share, rate) else share
    }

    fun bpsToMbpsText(bps: Long): String {
        val m = bps / 1_000_000.0
        return if (m == Math.floor(m)) m.toLong().toString() else String.format(java.util.Locale.US, "%.2f", m).trimEnd('0').trimEnd('.')
    }

    private fun latinDigits(s: String) = s.map { c ->
        when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); else -> c }
    }.joinToString("")

    // ───────────────────────── الحالة الحالية ─────────────────────────

    data class Item(val path: String, val id: String, val name: String)

    data class Status(
        val queue: Map<String, String>?,           // Simple Queue الخاصة بالميزة (الأولى إن وُجد تكرار)
        val duplicates: Int,                        // عدد النسخ المكررة الموسومة
        val typeDown: Map<String, String>?,
        val typeUp: Map<String, String>?,
        val before: Int,                            // عدد الكيوز التي تسبقها في الترتيب (أول تطابق يفوز)
        val fasttrack: Boolean,                     // FastTrack مفعّل ⇒ الحزم تتجاوز الكيوز
        val rosVersion: String,
    ) {
        val active get() = queue != null
        val complete get() = queue != null && typeDown != null && typeUp != null
        val target get() = queue?.get("target").orEmpty().substringBefore(',')
        val downBps get() = ActiveInfo.parseRate(queue?.get("max-limit").orEmpty().substringAfter('/', ""))
        val upBps get() = ActiveInfo.parseRate(queue?.get("max-limit").orEmpty().substringBefore('/'))
        val disabled get() = queue?.get("disabled") == "true"
        val downRate get() = ActiveInfo.parseRate(typeDown?.get("pcq-rate").orEmpty())
        val upRate get() = ActiveInfo.parseRate(typeUp?.get("pcq-rate").orEmpty())
        val downClassifier get() = typeDown?.get("pcq-classifier")?.substringBefore(',')?.ifBlank { null } ?: "dst-address"
        val upClassifier get() = typeUp?.get("pcq-classifier")?.substringBefore(',')?.ifBlank { null } ?: "src-address"
    }

    /** كل العناصر التي تحمل الوسم (للحذف والتحقق). */
    suspend fun tagged(api: RouterOsClient): List<Item> {
        val out = ArrayList<Item>()
        api.print(SIMPLE, listOf(".id", "name", "comment")).filter { isOurs(it) }
            .forEach { out += Item(SIMPLE, it[".id"].orEmpty(), it["name"].orEmpty()) }
        runCatching { api.print("/queue/tree", listOf(".id", "name", "comment")) }.getOrDefault(emptyList())
            .filter { it["comment"] == TAG }.forEach { out += Item("/queue/tree", it[".id"].orEmpty(), it["name"].orEmpty()) }
        api.print(TYPES, listOf(".id", "name")).filter { it["name"] == TYPE_DOWN || it["name"] == TYPE_UP }
            .forEach { out += Item(TYPES, it[".id"].orEmpty(), it["name"].orEmpty()) }
        return out
    }

    private fun isOurs(r: Map<String, String>) = r["comment"] == TAG

    suspend fun status(api: RouterOsClient): Status {
        val simple = api.print(SIMPLE, listOf(".id", "name", "target", "max-limit", "queue", "disabled", "dynamic", "comment"))
        val ours = simple.filter { isOurs(it) }
        val first = ours.firstOrNull()
        val idx = first?.let { f -> simple.indexOfFirst { it[".id"] == f[".id"] } } ?: -1
        val before = if (idx > 0) simple.take(idx).count { it["disabled"] != "true" } else 0
        val types = api.print(TYPES, listOf(".id", "name", "kind", "pcq-rate", "pcq-classifier"))
        val ft = runCatching {
            api.print("/ip/firewall/filter", listOf(".id", "action", "disabled"), listOf("?action=fasttrack-connection"))
        }.getOrDefault(emptyList()).any { it["disabled"] != "true" }
        val ver = runCatching { api.print("/system/resource", listOf("version")).firstOrNull()?.get("version") }.getOrNull().orEmpty()
        return Status(first, (ours.size - 1).coerceAtLeast(0),
            types.firstOrNull { it["name"] == TYPE_DOWN }, types.firstOrNull { it["name"] == TYPE_UP }, before, ft, ver)
    }

    // ───────────────────────── التفعيل / التحديث ─────────────────────────

    class FairSpeedException(message: String) : Exception(message)

    /** إجراء تراجع: حذف ما أُنشئ أو إرجاع القيم القديمة لما عُدّل. */
    private sealed class Undo {
        abstract val label: String
        class Remove(val path: String, val id: String, override val label: String) : Undo()
        class Restore(val path: String, val id: String, val old: Map<String, String>, override val label: String) : Undo()
    }

    data class ApplyResult(val created: List<String>, val updated: List<String>, val removedDuplicates: Int)

    /**
     * ينشئ الإعدادات أو يحدّثها إن كانت موجودة (بدون تكرار).
     * عند فشل أي خطوة يتراجع عن كل ما سبق ثم يرمي [FairSpeedException] برسالة واضحة.
     */
    suspend fun apply(api: RouterOsClient, plan: Plan): ApplyResult {
        val undo = ArrayList<Undo>()
        val created = ArrayList<String>(); val updated = ArrayList<String>()
        var step = "قراءة الإعدادات الحالية"
        try {
            val simple = api.print(SIMPLE, listOf(".id", "name", "target", "max-limit", "queue", "disabled", "comment"))
            val ours = simple.filter { isOurs(it) }
            // اسم محجوز لكيو لا يخص الميزة ⇒ لا نعدّلها ولا ننشئ بنفس الاسم
            if (ours.isEmpty() && simple.any { it["name"] == QUEUE_NAME })
                throw FairSpeedException("يوجد Simple Queue باسم «$QUEUE_NAME» لا تحمل وسم الميزة — لن يتم تعديلها. غيّر اسمها أولاً.")
            val types = api.print(TYPES, listOf(".id", "name", "kind", "pcq-rate", "pcq-classifier"))

            // 1) أنواع PCQ
            for ((name, spec, label) in listOf(
                Triple(TYPE_DOWN, plan.downClassifier to plan.downRate, "PCQ التنزيل"),
                Triple(TYPE_UP, plan.upClassifier to plan.upRate, "PCQ الرفع"))) {
                step = label
                val want = linkedMapOf("kind" to "pcq", "pcq-rate" to spec.second.toString(), "pcq-classifier" to spec.first)
                val cur = types.firstOrNull { it["name"] == name }
                if (cur == null) {
                    val id = api.exec("$TYPES/add", linkedMapOf("name" to name) + want).done["ret"].orEmpty()
                    undo += Undo.Remove(TYPES, id.ifBlank { name }, label); created += label
                } else if (want.any { (k, v) -> !same(k, cur[k], v) }) {
                    val id = cur[".id"].orEmpty()
                    api.exec("$TYPES/set", linkedMapOf(".id" to id) + want)
                    undo += Undo.Restore(TYPES, id, want.keys.associateWith { cur[it].orEmpty() }.filterValues { it.isNotEmpty() }, label)
                    updated += label
                }
            }

            // 2) Simple Queue الرئيسية
            step = "Simple Queue الرئيسية"
            val want = linkedMapOf(
                "target" to plan.target,
                "max-limit" to plan.maxLimit,
                "queue" to "$TYPE_UP/$TYPE_DOWN",   // رفع/تنزيل
                "disabled" to "no",
            )
            val cur = ours.firstOrNull()
            if (cur == null) {
                val id = api.exec("$SIMPLE/add", linkedMapOf("name" to QUEUE_NAME) + want + ("comment" to TAG)).done["ret"].orEmpty()
                undo += Undo.Remove(SIMPLE, id.ifBlank { QUEUE_NAME }, "Simple Queue"); created += "Simple Queue"
            } else {
                val id = cur[".id"].orEmpty()
                api.exec("$SIMPLE/set", linkedMapOf(".id" to id) + want)
                undo += Undo.Restore(SIMPLE, id, want.keys.associateWith { k ->
                    when (k) { "disabled" -> if (cur["disabled"] == "true") "yes" else "no"; else -> cur[k].orEmpty() }
                }.filterValues { it.isNotEmpty() }, "Simple Queue")
                updated += "Simple Queue"
            }

            // 3) إزالة النسخ المكررة الموسومة (منع التكرار) — بعد نجاح كل ما سبق
            step = "إزالة النسخ المكررة"
            val dups = ours.drop(1)
            dups.forEach { api.exec("$SIMPLE/remove", mapOf(".id" to it[".id"].orEmpty())) }

            // 4) وضع كيو التقسيم في أعلى Simple Queues (أول تطابق يفوز) — لا يُعدّل أي كيو أخرى
            step = "النقل إلى أعلى Simple Queues"
            moveToTop(api)
            return ApplyResult(created, updated, dups.size)
        } catch (e: Exception) {
            val reason = (e.message ?: e.javaClass.simpleName)
            if (undo.isEmpty()) throw if (e is FairSpeedException) e else FairSpeedException("فشل عند «$step»: $reason")
            val failed = rollback(api, undo)
            throw FairSpeedException(
                "فشل عند «$step»: $reason\n" +
                    if (failed.isEmpty()) "تم التراجع عن كل التغييرات التي أُجريت (${undo.size})."
                    else "تعذّر التراجع عن: ${failed.joinToString("، ")} — راجعها يدوياً."
            )
        }
    }

    /**
     * ينقل كيو الميزة لتكون الأولى في Simple Queues (move numbers=ours destination=first).
     * يجرّب قبل أول كيو، وإن رفض الراوتر (كيو ديناميكية) فقبل أول كيو ثابتة.
     */
    suspend fun moveToTop(api: RouterOsClient) {
        val all = api.print(SIMPLE, listOf(".id", "dynamic", "comment"))
        val ours = all.firstOrNull { isOurs(it) }?.get(".id") ?: return
        val targets = listOfNotNull(all.firstOrNull()?.get(".id"),
            all.firstOrNull { it["dynamic"] != "true" }?.get(".id")).distinct()
        for (t in targets) {
            if (t == ours) return   // في الأعلى أصلاً
            val ok = runCatching { api.exec("$SIMPLE/move", linkedMapOf("numbers" to ours, "destination" to t)) }.isSuccess
            if (ok) return
        }
    }

    private fun same(key: String, cur: String?, want: String) = when (key) {
        "pcq-rate" -> ActiveInfo.parseRate(cur.orEmpty()) == ActiveInfo.parseRate(want)
        else -> cur == want
    }

    /** يعيد أسماء العناصر التي فشل التراجع عنها. */
    private suspend fun rollback(api: RouterOsClient, undo: List<Undo>): List<String> {
        val failed = ArrayList<String>()
        for (u in undo.asReversed()) {
            runCatching {
                when (u) {
                    is Undo.Remove -> api.exec("${u.path}/remove", mapOf(".id" to u.id))
                    is Undo.Restore -> if (u.old.isNotEmpty()) api.exec("${u.path}/set", linkedMapOf(".id" to u.id) + u.old)
                }
            }.onFailure { failed += u.label }
        }
        return failed
    }

    // ───────────────────────── التعطيل والحذف ─────────────────────────

    data class RemoveResult(val removed: List<String>, val errors: List<String>, val remaining: List<Item>) {
        val success get() = errors.isEmpty() && remaining.isEmpty()
    }

    /**
     * يحذف كل ما يحمل الوسم فقط: الكيوز أولاً (لأن أنواع PCQ مستخدمة فيها) ثم أنواع PCQ،
     * ثم يعيد القراءة ليتأكد أن لا شيء بقي.
     */
    suspend fun remove(api: RouterOsClient): RemoveResult {
        val items = tagged(api)
        val removed = ArrayList<String>(); val errors = ArrayList<String>()
        val order = items.sortedBy { if (it.path == TYPES) 1 else 0 }
        for (it in order) {
            try {
                api.exec("${it.path}/remove", mapOf(".id" to it.id))
                removed += label(it)
            } catch (e: Exception) {
                errors += "${label(it)}: ${e.message ?: "خطأ"}"
            }
        }
        api.invalidateCache()
        val remaining = runCatching { tagged(api) }.getOrElse { e ->
            errors += "تعذّر التحقق بعد الحذف: ${e.message ?: "خطأ"}"; emptyList()
        }
        return RemoveResult(removed, errors, remaining)
    }

    fun label(i: Item) = when {
        i.path == TYPES && i.name == TYPE_DOWN -> "PCQ التنزيل"
        i.path == TYPES && i.name == TYPE_UP -> "PCQ الرفع"
        i.path == SIMPLE -> "Simple Queue (${i.name})"
        else -> "${i.path} (${i.name})"
    }
}
