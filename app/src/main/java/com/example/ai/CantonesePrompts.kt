package com.example.ai

import com.example.model.CantoneseFormat
import com.google.firebase.ai.type.Schema

object CantonesePrompts {

    const val OPENAI_CANTONESE_BASE_PROMPT =
        "以下係香港廣東話語音，請用香港繁體中文準確記錄口語原文（例如：係、唔、嘅、咗、喺、啲、畀、諗、睇、講、嘢、啦、㗎、喎），保留夾雜嘅英文詞彙，唔好轉做簡體字或者普通話書面語。"

    fun buildOpenAiPrompt(customGlossary: String, preferWritten: Boolean): String {
        val styleHint = if (preferWritten) {
            "請用香港繁體中文清晰標點記錄，整理成自然流暢嘅書面語。"
        } else {
            OPENAI_CANTONESE_BASE_PROMPT
        }
        return if (customGlossary.isNotBlank()) {
            "$styleHint 常用專有名詞或術語參考：$customGlossary"
        } else {
            styleHint
        }
    }

    fun buildGeminiAudioPrompt(includeWritten: Boolean, customGlossary: String): String {
        val glossarySection = if (customGlossary.isNotBlank()) {
            "\nUser custom Hong Kong vocabulary/names to prioritize when matching sounds: $customGlossary"
        } else {
            ""
        }
        val writtenRule = if (includeWritten) {
            "2. writtenChinese: A natural, polished Standard Written Chinese (書面語, Traditional Chinese HK) version that preserves the exact meaning and any English terms."
        } else {
            "2. writtenChinese: Leave as an empty string \"\" to minimize output latency."
        }

        return """
            You are an expert Hong Kong Cantonese (粵語 / 廣東話) speech-to-text transcriber.
            Listen carefully to the attached audio and transcribe it with maximum fidelity.

            Rules:
            1. spokenCantonese: Verbatim Hong Kong colloquial Cantonese (口語) in Traditional Chinese (HK standard). Use authentic Cantonese characters (係、唔、嘅、咗、喺、啲、畀、諗、睇、講、嘢、冇、仲、咁、啦、㗎、喎、咩) and natural punctuation. Keep English words, brand names, and HK "Chinglish" code-switching in exact English spelling (e.g., "聽日開 meeting confirm 個 deadline"). NEVER use Simplified Chinese.
            $writtenRule
            3. detectedLanguageMix: Short label of languages heard (e.g., "Cantonese", "Cantonese + English", "Cantonese + Mandarin").
            If the audio is completely silent or unintelligible noise, return spokenCantonese as "[無法辨識語音 / Silence detected]".
            $glossarySection
        """.trimIndent()
    }

    fun buildFastKeyboardPrompt(format: CantoneseFormat, customGlossary: String): String {
        val glossaryHint = if (customGlossary.isNotBlank()) {
            " Prioritize these terms if heard: $customGlossary."
        } else {
            ""
        }
        return when (format) {
            CantoneseFormat.WRITTEN_CHINESE ->
                "Transcribe this Hong Kong Cantonese voice dictation directly into concise Standard Written Chinese (繁體書面語) with natural punctuation and exact English code-switching. Output ONLY the final text to insert into a text field, with no quotes or explanation.$glossaryHint"
            else ->
                "Transcribe this Hong Kong Cantonese voice dictation verbatim into authentic Hong Kong colloquial Cantonese (香港繁體口語：係、唔、嘅、咗、喺、啲、畀、啦、㗎) with natural punctuation and exact English code-switching. Output ONLY the final text to insert into a text field, with no quotes or explanation.$glossaryHint"
        }
    }

    fun buildSpokenToWrittenPrompt(spokenCantonese: String): String = """
        Convert the following Hong Kong spoken Cantonese (口語) transcript into natural, clear Standard Written Chinese (香港繁體書面語).
        Preserve any English words, numbers, names, and tone accurately. Never use Simplified Chinese.
        Output ONLY the converted written Chinese text with no extra commentary.

        Transcript:
        $spokenCantonese
    """.trimIndent()

    fun buildDeepReviewPrompt(
        spokenCantonese: String,
        writtenChinese: String?,
        customGlossary: String
    ): String {
        val glossaryLine = if (customGlossary.isNotBlank()) {
            "User glossary hints: $customGlossary\n"
        } else {
            ""
        }
        return """
            You are a senior Hong Kong Cantonese linguist and executive assistant.
            Perform a thorough, high-precision review of this Cantonese voice transcript (and attached audio if provided).

            Tasks:
            - Resolve Cantonese homophones, slurred speech, HK colloquialisms, and Cantonese-English code-switching errors.
            - Produce an upgraded verbatim Spoken Cantonese (refinedSpoken, 繁體口語) and polished Standard Written Chinese (refinedWritten, 繁體書面語).
            - In cantoneseNotes, briefly explain up to 3 notable colloquial phrases, ambiguous terms, or Chinglish expressions clarified (or note "No ambiguities detected" if completely clear).
            - In keyPoints, extract concise actionable takeaways, dates, numbers, or decisions (1 to 4 short bullet items in Traditional Chinese).
            - In suggestedReplies, draft 2 to 3 natural, ready-to-send Hong Kong WhatsApp replies in authentic Cantonese (short, practical, polite/casual).
            $glossaryLine
            Current Spoken Transcript:
            $spokenCantonese

            Current Written Draft:
            ${writtenChinese.orEmpty().ifBlank { "(not yet generated)" }}
        """.trimIndent()
    }

    val audioTranscriptSchema: Schema = Schema.obj(
        properties = mapOf(
            "spokenCantonese" to Schema.string(
                description = "Verbatim Hong Kong colloquial Cantonese in Traditional Chinese with natural English code-switching"
            ),
            "writtenChinese" to Schema.string(
                description = "Polished Standard Written Chinese in Traditional Chinese, or empty string if omitted"
            ),
            "detectedLanguageMix" to Schema.string(
                description = "Short label describing the detected language mix, e.g. Cantonese + English"
            )
        )
    )

    val deepReviewSchema: Schema = Schema.obj(
        properties = mapOf(
            "refinedSpoken" to Schema.string(
                description = "Refined verbatim Hong Kong spoken Cantonese in Traditional Chinese"
            ),
            "refinedWritten" to Schema.string(
                description = "Polished Standard Written Chinese in Traditional Chinese"
            ),
            "cantoneseNotes" to Schema.array(
                items = Schema.string(),
                description = "Brief clarifications of Hong Kong slang, homophones, or Chinglish terms"
            ),
            "keyPoints" to Schema.array(
                items = Schema.string(),
                description = "Key actionable takeaways, times, or decisions extracted from the audio"
            ),
            "suggestedReplies" to Schema.array(
                items = Schema.string(),
                description = "Short, natural Hong Kong Cantonese WhatsApp quick replies"
            )
        )
    )
}
