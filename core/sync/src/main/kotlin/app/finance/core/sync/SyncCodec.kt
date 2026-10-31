package app.finance.core.sync

import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Category
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import app.finance.core.model.RevisionSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Файл на Диске не читается: битый JSON, неизвестный тип записи, нарушенные поля. */
class SyncFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Формат файлов хранилища (docs/SYNC-YANDEX.md §4).
 *
 * Пакет — JSON Lines: первая строка — заголовок, дальше по строке на изменение. Построчный
 * формат читается и проверяется глазами в веб-интерфейсе Диска, а повреждение одной строки
 * видно сразу. Деньги — в копейках (`*_minor`), периоды — числом `ГГГГММ`, как в базе.
 */
object SyncCodec {

    /** Версия формата файлов. Растёт только при несовместимых изменениях. */
    const val FORMAT = 1

    private val json = Json { prettyPrint = false }

    // ── Пакет ─────────────────────────────────────────────────────────────────────

    fun encodePack(pack: SyncPack): String = buildString {
        append(
            buildJsonObject {
                put("type", "header")
                put("format", pack.format)
                put("deviceId", pack.deviceId)
                put("seq", pack.seq)
                put("schemaVersion", pack.schemaVersion)
                put("appVersion", pack.appVersion)
                put("createdAtUtc", pack.createdAtUtc)
            }.toString(),
        )
        append('\n')
        pack.changes.forEach {
            append(encodeChange(it).toString())
            append('\n')
        }
    }

    fun decodePack(text: String): SyncPack = wrap("пакет") {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) throw SyncFormatException("Пустой файл пакета")
        val header = parse(lines.first())
        if (header.str("type") != "header") throw SyncFormatException("Нет заголовка пакета")
        SyncPack(
            format = header.int("format"),
            deviceId = header.str("deviceId"),
            seq = header.long("seq"),
            schemaVersion = header.long("schemaVersion"),
            appVersion = header.str("appVersion"),
            createdAtUtc = header.long("createdAtUtc"),
            changes = lines.drop(1).map { decodeChange(parse(it)) },
        )
    }

    // ── Служебные файлы ───────────────────────────────────────────────────────────

    fun encodeStore(info: StoreInfo): String = buildJsonObject {
        put("format", info.format)
        put("storeId", info.storeId)
        put("createdAtUtc", info.createdAtUtc)
    }.toString()

    fun decodeStore(text: String): StoreInfo = wrap("sync.json") {
        val o = parse(text)
        StoreInfo(format = o.int("format"), storeId = o.str("storeId"), createdAtUtc = o.long("createdAtUtc"))
    }

    fun encodeDevice(info: DeviceInfo): String = buildJsonObject {
        put("deviceId", info.deviceId)
        put("name", info.name)
        put("os", info.os)
        put("appVersion", info.appVersion)
        put("schemaVersion", info.schemaVersion)
        put("lastSeq", info.lastSeq)
        put("updatedAtUtc", info.updatedAtUtc)
    }.toString()

    fun decodeDevice(text: String): DeviceInfo = wrap("device.json") {
        val o = parse(text)
        DeviceInfo(
            deviceId = o.str("deviceId"),
            name = o.str("name"),
            os = o.str("os"),
            appVersion = o.str("appVersion"),
            schemaVersion = o.long("schemaVersion"),
            lastSeq = o.long("lastSeq"),
            updatedAtUtc = o.long("updatedAtUtc"),
        )
    }

    // ── Изменения ─────────────────────────────────────────────────────────────────

    private fun encodeChange(change: SyncChange): JsonObject = when (change) {
        is SyncChange.CategoryChange -> buildJsonObject {
            val c = change.category
            put("type", SyncEntity.CATEGORY)
            put("id", c.id)
            put("section", c.section.name)
            put("name", c.name)
            put("color", c.color)
            put("icon", c.icon)
            put("sortOrder", c.sortOrder)
            put("balancePolicy", c.balancePolicy.name)
            put("allowsNegativeBalance", c.allowsNegativeBalance)
            put("isArchived", c.isArchived)
            putMeta(c.createdAtUtc, c.updatedAtUtc, c.rowVersion, c.deletedAtUtc)
        }

        is SyncChange.AccountChange -> buildJsonObject {
            val a = change.account
            put("type", SyncEntity.ACCOUNT)
            put("id", a.id)
            put("categoryId", a.categoryId)
            put("name", a.name)
            put("currency", a.currency)
            put("openedPeriod", a.openedPeriod.key)
            put("closedPeriod", a.closedPeriod?.key)
            put("annualRateBp", a.annualRateBp)
            put("termMonths", a.termMonths)
            put("note", a.note)
            putMeta(a.createdAtUtc, a.updatedAtUtc, a.rowVersion, a.deletedAtUtc)
        }

        is SyncChange.SnapshotChange -> buildJsonObject {
            val s = change.snapshot
            put("type", SyncEntity.SNAPSHOT)
            put("id", s.id)
            put("accountId", s.accountId)
            put("period", s.period.key)
            put("balanceMinor", s.balance.minor)
            put("currency", s.balance.currency)
            put("contributionMinor", s.contribution?.minor)
            put("note", s.note)
            putMeta(s.createdAtUtc, s.updatedAtUtc, s.rowVersion, s.deletedAtUtc)
        }

        is SyncChange.RevisionChange -> buildJsonObject {
            val r = change.revision
            put("type", SyncEntity.REVISION)
            put("id", r.id)
            put("cellId", r.cellId)
            put("section", change.section.name)
            put("categoryId", change.categoryId)
            put("period", change.period.key)
            put("op", r.op.name)
            put("currency", r.amountAfter.currency)
            put("deltaMinor", r.delta?.minor)
            put("amountBeforeMinor", r.amountBefore?.minor)
            put("amountAfterMinor", r.amountAfter.minor)
            put("commentBefore", r.commentBefore)
            put("commentAfter", r.commentAfter)
            put("source", r.source.name)
            put("sourcePeriod", r.sourcePeriod?.key)
            put("idempotencyKey", r.idempotencyKey)
            put("createdAtUtc", r.createdAtUtc)
        }
    }

    private fun decodeChange(o: JsonObject): SyncChange = when (val type = o.str("type")) {
        SyncEntity.CATEGORY -> SyncChange.CategoryChange(
            Category(
                id = o.str("id"),
                section = CategorySection.valueOf(o.str("section")),
                name = o.str("name"),
                color = o.str("color"),
                icon = o.strOrNull("icon"),
                sortOrder = o.int("sortOrder"),
                balancePolicy = AssetBalancePolicy.valueOf(o.str("balancePolicy")),
                allowsNegativeBalance = o.bool("allowsNegativeBalance"),
                isArchived = o.bool("isArchived"),
                createdAtUtc = o.long("createdAtUtc"),
                updatedAtUtc = o.long("updatedAtUtc"),
                rowVersion = o.int("rowVersion"),
                deletedAtUtc = o.longOrNull("deletedAtUtc"),
            ),
        )

        SyncEntity.ACCOUNT -> SyncChange.AccountChange(
            AssetAccount(
                id = o.str("id"),
                categoryId = o.str("categoryId"),
                name = o.str("name"),
                currency = o.str("currency"),
                openedPeriod = Period.ofKey(o.int("openedPeriod")),
                closedPeriod = o.intOrNull("closedPeriod")?.let(Period::ofKey),
                annualRateBp = o.intOrNull("annualRateBp"),
                termMonths = o.intOrNull("termMonths"),
                note = o.strOrNull("note"),
                createdAtUtc = o.long("createdAtUtc"),
                updatedAtUtc = o.long("updatedAtUtc"),
                rowVersion = o.int("rowVersion"),
                deletedAtUtc = o.longOrNull("deletedAtUtc"),
            ),
        )

        SyncEntity.SNAPSHOT -> {
            val currency = o.str("currency")
            SyncChange.SnapshotChange(
                AssetSnapshot(
                    id = o.str("id"),
                    accountId = o.str("accountId"),
                    period = Period.ofKey(o.int("period")),
                    balance = Money(o.long("balanceMinor"), currency),
                    contribution = o.longOrNull("contributionMinor")?.let { Money(it, currency) },
                    note = o.strOrNull("note"),
                    createdAtUtc = o.long("createdAtUtc"),
                    updatedAtUtc = o.long("updatedAtUtc"),
                    rowVersion = o.int("rowVersion"),
                    deletedAtUtc = o.longOrNull("deletedAtUtc"),
                ),
            )
        }

        SyncEntity.REVISION -> {
            val currency = o.str("currency")
            SyncChange.RevisionChange(
                revision = CategoryMonthRevision(
                    id = o.str("id"),
                    cellId = o.str("cellId"),
                    op = RevisionOp.valueOf(o.str("op")),
                    amountAfter = Money(o.long("amountAfterMinor"), currency),
                    amountBefore = o.longOrNull("amountBeforeMinor")?.let { Money(it, currency) },
                    delta = o.longOrNull("deltaMinor")?.let { Money(it, currency) },
                    commentBefore = o.strOrNull("commentBefore"),
                    commentAfter = o.strOrNull("commentAfter"),
                    source = RevisionSource.valueOf(o.str("source")),
                    sourcePeriod = o.intOrNull("sourcePeriod")?.let(Period::ofKey),
                    idempotencyKey = o.strOrNull("idempotencyKey"),
                    createdAtUtc = o.long("createdAtUtc"),
                ),
                section = LedgerSection.valueOf(o.str("section")),
                categoryId = o.str("categoryId"),
                period = Period.ofKey(o.int("period")),
            )
        }

        else -> throw SyncFormatException("Неизвестный тип записи «$type»")
    }

    // ── Помощники ─────────────────────────────────────────────────────────────────

    private fun kotlinx.serialization.json.JsonObjectBuilder.putMeta(
        createdAtUtc: Long,
        updatedAtUtc: Long,
        rowVersion: Int,
        deletedAtUtc: Long?,
    ) {
        put("createdAtUtc", createdAtUtc)
        put("updatedAtUtc", updatedAtUtc)
        put("rowVersion", rowVersion)
        put("deletedAtUtc", deletedAtUtc)
    }

    private fun parse(line: String): JsonObject = json.parseToJsonElement(line).jsonObject

    private fun JsonObject.field(name: String): JsonElement =
        this[name] ?: throw SyncFormatException("Нет поля «$name»")

    private fun JsonObject.str(name: String): String =
        field(name).jsonPrimitive.contentOrNull ?: throw SyncFormatException("Пустое поле «$name»")

    private fun JsonObject.strOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.int(name: String): Int = field(name).jsonPrimitive.int

    private fun JsonObject.intOrNull(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(name: String): Long = field(name).jsonPrimitive.long

    private fun JsonObject.longOrNull(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(name: String): Boolean =
        field(name).jsonPrimitive.booleanOrNull ?: throw SyncFormatException("Поле «$name» не логическое")

    private inline fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: SyncFormatException) {
        throw e
    } catch (e: Exception) {
        // IllegalArgumentException из моделей (неверный цвет, период) и ошибки разбора JSON.
        throw SyncFormatException("Файл «$what» повреждён: ${e.message ?: e.javaClass.simpleName}", e)
    }
}
