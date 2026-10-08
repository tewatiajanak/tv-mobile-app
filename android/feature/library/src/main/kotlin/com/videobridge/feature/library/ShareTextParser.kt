package com.videobridge.feature.library

/**
 * Finds web links in text shared from other apps. WhatsApp and Telegram share a whole message
 * ("Watch this https://… 😀"), so the link has to be picked out of it.
 */
object ShareTextParser {
    private val LINK = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}'\""

    // Shared text is untrusted and can be huge; only its start is examined.
    private const val MAX_INPUT = 20_000

    fun extractUrls(text: String?): List<String> = LINK
        .findAll(text.orEmpty().take(MAX_INPUT))
        .map { it.value.trimEnd { char -> char in TRAILING } }
        .filter { it.length > "https://".length }
        .distinct()
        .toList()

    /** The first link in the text, or the first in the subject (some apps put it there). */
    fun firstUrl(text: String?, subject: String? = null): String? = extractUrls(text).firstOrNull() ?: extractUrls(subject).firstOrNull()
}
