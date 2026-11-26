package com.example.ai

/**
 * Post-transcription hygiene filter for Cantonese / mixed-language speech-to-text outputs.
 *
 * Prevents three common STT artifacts from leaking into host apps or transcript cards:
 * 1. OpenAI Whisper / gpt-4o-transcribe prompt echoes (when prompt text or glossary leaks during silence).
 * 2. Known CJK/English silence hallucinations generated on near-silent audio buffers.
 * 3. Adjacent duplicate sentences/phrases caused by overlapping VAD segments or streaming commits.
 */
object TranscriptSanitizer {

    private val LEAKED_INSTRUCTION_PATTERNS = listOf(
        Regex("以下係香港廣東話語音[，,。\\s]*.*?書面語[。.]?"),
        Regex("請用香港繁體中文清晰標點記錄[，,。\\s]*整理成自然流暢[嘅的]?書面語[。.]?"),
        Regex("常用專有名詞或術語參考[：:]?[^。！？!?\\n]*[。.]?"),
        Regex("請用香港繁體中文準確記錄口語原文[^。！？!?\\n]*[。.]?"),
        Regex("保留夾雜[嘅的]英文詞彙[，,、\\s]*唔好轉做簡體字或者普通話書面語[。.]?"),
        Regex("香港廣東話口語[，,]?用繁體字[：:]?[^。\\n]*[。.]?"),
        Regex("香港繁體廣東話口語[。.]?"),
        Regex("香港繁體中文[。.]?")
    )

    /**
     * Well-known Whisper/OpenAI silence hallucination phrases that appear only when an audio
     * slice contains silence or ambient noise without actual human speech.
     */
    private val SILENCE_HALLUCINATION_EXACT = setOf(
        "謝謝觀看",
        "感謝收看",
        "感謝觀看",
        "謝謝收看",
        "多謝收看",
        "多謝觀看",
        "請不吝點讚訂閱轉發打賞支持明鏡與點點欄目",
        "請不吝點讚",
        "訂閱轉發",
        "字幕由索蘭婭提供",
        "字幕由社群提供",
        "字幕由Amara.org社群提供",
        "由Amara.org社群提供的字幕",
        "Amara.org",
        "Thank you for watching.",
        "Thanks for watching.",
        "Thank you.",
        "MBC 뉴스 이덕영입니다.",
        "ご視聴ありがとうございました",
        "ご視聴ありがとうございました。",
        "[無法辨識語音 / Silence detected]",
        "[Silence]"
    )

    private val SILENCE_HALLUCINATION_CONTAINS = listOf(
        "請不吝點讚",
        "明鏡與點點欄目",
        "字幕由",
        "Amara.org",
        "CC BY-SA",
        "訂閱我的頻道",
        "按讚訂閱"
    )

    /**
     * Cleans a single transcribed segment, removing any leaked prompt text, known silence
     * hallucinations, and immediate sentence-level repetitions inside the segment.
     */
    fun sanitizeSegment(
        rawText: String,
        activePrompt: String = "",
        customGlossary: String = ""
    ): String {
        var text = rawText.trim()
        if (text.isEmpty()) return ""

        // 1. Strip exact active prompt echo if the STT model echoed the prompt verbatim
        val cleanPrompt = activePrompt.trim()
        if (cleanPrompt.length >= 4 && text.contains(cleanPrompt)) {
            text = text.replace(cleanPrompt, "").trim()
        }

        // 2. Strip leaked custom glossary verbatim if echoed as a raw list
        val cleanGlossary = customGlossary.trim()
        if (cleanGlossary.length >= 4 && text == cleanGlossary) {
            return ""
        }

        // 3. Strip any legacy/known prompt instruction fragments
        for (pattern in LEAKED_INSTRUCTION_PATTERNS) {
            text = pattern.replace(text, "").trim()
        }

        // 4. Reject pure silence hallucinations
        val normalizedForHallucination = text.trimEnd('。', '.', '！', '!', '？', '?', '，', ',', ' ')
        if (text.isEmpty() ||
            text in SILENCE_HALLUCINATION_EXACT ||
            normalizedForHallucination in SILENCE_HALLUCINATION_EXACT
        ) {
            return ""
        }
        if (text.length <= 36 && SILENCE_HALLUCINATION_CONTAINS.any { text.contains(it, ignoreCase = true) }) {
            return ""
        }

        // 5. Collapse immediate duplicate sentences within the same segment (e.g. "不如我哋一齊試吓。不如我哋一齊試吓。")
        text = collapseRepeatedSentences(text)

        return text.trim()
    }

    /**
     * Removes leading overlap or exact suffix duplication when appending [incoming] onto [existing].
     * Returns only the net-new suffix that should be appended/committed (may be empty if fully duplicated).
     */
    fun deduplicateAgainstExisting(existing: String, incoming: String): String {
        val cleanIncoming = incoming.trim()
        if (cleanIncoming.isEmpty()) return ""
        val cleanExisting = existing.trim()
        if (cleanExisting.isEmpty()) return cleanIncoming

        // Exact suffix duplicate (e.g. existing ends with "不如我哋一齊試吓。" and incoming is "不如我哋一齊試吓。")
        if (cleanExisting.endsWith(cleanIncoming)) {
            return ""
        }

        // Ignore trailing punctuation differences when comparing short repeated utterances
        val existingNoTrail = stripTrailingPunct(cleanExisting)
        val incomingNoTrail = stripTrailingPunct(cleanIncoming)
        if (incomingNoTrail.length >= 3 && existingNoTrail.endsWith(incomingNoTrail)) {
            return ""
        }

        // Partial overlap at the boundary (e.g. chunked audio overlap: existing ends with "一齊試吓" and incoming starts with "一齊試吓啦")
        val maxOverlap = minOf(cleanExisting.length, cleanIncoming.length, 24)
        for (len in maxOverlap downTo 3) {
            val suffix = cleanExisting.takeLast(len)
            if (cleanIncoming.startsWith(suffix)) {
                return cleanIncoming.substring(len).trimStart()
            }
        }

        return cleanIncoming
    }

    private fun collapseRepeatedSentences(text: String): String {
        // Split while preserving Chinese/English sentence terminators
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?') {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) parts.add(sentence)
                current.clear()
            }
        }
        val remainder = current.toString().trim()
        if (remainder.isNotEmpty()) parts.add(remainder)

        if (parts.size <= 1) return text

        val deduped = mutableListOf<String>()
        for (part in parts) {
            val prev = deduped.lastOrNull()
            if (prev != null && stripTrailingPunct(prev) == stripTrailingPunct(part) && stripTrailingPunct(part).length >= 2) {
                continue
            }
            deduped.add(part)
        }
        return deduped.joinToString("")
    }

    private fun stripTrailingPunct(s: String): String {
        return s.trimEnd('。', '，', '、', '！', '？', '.', ',', '!', '?', ' ', '　')
    }
}
