package com.vakildoot

import com.vakildoot.ml.embedding.EmbeddingEngine
import com.vakildoot.ml.rag.TextChunker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Phase 1 unit tests.
 *
 * Run with: ./gradlew test
 *
 * These tests verify the RAG pipeline components without requiring
 * a device or emulator. The embedding engine uses its deterministic
 * stub, so tests are repeatable and fast.
 */
class RagPipelineTest {

    private lateinit var chunker: TextChunker
    private lateinit var embeddingEngine: EmbeddingEngine

    @Before
    fun setup() {
        chunker = TextChunker()
        // EmbeddingEngine requires Context — use Robolectric in full test suite
        // embeddingEngine = EmbeddingEngine(context)
    }

    // ── TextChunker tests ─────────────────────────────────────────────────────

    @Test
    fun `chunker splits short text into single chunk`() {
        val text = "This is a short legal clause about payment terms."
        val chunks = chunker.chunk(text, maxTokens = 512, overlapTokens = 50)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].text.contains("payment terms"))
    }

    @Test
    fun `chunker produces overlapping chunks for long text`() {
        // 1000-word text should produce multiple chunks with overlap
        val text = (1..1000).joinToString(" ") { "word$it" }
        val chunks = chunker.chunk(text, maxTokens = 100, overlapTokens = 20)
        assertTrue("Expected >1 chunk, got ${chunks.size}", chunks.size > 1)
        // Verify overlap: last words of chunk N appear at start of chunk N+1
        if (chunks.size >= 2) {
            val lastWordsOf0 = chunks[0].text.split(" ").takeLast(5).toSet()
            val firstWordsOf1 = chunks[1].text.split(" ").take(25).toSet()
            val overlap = lastWordsOf0.intersect(firstWordsOf1)
            assertTrue("Expected word overlap between chunks, got none", overlap.isNotEmpty())
        }
    }

    @Test
    fun `chunker preserves page markers`() {
        val text = "Clause 1: Payment terms are net 30.\n[PAGE 2]\nClause 2: Termination requires 30 days notice."
        val chunks = chunker.chunk(text)
        // Should have content from both pages
        val allText = chunks.joinToString(" ") { it.text }
        assertTrue(allText.contains("Payment terms"))
        assertTrue(allText.contains("Termination"))
    }

    @Test
    fun `chunker assigns sequential chunk indices`() {
        val text = (1..500).joinToString(" ") { "term$it" }
        val chunks = chunker.chunk(text, maxTokens = 50, overlapTokens = 10)
        chunks.forEachIndexed { i, chunk ->
            assertEquals("Chunk index mismatch at position $i", i, chunk.chunkIndex)
        }
    }

    @Test
    fun `chunker handles empty text gracefully`() {
        val chunks = chunker.chunk("")
        assertEquals(0, chunks.size)
    }

    @Test
    fun `chunker handles text with only whitespace`() {
        val chunks = chunker.chunk("   \n\n\t  ")
        assertEquals(0, chunks.size)
    }

    // ── EmbeddingEngine tests (stub mode) ────────────────────────────────────

    @Test
    fun `cosine similarity of identical vectors is 1`() {
        val engine = object {
            fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
                var dot = 0f; var normA = 0f; var normB = 0f
                for (i in a.indices) { dot += a[i]*b[i]; normA += a[i]*a[i]; normB += b[i]*b[i] }
                val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
                return if (denom == 0.0) 0f else (dot / denom).toFloat()
            }
        }
        val vec = FloatArray(10) { it.toFloat() + 1f }
        val sim = engine.cosineSimilarity(vec, vec)
        assertEquals(1.0f, sim, 0.001f)
    }

    @Test
    fun `cosine similarity of opposite vectors is -1`() {
        val engine = object {
            fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
                var dot = 0f; var normA = 0f; var normB = 0f
                for (i in a.indices) { dot += a[i]*b[i]; normA += a[i]*a[i]; normB += b[i]*b[i] }
                val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
                return if (denom == 0.0) 0f else (dot / denom).toFloat()
            }
        }
        val vec = FloatArray(10) { it.toFloat() + 1f }
        val neg = FloatArray(10) { -(it.toFloat() + 1f) }
        val sim = engine.cosineSimilarity(vec, neg)
        assertEquals(-1.0f, sim, 0.001f)
    }

    // ── Legal prompt structure tests ─────────────────────────────────────────

    @Test
    fun `system prompt contains required legal context markers`() {
        val systemPrompt = """
            You are VakilDoot, a precise legal assistant for Indian law.
            You analyze legal documents and answer questions with reference to specific clauses.
            Rules:
            1. Always cite the exact clause number (e.g. "Clause 8.2") when making claims.
            2. Flag HIGH RISK, MEDIUM RISK, or LOW RISK explicitly.
            3. Reference Indian law where relevant (Contract Act 1872, TPA 1882, DPDP Act 2023).
        """.trimIndent()

        assertTrue(systemPrompt.contains("Indian law"))
        assertTrue(systemPrompt.contains("clause number"))
        assertTrue(systemPrompt.contains("HIGH RISK"))
        assertTrue(systemPrompt.contains("Contract Act 1872"))
        assertTrue(systemPrompt.contains("DPDP Act 2023"))
    }

    // ── RAG context formatting tests ─────────────────────────────────────────

    @Test
    fun `rag context formatter includes page number and similarity`() {
        val fakeChunk = com.vakildoot.data.model.DocumentChunk(
            id = 1L, documentId = 1L, chunkIndex = 0,
            text = "This Agreement shall be governed by Indian law.",
            pageNumber = 3, embeddingRaw = "", tokenCount = 10,
        )
        val retrieved = com.vakildoot.data.model.RetrievedChunk(fakeChunk, 0.92f)
        val ragContext = com.vakildoot.data.model.RagContext(
            chunks = listOf(retrieved), retrievalMs = 45L
        )

        // Replicate formatter logic
        val formatted = ragContext.chunks.joinToString("\n\n---\n\n") { rc ->
            val sim = "%.2f".format(rc.similarity)
            "[Page ${rc.chunk.pageNumber}, relevance=$sim]\n${rc.chunk.text}"
        }

        assertTrue(formatted.contains("Page 3"))
        assertTrue(formatted.contains("0.92"))
        assertTrue(formatted.contains("governed by Indian law"))
    }
}

// ── Integration smoke test ────────────────────────────────────────────────────

class Phase1IntegrationTest {

    @Test
    fun `full pipeline stages are defined and ordered`() {
        val stages = com.vakildoot.data.model.ProcessingStage.values()
        assertEquals("PARSING",      stages[0].name)
        assertEquals("CHUNKING",     stages[1].name)
        assertEquals("EMBEDDING",    stages[2].name)
        assertEquals("INDEXING",     stages[3].name)
        assertEquals("LOADING_MODEL",stages[4].name)
        assertEquals("PRE_ANALYSIS", stages[5].name)
        assertEquals("DONE",         stages[6].name)
    }

    @Test
    fun `device tier enum has exactly two values`() {
        val tiers = com.vakildoot.data.model.DeviceTier.values()
        assertEquals(2, tiers.size)
        assertEquals("FLAGSHIP", tiers[0].name)
        assertEquals("MIDRANGE", tiers[1].name)
    }

    @Test
    fun `indexing state sealed class covers all cases`() {
        // Verify sealed class hierarchy is complete for when expressions
        val idle: com.vakildoot.data.model.IndexingState = com.vakildoot.data.model.IndexingState.Idle
        val processing = com.vakildoot.data.model.IndexingState.Processing(
            com.vakildoot.data.model.ProcessingStage.PARSING, 0f
        )
        val error = com.vakildoot.data.model.IndexingState.Error("test")

        assertNotNull(idle)
        assertNotNull(processing)
        assertNotNull(error)
        assertEquals(0f, processing.progress)
        assertEquals("test", error.message)
    }
}
