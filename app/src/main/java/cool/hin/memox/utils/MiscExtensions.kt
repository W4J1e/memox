package cool.hin.memox.utils

import android.util.Patterns
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.text.RegexOption

fun CharSequence.truncate(limit: Int): CharSequence {
    return if (length > limit) {
        val truncated = take(limit)
        val remainingCharacters = length - limit
        "$truncated... ($remainingCharacters more characters)"
    } else {
        this
    }
}

fun CharSequence.startsWithAnyOf(vararg s: String): Boolean {
    s.forEach { if (startsWith(it)) return true }
    return false
}

fun CharSequence.fromCamelCaseToEnumName(): String {
    return this.fold(StringBuilder()) { acc, char ->
            if (char.isUpperCase() && acc.isNotEmpty()) {
                acc.append("_")
            }
            acc.append(char.uppercase())
        }
        .toString()
}

// 仅识别带 http(s):// 协议的链接，避免把 "1.xxx" / "2.xxx" 这类带序号的小标题误判为链接。
private val SCHEME_URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

// 链接结尾可能被紧跟着的标点/括号带进去，识别时把这些尾字符剔除。
private val URL_TRAILING_PUNCTUATION: Set<Char> =
    ".,;:!?')\uFF09\u3002\uFF0C\uFF1B\uFF1A\uFF01\uFF1F\u300D\u300F\u201D".toSet()

fun CharSequence?.isWebUrl(): Boolean {
    val text = this?.trim() ?: return false
    return SCHEME_URL_REGEX.matches(text)
}

fun CharSequence?.findWebUrls(): Collection<Pair<Int, Int>> {
    return this?.let { text ->
        val matches = mutableListOf<Pair<Int, Int>>()
        SCHEME_URL_REGEX.findAll(text).forEach { m ->
            var end = m.range.last + 1
            while (end > m.range.first && text[end - 1] in URL_TRAILING_PUNCTUATION) {
                end--
            }
            if (end > m.range.first) {
                matches.add(Pair(m.range.first, end))
            }
        }
        matches
    } ?: listOf()
}

/** Calculates the character limit for a given MB size (in worst case). */
fun Double.charLimit(): Int {
    val totalBytes = (this * 1024 * 1024).toInt()
    val minChars = totalBytes / 4 // Every character is an Emoji/Complex
    return minChars
}

fun String.findAllOccurrences(
    search: String,
    caseSensitive: Boolean = false,
): List<Pair<Int, Int>> {
    if (search.isEmpty()) return emptyList()
    val regex = Regex(Regex.escape(if (caseSensitive) search else search.lowercase()))
    return regex
        .findAll(if (caseSensitive) this else this.lowercase())
        .map { match -> match.range.first to match.range.last + 1 }
        .toList()
}

fun String.removeTrailingParentheses(): String {
    return substringBeforeLast(" (")
}

fun String.toCamelCase(): String {
    return this.lowercase()
        .split("_")
        .mapIndexed { index, word ->
            if (index == 0) word
            else
                word.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
        }
        .joinToString("")
}

fun String.getUrl(start: Int, end: Int): String {
    return if (end <= length) {
        substring(start, end).toUrl()
    } else substring(start, length).toUrl()
}

private fun String.toUrl(): String {
    return when {
        matches(Patterns.PHONE.toRegex()) -> "tel:$this"
        matches(Patterns.EMAIL_ADDRESS.toRegex()) -> "mailto:$this"
        matches(Patterns.DOMAIN_NAME.toRegex()) -> "http://$this"
        else -> this
    }
}

val String.toPreservedByteArray: ByteArray
    get() {
        return this.toByteArray(Charsets.ISO_8859_1)
    }

val ByteArray.toPreservedString: String
    get() {
        return String(this, Charsets.ISO_8859_1)
    }

fun now(): Calendar =
    Calendar.getInstance().apply {
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

typealias TimeMillis = Long

fun TimeMillis.secondsBetween(other: TimeMillis): Long = abs(this - other) / 1000

fun <T : Enum<T>> List<T>.serializeEnums(): String {
    return joinToString(separator = ",") { it.name }
}

fun <T : Enum<T>> Class<T>.deserializeEnums(data: String): List<T> {
    if (data.isEmpty()) return emptyList()

    return data.split(",").mapNotNull { name ->
        try {
            java.lang.Enum.valueOf(this, name.trim())
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

fun uniqueCurrentMillis(): Long {
    Thread.sleep(1)
    return System.currentTimeMillis()
}

typealias Seconds = Long

fun Seconds.toMillis() = this * 1000
