package com.vakildoot.data.repository

import com.vakildoot.data.model.ChatMessage
import com.vakildoot.data.model.Document
import com.vakildoot.data.model.DocumentChunk
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.toFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DocumentRepository
 *
 * All persistence to ObjectBox — a local NoSQL database with native
 * vector support. Data never leaves the device.
 *
 * ObjectBox is chosen over Room for Phase 1 because:
 * - Sub-millisecond reads for vector similarity
 * - No SQL schema migrations
 * - Native FloatArray storage (Phase 2 upgrade from serialised strings)
 *
 * In Phase 2, the embedding vectors migrate to sqlite-vss for HNSW
 * approximate nearest-neighbour search.
 */
@Singleton
class DocumentRepository @Inject constructor(
    private val boxStore: io.objectbox.BoxStore,
) {
    private val documentBox: Box<Document>      by lazy { boxStore.boxFor() }
    private val chunkBox:    Box<DocumentChunk> by lazy { boxStore.boxFor() }
    private val messageBox:  Box<ChatMessage>   by lazy { boxStore.boxFor() }

    // ── Documents ────────────────────────────────────────────────────────────

    fun getAllDocuments(): Flow<List<Document>> =
        documentBox.query().build().subscribe().toFlow()
            .map { it.sortedByDescending { d -> d.addedAt } }

    suspend fun getDocument(id: Long): Document? = withContext(Dispatchers.IO) {
        documentBox.get(id)
    }

    suspend fun insertDocument(document: Document): Long = withContext(Dispatchers.IO) {
        documentBox.put(document).also { Timber.d("Inserted document id=$it") }
    }

    suspend fun updateDocument(document: Document) = withContext(Dispatchers.IO) {
        documentBox.put(document)
    }

    suspend fun deleteDocument(id: Long) = withContext(Dispatchers.IO) {
        // Also delete chunks and messages
        val chunkIds = chunkBox.query()
            .equal(DocumentChunk_.documentId, id)
            .build().findIds()
        chunkBox.removeByIds(chunkIds.toList())

        val msgIds = messageBox.query()
            .equal(ChatMessage_.documentId, id)
            .build().findIds()
        messageBox.removeByIds(msgIds.toList())

        documentBox.remove(id)
        Timber.d("Deleted document $id with ${chunkIds.size} chunks and ${msgIds.size} messages")
    }

    // ── Chunks ───────────────────────────────────────────────────────────────

    suspend fun insertChunks(chunks: List<DocumentChunk>) = withContext(Dispatchers.IO) {
        chunkBox.put(chunks)
    }

    suspend fun getChunksForDocument(documentId: Long): List<DocumentChunk> =
        withContext(Dispatchers.IO) {
            chunkBox.query()
                .equal(DocumentChunk_.documentId, documentId)
                .build().find()
                .sortedBy { it.chunkIndex }
        }

    suspend fun getChunkCount(documentId: Long): Int = withContext(Dispatchers.IO) {
        chunkBox.query()
            .equal(DocumentChunk_.documentId, documentId)
            .build().count().toInt()
    }

    // ── Chat messages ────────────────────────────────────────────────────────

    fun getMessagesForDocument(documentId: Long): Flow<List<ChatMessage>> =
        messageBox.query()
            .equal(ChatMessage_.documentId, documentId)
            .build().subscribe().toFlow()
            .map { it.sortedBy { m -> m.timestamp } }

    suspend fun insertMessage(message: ChatMessage): Long = withContext(Dispatchers.IO) {
        messageBox.put(message)
    }

    suspend fun clearMessages(documentId: Long) = withContext(Dispatchers.IO) {
        val ids = messageBox.query()
            .equal(ChatMessage_.documentId, documentId)
            .build().findIds()
        messageBox.removeByIds(ids.toList())
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    suspend fun getTotalDocumentCount(): Int = withContext(Dispatchers.IO) {
        documentBox.count().toInt()
    }

    suspend fun getTotalChunkCount(): Int = withContext(Dispatchers.IO) {
        chunkBox.count().toInt()
    }
}
