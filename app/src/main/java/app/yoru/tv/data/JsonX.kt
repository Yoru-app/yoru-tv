package app.yoru.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Общий JSON: терпимый к лишним и странным полям. */
val JsonCfg = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/*
 * Ответы внешних API разбираем вручную: Anime365 и Shikimori отдают числа то строкой,
 * то числом, флаги — то 0/1, то true/false. Так надёжнее, чем строгие модели.
 */
fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
fun JsonObject.long(k: String): Long? = str(k)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
fun JsonObject.int(k: String): Int? = long(k)?.toInt()
fun JsonObject.double(k: String): Double? = str(k)?.replace(',', '.')?.toDoubleOrNull()
fun JsonObject.bool(k: String): Boolean? = when (str(k)?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}
fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.a(k: String): JsonArray? = this[k] as? JsonArray
val JsonElement?.obj: JsonObject? get() = this as? JsonObject
fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject } ?: emptyList()

fun parseJson(text: String): JsonElement = JsonCfg.parseToJsonElement(text)

/** Разбор ответа вне основного потока: экраны запускают запросы с него, и большой JSON подвешивал бы интерфейс. */
suspend fun parseOff(text: String): JsonElement = withContext(Dispatchers.Default) { parseJson(text) }

/** Экранирование строки для подстановки в GraphQL-запрос. */
fun gqlString(s: String): String = JsonPrimitive(s).toString()
