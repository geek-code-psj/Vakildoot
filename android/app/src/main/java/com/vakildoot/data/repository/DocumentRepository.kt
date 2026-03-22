package com.vakildoot.data.repository

import com.vakildoot.data.model.ChatMessage
import com.vakildoot.data.model.Document
import com.vakildoot.data.model.DocumentChunk
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.toFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
 * PHASE 1: Falls back to InMemoryDocumentStore if ObjectBox is null
 * PHASE 2: Uses real ObjectBox with persistent storage
 */
@Singleton
class DocumentRepository @Inject constructor(
    private val boxStore: io.objectbox.BoxStore?,
    private val inMemoryStore: InMemoryDocumentStore,
) {
    private val documentBox: Box<Document>?      by lazy { boxStore?.boxFor() }
    private val chunkBox:    Box<DocumentChunk>? by lazy { boxStore?.boxFor() }
    private val messageBox:  Box<ChatMessage>?   by lazy { boxStore?.boxFor() }
    
    private val useInMemory: Boolean = boxStore == null

    // ── Documents ────────────────────────────────────────────────────────────

    fun getAllDocuments(): Flow<List<Document>> {
        return if (useInMemory) {
            Timber.d("Repository: Using in-memory storage for documents")
            inMemoryStore.getAllDocuments()
        } else {
            documentBox?.query()?.build()?.subscribe()?.toFlow()
                ?.map { it.sortedByDescending { d -> d.addedAt } } ?: emptyFlow()
        }
    }

    suspend fun getDocument(id: Long): Document? = withContext(Dispatchers.IO) {
        if (useInMemory) {
            inMemoryStore.getDocument(id)
        } else {
            documentBox?.get(id)
        }
    }

    suspend fun findDocumentByFilePath(filePath: String): Document? = withContext(Dispatchers.IO) {
        if (filePath.isBlank()) return@withContext null
        if (useInMemory) {
            inMemoryStore.findDocumentByFilePath(filePath)
        } else {
            (documentBox?.all ?: emptyList()).firstOrNull { it.filePath == filePath }
        }
    }

    suspend fun insertDocument(document: Document): Long = withContext(Dispatchers.IO) {
        if (useInMemory) {
            inMemoryStore.insertDocument(document)
        } else {
            documentBox?.put(document)?.also { Timber.d("Inserted document id=$it") } ?: 0L
        }
    }

    suspend fun updateDocument(document: Document) = withContext(Dispatchers.IO) {
        if (useInMemory) {
            inMemoryStore.updateDocument(document)
        } else {
            documentBox?.put(document)
        }
    }

    suspend fun deleteDocument(id: Long) = withContext(Dispatchers.IO) {
        if (useInMemory) {
            inMemoryStore.deleteDocument(id)
        } else {
            // Also delete chunks and messages
            if (boxStore != null) {
                val allChunks = chunkBox?.all ?: emptyList()
                val chunkIds = allChunks.filter { it.documentId == id }.map { it.id }
                chunkBox?.removeByIds(chunkIds)

                val allMessages = messageBox?.all ?: emptyList()
                val msgIds = allMessages.filter { it.documentId == id }.map { it.id }
                messageBox?.removeByIds(msgIds)

                documentBox?.remove(id)
                Timber.d("Deleted document $id with ${chunkIds.size} chunks and ${msgIds.size} messages")
            }
        }
    }

    // ── Chunks ───────────────────────────────────────────────────────────────

    suspend fun insertChunks(chunks: List<DocumentChunk>) = withContext(Dispatchers.IO) {
        if (useInMemory) {
            inMemoryStore.insertChunks(chunks)
        } else {
            chunkBox?.put(chunks)
        }
    }

    suspend fun getChunksForDocument(documentId: Long): List<DocumentChunk> =
        withContext(Dispatchers.IO) {
            if (useInMemory) {
                inMemoryStore.getChunksForDocument(documentId)
            } else {
                (chunkBox?.all ?: emptyList())
                    .filter { it.documentId == documentId }
                    .sortedBy { it.chunkIndex }
            }
        }

    suspend fun getChunkCount(documentId: Long): Int = withContext(Dispatchers.IO) {
        if (useInMemory) {
            inMemoryStore.getChunkCount(documentId)
        } else {
            (chunkBox?.all ?: emptyList()).count { it.documentId == documentId }
        }
    }

    // ── Chat messages ────────────────────────────────────────────────────────

    fun getMessagesForDocument(documentId: Long): Flow<List<ChatMessage>> {
        return if (useInMemory) {
            inMemoryStore.getMessagesForDocument(documentId)
        } else {
            messageBox?.query()?.build()?.subscribe()?.toFlow()
                ?.map { it.filter { m -> m.documentId == documentId }.sortedBy { m -> m.timestamp } }
                ?: emptyFlow()
        }
    }

    suspend fun insertMessage(message: ChatMessage): Long = withContext(Dispatchers.IO) {
        if (useInMemory) {
            inMemoryStore.insertMessage(message)
        } else {
            messageBox?.put(message) ?: 0L
        }
    }

    suspend fun clearMessages(documentId: Long) = withContext(Dispatchers.IO) {
        if (useInMemory) {
            inMemoryStore.clearMessages(documentId)
        } else {
            if (boxStore != null) {
                val ids = (messageBox?.all ?: emptyList())
                    .filter { it.documentId == documentId }
                    .map { it.id }
                messageBox?.removeByIds(ids)
            }
        }
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    suspend fun getTotalDocumentCount(): Int = withContext(Dispatchers.IO) {
        if (useInMemory) {
            inMemoryStore.getTotalDocumentCount()
        } else {
            documentBox?.count()?.toInt() ?: 0
        }
    }

    suspend fun getTotalChunkCount(): Int = withContext(Dispatchers.IO) {
        if (useInMemory) {
            inMemoryStore.getTotalChunkCount()
        } else {
            chunkBox?.count()?.toInt() ?: 0
        }
    }
}
