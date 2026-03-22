package com.vakildoot.data.repository

import com.vakildoot.data.model.Document
import com.vakildoot.data.model.DocumentChunk
import com.vakildoot.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Singleton

/**
 * PHASE 1: In-Memory Document Storage
 *
 * Stores all data in RAM - perfect for development and testing.
 * Data is lost when app closes (expected for Phase 1).
 *
 * Phase 2 will replace this with persistent ObjectBox.
 */
@Singleton
class InMemoryDocumentStore {
    
    private val documents = MutableStateFlow<List<Document>>(emptyList())
    private val chunks = mutableMapOf<Long, MutableList<DocumentChunk>>()
    private val messages = mutableMapOf<Long, MutableList<ChatMessage>>()
    
    private var nextDocId = 1L
    private var nextChunkId = 1L
    private var nextMessageId = 1L
    
    // ── Documents ────────────────────────────────────────────────────────────
    
    fun getAllDocuments(): Flow<List<Document>> {
        return documents
    }
    
    suspend fun getDocument(id: Long): Document? {
        return documents.value.find { it.id == id }
    }

    suspend fun findDocumentByFilePath(filePath: String): Document? {
        return documents.value.find { it.filePath == filePath }
    }
    
    suspend fun insertDocument(document: Document): Long {
        val newDoc = document.copy(id = nextDocId)
        nextDocId++
        
        documents.value = documents.value + newDoc
        Timber.i("InMemory: Inserted document id=${newDoc.id}, name=${newDoc.fileName}")
        return newDoc.id
    }
    
    suspend fun updateDocument(document: Document) {
        val index = documents.value.indexOfFirst { it.id == document.id }
        if (index >= 0) {
            val updated = documents.value.toMutableList()
            updated[index] = document
            documents.value = updated
            Timber.i("InMemory: Updated document id=${document.id}")
        }
    }
    
    suspend fun deleteDocument(id: Long) {
        documents.value = documents.value.filter { it.id != id }
        chunks.remove(id)
        messages.remove(id)
        Timber.i("InMemory: Deleted document id=$id")
    }
    
    suspend fun getTotalDocumentCount(): Int {
        return documents.value.size
    }
    
    // ── Chunks ───────────────────────────────────────────────────────────────
    
    suspend fun insertChunks(docChunks: List<DocumentChunk>) {
        for (chunk in docChunks) {
            val newChunk = chunk.copy(id = nextChunkId)
            nextChunkId++
            
            if (!chunks.containsKey(chunk.documentId)) {
                chunks[chunk.documentId] = mutableListOf()
            }
            chunks[chunk.documentId]!!.add(newChunk)
        }
        Timber.i("InMemory: Inserted ${docChunks.size} chunks")
    }
    
    suspend fun getChunksForDocument(documentId: Long): List<DocumentChunk> {
        return chunks[documentId]?.sortedBy { it.chunkIndex } ?: emptyList()
    }
    
    suspend fun getChunkCount(documentId: Long): Int {
        return chunks[documentId]?.size ?: 0
    }
    
    suspend fun getTotalChunkCount(): Int {
        return chunks.values.sumOf { it.size }
    }
    
    // ── Chat Messages ────────────────────────────────────────────────────────
    
    fun getMessagesForDocument(documentId: Long): Flow<List<ChatMessage>> {
        val msgs = messages[documentId]?.sortedBy { it.timestamp } ?: emptyList()
        return flowOf(msgs)
    }
    
    suspend fun insertMessage(message: ChatMessage): Long {
        val newMsg = message.copy(id = nextMessageId)
        nextMessageId++
        
        if (!messages.containsKey(message.documentId)) {
            messages[message.documentId] = mutableListOf()
        }
        messages[message.documentId]!!.add(newMsg)
        Timber.i("InMemory: Inserted message id=${newMsg.id}")
        return newMsg.id
    }
    
    suspend fun clearMessages(documentId: Long) {
        messages.remove(documentId)
        Timber.i("InMemory: Cleared messages for document $documentId")
    }
    
    fun clearAll() {
        documents.value = emptyList()
        chunks.clear()
        messages.clear()
        nextDocId = 1L
        nextChunkId = 1L
        nextMessageId = 1L
        Timber.i("InMemory: Cleared all data")
    }
}

