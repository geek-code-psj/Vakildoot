package com.vakildoot.domain.usecase

import android.net.Uri
import com.vakildoot.data.model.*
import com.vakildoot.data.repository.DocumentRepository
import com.vakildoot.data.repository.ParsedPdf
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
    private val structureAnalyzer: StructureAnalyzerUseCase,
) {
    operator fun invoke(uri: Uri): Flow<IndexingState> = flow {
        emit(IndexingState.Processing(ProcessingStage.PARSING, 0.0f))

        // 1. Parse PDF
        val parsed = pdfParser.parse(uri).getOrElse {
            emit(IndexingState.Error("Could not parse PDF: ${it.message}"))
            return@flow
        }

        emit(IndexingState.Processing(ProcessingStage.CHUNKING, 0.15f))

        // 1a. Analyze document structure (clauses, tables, sections)
        val structure = structureAnalyzer(parsed.fullText, parsed.pageTexts)
        emit(IndexingState.Processing(ProcessingStage.CHUNKING, 0.18f))

        // 2. Create document record
        val docId = repository.insertDocument(Document(
            fileName    = parsed.fileName,
            displayName = parsed.fileName.removeSuffix(".pdf"),
            pageCount   = parsed.pageCount,
            fileSize    = parsed.fileSizeBytes,
            modelUsed   = llmEngine.deviceTier.name,
            clauses     = structure.clauses.joinToString(","),
            hasTableContent = structure.tables.isNotEmpty(),
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

        // 5. Build grounded summary directly from indexed document content
        val chunkCount  = repository.getChunkCount(docId)
        val summary = buildGroundedSummary(docId, parsed)

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
            modelUsed   = llmEngine.deviceTier.name,
            clauses     = structure.clauses.joinToString(","),
            hasTableContent = structure.tables.isNotEmpty(),
        )
        repository.updateDocument(finalDoc)

        Timber.i("Document indexed: ${parsed.fileName} | $chunkCount chunks")
        emit(IndexingState.Processing(ProcessingStage.DONE, 1.0f))
        emit(IndexingState.Success(finalDoc))
    }

    private suspend fun buildGroundedSummary(documentId: Long, parsed: ParsedPdf): String {
        return try {
            val context = ragPipeline.retrieve(
                documentId = documentId,
                query = "Summarize key legal obligations, penalties, and scope from this document."
            ).getOrNull()

            val retrievedChunks = context?.chunks?.map { it.chunk } ?: emptyList()
            val fallbackChunks = if (retrievedChunks.isNotEmpty()) {
                retrievedChunks
            } else {
                repository.getChunksForDocument(documentId).take(4)
            }

            val highlights = fallbackChunks
                .mapNotNull { chunk ->
                    val sentence = firstUsefulSentence(chunk.text)
                    if (sentence.isBlank()) null else "• [Page ${chunk.pageNumber}] $sentence"
                }
                .distinct()
                .take(3)

            val topTerms = topLegalTerms(parsed.fullText, limit = 6)
            val termLine = if (topTerms.isNotEmpty()) {
                "\n\nKey legal terms: ${topTerms.joinToString(", ")}"
            } else {
                ""
            }

            return when {
                highlights.isNotEmpty() -> {
                    "Summary from document analysis:\n" + highlights.joinToString("\n") + termLine
                }
                parsed.pageTexts.isNotEmpty() && parsed.pageTexts.any { it.isNotBlank() } -> {
                    val fallback = parsed.pageTexts
                        .asSequence()
                        .map { firstUsefulSentence(it) }
                        .firstOrNull { it.isNotBlank() }
                        ?: "Document contains legal text but detailed summary unavailable."
                    "Summary from extracted text:\n• $fallback$termLine"
                }
                else -> "Document received. Summary will be generated after content analysis."
            }
        } catch (e: Exception) {
            Timber.w(e, "Summary generation failed")
            "Document indexed. Summary unavailable in this session."
        }
    }

    private fun firstUsefulSentence(text: String): String {
        val normalized = text
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return ""

        val sentences = normalized.split(Regex("(?<=[.!?])\\s+"))
        return sentences.firstOrNull {
            val lower = it.lowercase()
            it.length >= 40 && (
                lower.contains("section") ||
                lower.contains("offence") ||
                lower.contains("offense") ||
                lower.contains("punishment") ||
                lower.contains("imprisonment") ||
                lower.contains("liable") ||
                lower.contains("shall")
            )
        }?.take(260) ?: sentences.first().take(260)
    }

    private fun topLegalTerms(text: String, limit: Int): List<String> {
        val stop = setOf(
            "the", "and", "for", "with", "that", "this", "from", "shall", "under", "which",
            "have", "has", "are", "was", "were", "into", "their", "there", "been", "than",
            "section", "chapter", "page", "bns", "ipc"
        )

        return Regex("[A-Za-z]{4,}")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it !in stop }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
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
    private val citationValidator: CitationValidatorUseCase,
) {
    suspend operator fun invoke(
        documentId: Long,
        query: String,
        onToken: ((String) -> Unit)? = null,
    ): Result<ChatMessage> {
        val startMs = System.currentTimeMillis()

        // 1. Retrieve relevant chunks
        val ragContext = ragPipeline.retrieve(documentId, query).getOrElse {
            Timber.w(it, "RAG retrieval failed for doc=$documentId; continuing with empty context")
            RagContext(chunks = emptyList(), retrievalMs = 0L)
        }
        val contextText = ragPipeline.formatContext(ragContext)

        // 2. Get document for clause validation
        val doc = repository.getDocument(documentId)
        val validClauses = doc?.clauses?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val requestedSection = extractRequestedSection(query)
        val sectionPresentInContext = requestedSection?.let { section ->
            ragContext.chunks.any { rc ->
                Regex("""(?:Section|Sec\.)\s+${Regex.escape(section)}\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(rc.chunk.text)
            }
        } ?: true
        val sectionPresentInIndex = requestedSection?.let { it in validClauses } ?: true
        val preLlmWarning = if (!sectionPresentInContext || !sectionPresentInIndex) {
            "Note: Section ${requestedSection ?: "requested"} is not currently in the local high-precision index context. " +
                "Answer may rely on general model knowledge."
        } else {
            ""
        }

        // 3. LLM inference with injected context
        val result = llmEngine.infer(
            query      = query,
            ragContext = if (preLlmWarning.isBlank()) contextText else "$preLlmWarning\n\n$contextText",
            onToken    = onToken,
        ).getOrElse {
            return Result.failure(it)
        }

        // 4. Validate citations
        val validatedResponse = citationValidator(
            llmResponse = result.text,
            validClausesInDocument = validClauses,
            retrievedContext = contextText,
        )

        // 5. Persist message pair with validation results
        repository.insertMessage(ChatMessage(
            documentId    = documentId,
            role          = "user",
            content       = query,
        ))
        val assistantMsg = ChatMessage(
            documentId    = documentId,
            role          = "assistant",
            content       = if (preLlmWarning.isBlank()) validatedResponse.validatedText else "$preLlmWarning\n\n${validatedResponse.validatedText}",
            sourceClauses = result.sourceClauses.joinToString(","),
            latencyMs     = System.currentTimeMillis() - startMs,
            tokensUsed    = result.tokensGenerated,
            sourceValidated = validatedResponse.hallucinated.isEmpty() && validatedResponse.notInContext.isEmpty(),
            confidenceScore = validatedResponse.confidenceScore,
        )
        repository.insertMessage(assistantMsg)

        Timber.i("Query validated: ${validatedResponse.verifiedClauses.size} verified, ${validatedResponse.hallucinated.size} hallucinated")

        return Result.success(assistantMsg)
    }

    private fun extractRequestedSection(query: String): String? {
        val sectionMatch = Regex("""(?:section|sec\.)\s+(\d+[A-Za-z-]*)""", RegexOption.IGNORE_CASE).find(query)
        if (sectionMatch != null) return sectionMatch.groupValues[1]

        val clauseMatch = Regex("""(?:clause|cl\.)\s+(\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE).find(query)
        return clauseMatch?.groupValues?.get(1)
    }
}
