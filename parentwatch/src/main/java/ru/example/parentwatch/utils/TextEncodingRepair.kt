package ru.example.parentwatch.utils

import java.nio.charset.Charset

/**
 * Repairs text that was decoded with the wrong single-byte charset.
 *
 * A stored profile name can arrive as `╨в╨╡╨║╤Г╤Й╨╕╨╣ ╨┐╤А╨╛╤Д╨╕╨╗╤М` instead of
 * `Текущий профиль`: the UTF-8 bytes of the real text were interpreted as
 * CP866, and the resulting characters were then saved as the name. The damage
 * is in the stored value, so the display layer cannot fix it by itself.
 *
 * The original bytes are recoverable, because the wrong decoding is reversible:
 * encode the damaged text back to CP866 and decode the result as UTF-8. The
 * repair is only applied when the input clearly shows the damage and the result
 * is verifiably valid text, so genuine names are never altered.
 */
object TextEncodingRepair {

    /** CP866 is what the damaged names were produced with. */
    private val wrongCharset: Charset? = runCatching { Charset.forName("CP866") }.getOrNull()

    /** Box-drawing and block characters only appear in the damaged form. */
    private val damageMarker = Regex("[\\u2500-\\u259F]")

    /** Cyrillic supplement characters are a second strong sign of the damage. */
    private val cyrillicSupplement = Regex("[\\u0480-\\u04FF]")

    fun looksDamaged(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        if (wrongCharset == null) return false
        if (!damageMarker.containsMatchIn(value)) return false
        return true
    }

    /**
     * Returns readable text for [value].
     *
     * A value that does not look damaged, or whose repair does not produce a
     * plausible result, is returned unchanged.
     */
    fun repair(value: String?): String? {
        if (value == null) return null
        if (!looksDamaged(value)) return value
        val charset = wrongCharset ?: return value

        val candidate = runCatching {
            String(value.toByteArray(charset), Charsets.UTF_8)
        }.getOrNull() ?: return value

        if (!isPlausible(candidate)) return value
        return candidate
    }

    /**
     * A repaired value is accepted only when the damage markers are gone and
     * what remains looks like words rather than control bytes.
     */
    private fun isPlausible(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (candidate.contains('\uFFFD')) return false
        if (damageMarker.containsMatchIn(candidate)) return false
        // The damaged form is dense in Cyrillic supplement characters; readable
        // text should not be.
        if (cyrillicSupplement.containsMatchIn(candidate)) return false
        val printable = candidate.count { !it.isISOControl() }
        return printable * 10 >= candidate.length * 9
    }
}
