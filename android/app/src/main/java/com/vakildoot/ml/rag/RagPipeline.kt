package com.vakildoot.ml.rag

import com.vakildoot.BuildConfig
import com.vakildoot.data.model.DocumentChunk
import com.vakildoot.data.model.RagContext
import com.vakildoot.data.model.RetrievedChunk
import com.vakildoot.data.repository.DocumentRepository
import com.vakildoot.ml.embedding.EmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RagPipeline
 *
 * The core local Retrieval-Augmented Generation engine.
 * Connects document chunks to the LLM via semantic search.
 *
 * Pipeline:
 *  INDEXING:  PDF text → chunks → embeddings → ObjectBox vector store
 *  RETRIEVAL: query → embedding → cosine similarity → top-K chunks → LLM context
 *
 * All operations run on-device. Zero network calls.
 */
@Singleton
class RagPipeline @Inject constructor(
    private val embeddingEngine: EmbeddingEngine,
    private val documentRepository: DocumentRepository,
    private val textChunker: TextChunker,
) {
    companion object {
        val TOP_K          = BuildConfig.RAG_TOP_K          // 5
        val CHUNK_SIZE     = BuildConfig.CHUNK_SIZE_TOKENS   // 512
        val CHUNK_OVERLAP  = BuildConfig.CHUNK_OVERLAP_TOKENS // 50
        const val MIN_SIMILARITY = 0.25f  // discard chunks below this threshold
    }

    // ── INDEXING ────────────────────────────────────────────────────────────

    /**
     * Index all text from a document into the vector store.
     * Called once per document after PDF parsing.
     *
     * @param documentId  DB id of the parent Document
     * @param fullText    All text extracted from the PDF (concatenated pages)
     * @param onProgress  Callback: (chunksProcessed, totalChunks) → Unit
     */
    suspend fun indexDocument(
        documentId: Long,
        fullText: String,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Result<Int> = withContext(Dispatchers.Default) {
        embeddingEngine.ensureLoaded().getOrElse { return@withContext Result.failure(it) }

        // 1. Chunk the full text
        val chunks = textChunker.chunk(fullText, CHUNK_SIZE, CHUNK_OVERLAP)
        Timber.d("Chunking complete: ${chunks.size} chunks from ${fullText.length} chars")
        onProgress?.invoke(0, chunks.size)

        // 2. Embed and store in batches
        var processed = 0
        for (batch in chunks.chunked(EmbeddingEngine.BATCH_SIZE)) {
            val texts      = batch.map { it.text }
            val embeddings = withContext(Dispatchers.Default) {
                embeddingEngine.embedBatch(texts)
            }

            val entities = batch.zip(embeddings).mapIndexedNotNull { _, (chunk, emb) ->
                if (emb == null) {
                    Timber.w("Embedding failed for chunk ${chunk.chunkIndex}, skipping")
                    null
                } else {
                    val clauseNumber = extractClauseNumber(chunk.text)
                    DocumentChunk(
                        documentId   = documentId,
                        chunkIndex   = chunk.chunkIndex,
                        text         = chunk.text,
                        pageNumber   = chunk.pageNumber,
                        embeddingRaw = embeddingEngine.serialise(emb),
                        tokenCount   = chunk.tokenCount,
                        clauseNumber = clauseNumber,
                        isTableContent = looksLikeTableChunk(chunk.text),
                    )
                }
            }
            withContext(Dispatchers.IO) {
                documentRepository.insertChunks(entities)
            }
            processed += entities.size
            onProgress?.invoke(processed, chunks.size)
        }

        Timber.i("Indexing complete: $processed/${chunks.size} chunks stored for doc $documentId")
        Result.success(processed)
    }

    // ── RETRIEVAL ───────────────────────────────────────────────────────────

    /**
     * Retrieve the TOP_K most semantically similar chunks for a query.
     * Uses cosine similarity over all chunks for the given document.
     *
     * Phase 2 upgrade: replace with HNSW approximate nearest neighbour
     * via sqlite-vss for sub-millisecond search at scale.
     */
    suspend fun retrieve(
        documentId: Long,
        query: String,
    ): Result<RagContext> = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()

        // 1. Embed the query
        val queryEmbedding = embeddingEngine.embed(query)
            .getOrElse { return@withContext Result.failure(it) }

        // 2. Load all chunks for this document
        val chunks = documentRepository.getChunksForDocument(documentId)
        if (chunks.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No chunks found for doc $documentId"))
        }

        // 3. Cosine similarity over all chunks (brute-force for Phase 1 MVP)
        //    Phase 2: HNSW with sqlite-vss, ~1ms for 10K chunks
        val scored = chunks.mapNotNull { chunk ->
            if (chunk.embeddingRaw.isBlank()) return@mapNotNull null
            val chunkEmb = runCatching { embeddingEngine.deserialise(chunk.embeddingRaw) }
                .onFailure { Timber.w(it, "Skipping malformed embedding for chunk ${chunk.chunkIndex}") }
                .getOrNull() ?: return@mapNotNull null

            if (chunkEmb.size != queryEmbedding.size) {
                Timber.w("Skipping chunk ${chunk.chunkIndex}: embedding dim mismatch ${chunkEmb.size} vs ${queryEmbedding.size}")
                return@mapNotNull null
            }

            val sim = runCatching { embeddingEngine.cosineSimilarity(queryEmbedding, chunkEmb) }
                .onFailure { Timber.w(it, "Similarity failed for chunk ${chunk.chunkIndex}") }
                .getOrNull() ?: return@mapNotNull null

            if (sim >= MIN_SIMILARITY) RetrievedChunk(chunk, sim) else null
        }.sortedByDescending { it.similarity }.take(TOP_K)

        val latencyMs = System.currentTimeMillis() - startMs
        Timber.d("Retrieved ${scored.size} chunks in ${latencyMs}ms (top sim: ${scored.firstOrNull()?.similarity?.let { "%.3f".format(it) } ?: "n/a"})")

        Result.success(RagContext(chunks = scored, retrievalMs = latencyMs))
    }

    private fun extractClauseNumber(text: String): String {
        val section = Regex("""(?:Section|Sec\.)\s+(\d+[A-Za-z-]*)""", RegexOption.IGNORE_CASE).find(text)
        if (section != null) return section.groupValues[1]

        val clause = Regex("""(?:Clause|Cl\.)\s+(\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE).find(text)
        return clause?.groupValues?.get(1) ?: ""
    }

    private fun looksLikeTableChunk(text: String): Boolean {
        val lineCount = text.split("\n").size
        if (lineCount < 2) return false
        val pipeCount = text.count { it == '|' }
        val multiSpaceRows = Regex("""\s{2,}\S+\s{2,}""").findAll(text).count()
        return pipeCount >= 2 || multiSpaceRows >= 2
    }

    /**
     * Format retrieved chunks into a context string to inject into the LLM prompt.
     */
    fun formatContext(ragContext: RagContext): String {
        if (ragContext.chunks.isEmpty()) return ""
        return ragContext.chunks.joinToString("\n\n---\n\n") { rc ->
            val sim = "%.2f".format(rc.similarity)
            "[Page ${rc.chunk.pageNumber}, relevance=$sim]\n${rc.chunk.text}"
        }
    }
}

// ── TextChunker ──────────────────────────────────────────────────────────────

/**
 * Splits raw PDF text into overlapping token windows.
 *
 * Strategy: word-based approximation (1 token ≈ 0.75 words) with
 * sentence boundary awareness to avoid splitting mid-clause.
 */
@Singleton
class TextChunker @Inject constructor() {

    data class RawChunk(
        val chunkIndex: Int,
        val text: String,
        val pageNumber: Int,
        val tokenCount: Int,
    )

    fun chunk(
        text: String,
        maxTokens: Int  = 512,
        overlapTokens: Int = 50,
        pageBreakMarker: String = "\n[PAGE ",
    ): List<RawChunk> {
        // Split text by page markers (iText inserts these)
        val pages = text.split(pageBreakMarker)
        val result = mutableListOf<RawChunk>()

        // Approximate words per chunk (1 token ≈ 0.75 words for English legal text)
        val wordsPerChunk   = (maxTokens   * 0.75).toInt()
        val overlapWords    = (overlapTokens * 0.75).toInt()

        var chunkIndex = 0
        var overlapBuffer = mutableListOf<String>()

        pages.forEachIndexed { pageIdx, pageText ->
            // Split at sentence boundaries where possible
            val sentences = pageText.replace(Regex("([.!?])\\s+"), "$1\u0000")
                .split("\u0000").filter { it.isNotBlank() }

            val words = sentences.flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() }
            var i = 0

            while (i < words.size) {
                val chunkWords = (overlapBuffer + words.subList(i, minOf(i + wordsPerChunk, words.size)))
                val chunkText  = chunkWords.joinToString(" ")
                val tokenEst   = (chunkText.length / 4).coerceAtLeast(1)

                result.add(RawChunk(
                    chunkIndex = chunkIndex++,
                    text       = chunkText,
                    pageNumber = pageIdx + 1,
                    tokenCount = tokenEst,
                ))

                overlapBuffer = chunkWords.takeLast(overlapWords).toMutableList()
                i += wordsPerChunk
            }
        }

        Timber.d("TextChunker: ${text.length} chars → ${result.size} chunks")
        return result
    }
}
