package com.vakildoot.domain.usecase

import android.net.Uri
import com.vakildoot.data.model.*
import com.vakildoot.data.repository.DocumentRepository
import com.vakildoot.data.repository.PdfParser
import com.vakildoot.ml.inference.LlmInferenceEngine
import com.vakildoot.ml.rag.RagPipeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject

/**
 * IndexDocumentUseCase
 *
 * Orchestrates the full Phase 1 document ingestion pipeline:
 * URI → PDF parse → chunk → embed → store → pre-analyse
 *
 * Emits IndexingState updates for the UI to observe.
 */
class IndexDocumentUseCase @Inject constructor(
    private val pdfParser: PdfParser,
    private val ragPipeline: RagPipeline,
    private val llmEngine: LlmInferenceEngine,
    private val repository: DocumentRepository,
) {
    operator fun invoke(uri: Uri): Flow<IndexingState> = flow {
        emit(IndexingState.Processing(ProcessingStage.PARSING, 0.0f))

        // 1. Parse PDF
        val parsed = pdfParser.parse(uri).getOrElse {
            emit(IndexingState.Error("Could not parse PDF: ${it.message}"))
            return@flow
        }

        emit(IndexingState.Processing(ProcessingStage.CHUNKING, 0.15f))

        // 2. Create document record
        val docId = repository.insertDocument(Document(
            fileName    = parsed.fileName,
            displayName = parsed.fileName.removeSuffix(".pdf"),
            pageCount   = parsed.pageCount,
            fileSize    = parsed.fileSizeBytes,
            modelUsed   = llmEngine.getDeviceTier().name,
        ))

        emit(IndexingState.Processing(ProcessingStage.EMBEDDING, 0.25f))

        // 3. Chunk + embed + index (with progress)
        ragPipeline.indexDocument(
            documentId = docId,
            fullText   = parsed.fullText,
            onProgress = { done, total ->
                val p = 0.25f + (done.toFloat() / total.coerceAtLeast(1)) * 0.45f
                // Not emitting every chunk update — would flood the UI
                Timber.v("Indexed $done/$total chunks")
            }
        ).getOrElse {
            emit(IndexingState.Error("Indexing failed: ${it.message}"))
            return@flow
        }

        emit(IndexingState.Processing(ProcessingStage.LOADING_MODEL, 0.75f))

        // 4. Ensure LLM is loaded (downloads ~2GB first time — handled elsewhere)
        llmEngine.ensureLoaded().getOrElse {
            emit(IndexingState.Error("Failed to load AI model: ${it.message}"))
            return@flow
        }

        emit(IndexingState.Processing(ProcessingStage.PRE_ANALYSIS, 0.90f))

        // 5. Pre-extract summary using LLM
        val chunkCount  = repository.getChunkCount(docId)
        val summaryResult = llmEngine.infer(
            query      = "Provide a 3-sentence summary of this legal document and list the top 3 risks.",
            ragContext = parsed.fullText.take(2000), // First 2000 chars for summary
        )
        val summary = summaryResult.getOrNull()?.text ?: ""

        // 6. Update document with final metadata
        val finalDoc = Document(
            id          = docId,
            fileName    = parsed.fileName,
            displayName = parsed.fileName.removeSuffix(".pdf"),
            pageCount   = parsed.pageCount,
            fileSize    = parsed.fileSizeBytes,
            chunkCount  = chunkCount,
            isIndexed   = true,
            indexedAt   = System.currentTimeMillis(),
            summary     = summary,
            modelUsed   = llmEngine.getDeviceTier().name,
        )
        repository.updateDocument(finalDoc)

        Timber.i("Document indexed: ${parsed.fileName} | $chunkCount chunks")
        emit(IndexingState.Processing(ProcessingStage.DONE, 1.0f))
        emit(IndexingState.Success(finalDoc))
    }
}

/**
 * QueryDocumentUseCase
 *
 * Handles a single chat turn:
 * question → RAG retrieval → LLM inference → ChatMessage
 */
class QueryDocumentUseCase @Inject constructor(
    private val ragPipeline: RagPipeline,
    private val llmEngine: LlmInferenceEngine,
    private val repository: DocumentRepository,
) {
    suspend operator fun invoke(
        documentId: Long,
        query: String,
        onToken: ((String) -> Unit)? = null,
    ): Result<ChatMessage> {
        val startMs = System.currentTimeMillis()

        // 1. Retrieve relevant chunks
        val ragContext = ragPipeline.retrieve(documentId, query).getOrElse {
            return Result.failure(it)
        }
        val contextText = ragPipeline.formatContext(ragContext)

        // 2. LLM inference with injected context
        val result = llmEngine.infer(
            query      = query,
            ragContext = contextText,
            onToken    = onToken,
        ).getOrElse {
            return Result.failure(it)
        }

        // 3. Persist message pair
        repository.insertMessage(ChatMessage(
            documentId    = documentId,
            role          = "user",
            content       = query,
        ))
        val assistantMsg = ChatMessage(
            documentId    = documentId,
            role          = "assistant",
            content       = result.text,
            sourceClauses = result.sourceClauses.joinToString(","),
            latencyMs     = System.currentTimeMillis() - startMs,
            tokensUsed    = result.tokensGenerated,
        )
        repository.insertMessage(assistantMsg)

        return Result.success(assistantMsg)
    }
}
