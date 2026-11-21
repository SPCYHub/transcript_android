package com.example.data

import com.example.model.DeepReviewResult
import com.example.model.TranscriptRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral, session-only in-memory transcript store.
 *
 * - Never writes voice transcripts to disk or cloud; automatically clears when the app session ends.
 * - Maintains a SHA-256 content-hash cache so re-sharing the same WhatsApp voice clip resolves in 0ms.
 */
class SessionTranscriptStore {

    private val _records = MutableStateFlow<List<TranscriptRecord>>(emptyList())
    val records: StateFlow<List<TranscriptRecord>> = _records.asStateFlow()

    private val hashCache = ConcurrentHashMap<String, TranscriptRecord>()

    fun findCachedByHash(sha256: String): TranscriptRecord? = hashCache[sha256]

    fun addOrUpdate(record: TranscriptRecord) {
        hashCache[record.audioSha256] = record
        _records.update { existing ->
            val filtered = existing.filterNot { it.id == record.id }
            listOf(record) + filtered
        }
    }

    fun updateWrittenChinese(recordId: String, writtenChinese: String): TranscriptRecord? {
        var updated: TranscriptRecord? = null
        _records.update { list ->
            list.map { item ->
                if (item.id == recordId) {
                    item.copy(writtenChinese = writtenChinese).also {
                        updated = it
                        hashCache[it.audioSha256] = it
                    }
                } else {
                    item
                }
            }
        }
        return updated
    }

    fun attachDeepReview(recordId: String, review: DeepReviewResult): TranscriptRecord? {
        var updated: TranscriptRecord? = null
        _records.update { list ->
            list.map { item ->
                if (item.id == recordId) {
                    item.copy(
                        deepReview = review,
                        writtenChinese = item.writtenChinese ?: review.refinedWritten
                    ).also {
                        updated = it
                        hashCache[it.audioSha256] = it
                    }
                } else {
                    item
                }
            }
        }
        return updated
    }

    fun removeById(recordId: String) {
        val target = _records.value.find { it.id == recordId }
        if (target != null) {
            hashCache.remove(target.audioSha256)
        }
        _records.update { it.filterNot { item -> item.id == recordId } }
    }

    fun clearAll() {
        hashCache.clear()
        _records.value = emptyList()
    }
}
