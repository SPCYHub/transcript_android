package com.example.ai

import com.example.model.CantoneseFormat

/**
 * Prompt & System Instruction builders tuned specifically for Hong Kong Cantonese (粵語 / 廣東話)
 * across Direct Gemini REST/Live APIs and OpenAI Audio/Realtime Transcription APIs.
 *
 * Key design rule:
 * - **Gemini** supports top-level `systemInstruction` for strict behavioral rules (verbatim HK
 *   Traditional Chinese orthography, Spoken vs Written Chinese, custom glossary).
 * - **OpenAI STT (`gpt-4o-transcribe` / Realtime)** treats `prompt` as prior transcript context /
 *   vocabulary biasing — NOT as imperative instructions. Never pass instruction sentences into
 *   OpenAI's `prompt` parameter, or the model will echo them verbatim during pauses/silence.
 */
object CantonesePrompts {

    /**
     * Builds a safe, non-instructional vocabulary/context prompt for OpenAI Audio / Realtime STT.
     *
     * Returns an empty string when neither [contextTail] nor [customGlossary] is provided so the
     * model never echoes synthetic instructions into the transcript.
     */
    fun buildOpenAiPrompt(
        customGlossary: String,
        contextTail: String = ""
    ): String {
        val cleanTail = contextTail.trim().takeLast(60)
        val cleanGlossary = customGlossary
            .split(',', '，', '、', '\n', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("、")

        return when {
            cleanTail.isNotEmpty() && cleanGlossary.isNotEmpty() -> "$cleanGlossary。$cleanTail"
            cleanTail.isNotEmpty() -> cleanTail
            cleanGlossary.isNotEmpty() -> cleanGlossary
            else -> ""
        }
    }

    /**
     * Builds a non-instructional Traditional Hong Kong Cantonese primer for Whisper `initial_prompt`.
     * Anchors the decoder on authentic HK Cantonese characters (係、唔、嘅、咗、喺、啲) without using
     * imperative sentences that could echo verbatim on silence.
     */
    fun buildLocalWhisperPrompt(
        customGlossary: String,
        contextTail: String = ""
    ): String {
        val primer = "香港廣東話口語，用繁體字：係、唔、嘅、咗、喺、啲。"
        val cleanGlossary = customGlossary
            .split(',', '，', '、', '\n', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("、")
        val cleanTail = contextTail.trim().takeLast(60)

        return buildList {
            add(primer)
            if (cleanGlossary.isNotEmpty()) add(cleanGlossary)
            if (cleanTail.isNotEmpty()) add(cleanTail)
        }.joinToString(" ")
    }

    fun buildGeminiAudioSystemInstruction(
        includeWritten: Boolean,
        customGlossary: String
    ): String {
        val glossarySection = if (customGlossary.isNotBlank()) {
            "\nCustom Hong Kong vocabulary / proper nouns to bias spelling ONLY when actually spoken in the audio: $customGlossary"
        } else {
            ""
        }
        val writtenRule = if (includeWritten) {
            "2. writtenChinese: A natural, polished Standard Written Chinese (香港繁體書面語) version preserving the exact meaning and any English terms."
        } else {
            "2. writtenChinese: Leave as an empty string \"\" to minimize latency."
        }

        return """
            You are an expert Hong Kong Cantonese (粵語 / 廣東話) speech-to-text transcriber.
            Transcribe ONLY what is genuinely spoken in the audio. NEVER invent, add, or repeat words that were not spoken.

            Strict Rules:
            1. spokenCantonese: Verbatim Hong Kong colloquial Cantonese (口語) in Traditional Chinese (HK standard). Use authentic Cantonese characters (係、唔、嘅、咗、喺、啲、畀、諗、睇、講、嘢、冇、仲、咁、啦、㗎、喎、咩) and natural punctuation. Keep English words, brand names, and HK code-switching in exact English spelling (e.g., "聽日開 meeting confirm 個 deadline"). NEVER use Simplified Chinese.
            $writtenRule
            3. detectedLanguageMix: Short label of languages heard (e.g., "Cantonese", "Cantonese + English", "Cantonese + Mandarin").
            4. If the audio is silent, noise-only, or contains no intelligible speech, return spokenCantonese as "" (empty string).
            $glossarySection
        """.trimIndent()
    }

    fun buildGeminiAudioUserPrompt(): String =
        "Transcribe the attached audio strictly verbatim according to the system instructions."

    fun buildFastKeyboardSystemInstruction(
        format: CantoneseFormat,
        customGlossary: String
    ): String {
        val glossaryHint = if (customGlossary.isNotBlank()) {
            "\nBias spelling toward these Hong Kong terms ONLY if actually spoken: $customGlossary."
        } else {
            ""
        }
        val styleRule = when (format) {
            CantoneseFormat.WRITTEN_CHINESE ->
                "Transcribe the Hong Kong Cantonese voice dictation directly into concise Standard Written Chinese (香港繁體書面語) with natural punctuation and exact English code-switching."
            else ->
                "Transcribe the Hong Kong Cantonese voice dictation verbatim into authentic Hong Kong colloquial Cantonese (香港繁體口語：係、唔、嘅、咗、喺、啲、畀、啦、㗎) with natural punctuation and exact English code-switching."
        }
        return """
            You are a zero-latency Hong Kong Cantonese voice keyboard transcriber.
            $styleRule
            Output ONLY the spoken text to insert into a text field — no quotes, no commentary, no instructions, and NEVER use Simplified Chinese.
            NEVER invent words or echo instructions. If the audio contains only silence or background noise, output an empty string.$glossaryHint
        """.trimIndent()
    }

    fun buildFastKeyboardUserPrompt(contextTail: String = ""): String {
        val tail = contextTail.trim().takeLast(48)
        return if (tail.isNotEmpty()) {
            "Transcribe only the new speech in this audio clip. Continue naturally after the previous words (do NOT repeat them): \"$tail\""
        } else {
            "Transcribe only the speech in this audio clip."
        }
    }

    fun buildSpokenToWrittenSystemInstruction(): String = """
        You are an expert Hong Kong Cantonese-to-Standard-Written-Chinese editor.
        Convert Hong Kong spoken Cantonese (口語) into natural, clear Standard Written Chinese (香港繁體書面語).
        Preserve English words, numbers, names, and tone accurately. NEVER use Simplified Chinese.
        Output ONLY the converted text with no commentary or quotes.
    """.trimIndent()

    fun buildSpokenToWrittenUserPrompt(spokenCantonese: String): String = spokenCantonese

    fun buildDeepReviewSystemInstruction(customGlossary: String): String {
        val glossaryLine = if (customGlossary.isNotBlank()) {
            "\nUser glossary hints (apply only when matching sounds in the audio/transcript): $customGlossary"
        } else {
            ""
        }
        return """
            You are a senior Hong Kong Cantonese linguist and executive assistant.
            Perform a thorough, high-precision review of the provided Cantonese voice transcript (and attached audio if provided).

            Tasks:
            - Resolve Cantonese homophones, slurred speech, HK colloquialisms, and Cantonese-English code-switching errors.
            - Produce an upgraded verbatim Spoken Cantonese (refinedSpoken, 香港繁體口語) and polished Standard Written Chinese (refinedWritten, 香港繁體書面語).
            - In cantoneseNotes, briefly explain up to 3 notable colloquial phrases, ambiguous terms, or Chinglish expressions clarified (or note "No ambiguities detected" if completely clear).
            - In keyPoints, extract concise actionable takeaways, dates, numbers, or decisions (1 to 4 short bullet items in Traditional Chinese).
            - In suggestedReplies, draft 2 to 3 natural, ready-to-send Hong Kong WhatsApp replies in authentic Cantonese (short, practical, polite/casual).
            - NEVER use Simplified Chinese.$glossaryLine
        """.trimIndent()
    }

    fun buildDeepReviewUserPrompt(
        spokenCantonese: String,
        writtenChinese: String?
    ): String = """
        Current Spoken Transcript:
        $spokenCantonese

        Current Written Draft:
        ${writtenChinese.orEmpty().ifBlank { "(not yet generated)" }}
    """.trimIndent()
}
