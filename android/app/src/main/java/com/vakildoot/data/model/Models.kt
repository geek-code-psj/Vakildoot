package com.vakildoot.data.model

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

// ─────────────────────────────────────────────────────────────
//  Document — represents an indexed legal PDF
// ─────────────────────────────────────────────────────────────
@Entity
data class Document(
    @Id var id: Long = 0,
    val fileName: String = "",
    val displayName: String = "",
    val filePath: String = "",        // encrypted local path
    val fileSize: Long = 0L,
    val pageCount: Int = 0,
    val chunkCount: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val indexedAt: Long = 0L,
    val riskScore: Int = 0,           // 0–100
    val riskLevel: String = "UNKNOWN",// LOW / MED / HIGH
    val clauseCount: Int = 0,
    val summary: String = "",
    val isIndexed: Boolean = false,
    val modelUsed: String = "",       // which SLM generated the analysis
)

// ─────────────────────────────────────────────────────────────
//  DocumentChunk — one 512-token chunk from a document
// ─────────────────────────────────────────────────────────────
@Entity
data class DocumentChunk(
    @Id var id: Long = 0,
    @Index val documentId: Long = 0,
    val chunkIndex: Int = 0,
    val text: String = "",
    val pageNumber: Int = 0,
    // Embedding stored as space-separated floats (ObjectBox doesn't
    // support float arrays natively; Phase 2 migrates to sqlite-vss)
    val embeddingRaw: String = "",
    val tokenCount: Int = 0,
)

// ─────────────────────────────────────────────────────────────
//  ChatMessage — conversation history per document
// ─────────────────────────────────────────────────────────────
@Entity
data class ChatMessage(
    @Id var id: Long = 0,
    @Index val documentId: Long = 0,
    val role: String = "",            // "user" | "assistant"
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val sourceClauses: String = "",   // comma-separated clause refs
    val latencyMs: Long = 0L,
    val tokensUsed: Int = 0,
)

// ─────────────────────────────────────────────────────────────
//  In-memory models (not persisted)
// ─────────────────────────────────────────────────────────────

data class RetrievedChunk(
    val chunk: DocumentChunk,
    val similarity: Float,
)

data class RagContext(
    val chunks: List<RetrievedChunk>,
    val retrievalMs: Long,
)

data class InferenceResult(
    val text: String,
    val tokensGenerated: Int,
    val latencyMs: Long,
    val sourceClauses: List<String>,
)

enum class ProcessingStage(val label: String, val detail: String) {
    PARSING("Parsing PDF", "iText 7 · text + structure extraction"),
    CHUNKING("Chunking text", "512-token windows · 50-token overlap"),
    EMBEDDING("Generating embeddings", "FunctionGemma-270M · on-device"),
    INDEXING("Indexing vectors", "HNSW · ObjectBox local store"),
    LOADING_MODEL("Loading Phi-4 Mini", "ExecuTorch · XNNPack NPU · 4-bit quant"),
    PRE_ANALYSIS("Pre-extracting clauses", "Legal prompt template · structured output"),
    DONE("Complete", ""),
}

sealed class IndexingState {
    object Idle : IndexingState()
    data class Processing(val stage: ProcessingStage, val progress: Float) : IndexingState()
    data class Success(val document: Document) : IndexingState()
    data class Error(val message: String) : IndexingState()
}

sealed class ChatState {
    object Idle : ChatState()
    object Thinking : ChatState()
    data class Error(val message: String) : ChatState()
}

// Device capability tier — drives model selection
enum class DeviceTier { FLAGSHIP, MIDRANGE }
