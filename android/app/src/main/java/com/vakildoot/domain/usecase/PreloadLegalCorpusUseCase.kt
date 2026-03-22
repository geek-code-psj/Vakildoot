package com.vakildoot.domain.usecase

import android.content.Context
import com.vakildoot.data.model.Document
import com.vakildoot.data.model.DocumentChunk
import com.vakildoot.data.repository.DocumentRepository
import com.vakildoot.ml.embedding.EmbeddingEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

private data class ParsedLegalChunk(
    val chunkText: String,
    val pageNumber: Int,
    val tokenCount: Int,
    val sectionNumber: String,
)

private sealed class ParsedLineResult {
    data class Chunk(val value: ParsedLegalChunk) : ParsedLineResult()
    data class Metadata(val schemaVersion: String) : ParsedLineResult()
    object Skip : ParsedLineResult()
}

data class PreloadLegalCorpusResult(
    val documentId: Long,
    val insertedChunks: Int,
    val skippedLines: Int,
)

class PreloadLegalCorpusUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DocumentRepository,
    private val embeddingEngine: EmbeddingEngine,
) {
    companion object {
        private const val BUILTIN_FILE_NAME = "bns_manifest.jsonl"
        private const val BUILTIN_DISPLAY_NAME = "BNS 2023 (Preloaded)"
        private val ASSET_PATH_CANDIDATES = listOf(
            "legal/bns_manifest.jsonl",
            "legal/bns_chunks.jsonl",
        )
    }

    private var didAttemptPreload = false

    suspend operator fun invoke(): Result<PreloadLegalCorpusResult?> = withContext(Dispatchers.IO) {
        if (didAttemptPreload) {
            return@withContext Result.success(null)
        }
        didAttemptPreload = true

        runCatching {
            val parsedChunks = mutableListOf<ParsedLegalChunk>()
            val corpusSections = linkedSetOf<String>()
            var skippedLines = 0
            var corpusSchemaVersion = "1.0.0-legacy"
            val selectedAssetPath = ASSET_PATH_CANDIDATES.firstOrNull { path ->
                runCatching {
                    context.assets.open(path).close()
                    true
                }.getOrDefault(false)
            } ?: run {
                Timber.i("No preloaded legal corpus asset found in candidates: $ASSET_PATH_CANDIDATES")
                return@runCatching null
            }
            val assetFilePath = "asset://$selectedAssetPath"

            var existing: Document? = null
            for (path in ASSET_PATH_CANDIDATES) {
                val candidate = repository.findDocumentByFilePath("asset://$path")
                if (candidate?.isIndexed == true) {
                    existing = candidate
                    break
                }
            }
            if (existing != null && existing.isIndexed) {
                Timber.i("Bundled corpus already preloaded as document ${existing.id}")
                return@runCatching PreloadLegalCorpusResult(
                    documentId = existing.id,
                    insertedChunks = 0,
                    skippedLines = 0,
                )
            }

            val input = context.assets.open(selectedAssetPath)

            input.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    when (val parsed = parseChunkLine(line)) {
                        is ParsedLineResult.Chunk -> {
                            parsedChunks += parsed.value
                            if (parsed.value.sectionNumber.isNotBlank()) {
                                corpusSections += parsed.value.sectionNumber
                            }
                        }
                        is ParsedLineResult.Metadata -> {
                            corpusSchemaVersion = parsed.schemaVersion
                        }
                        ParsedLineResult.Skip -> {
                            skippedLines += 1
                        }
                    }
                }
            }

            if (parsedChunks.isEmpty()) {
                Timber.w("Preload corpus found but no valid chunks parsed")
                return@runCatching null
            }

            embeddingEngine.ensureLoaded().getOrThrow()

            val docId = repository.insertDocument(
                Document(
                    fileName = BUILTIN_FILE_NAME,
                    displayName = BUILTIN_DISPLAY_NAME,
                    filePath = assetFilePath,
                    pageCount = 0,
                    chunkCount = 0,
                    isIndexed = false,
                    summary = "Bundled statutory corpus for BNS retrieval.",
                    clauses = corpusSections.joinToString(","),
                )
            )

            var inserted = 0
            var chunkIndex = 0
            for (batch in parsedChunks.chunked(EmbeddingEngine.BATCH_SIZE)) {
                val embeddings = embeddingEngine.embedBatch(batch.map { it.chunkText })
                val entities = batch.zip(embeddings).mapNotNull { (chunk, embedding) ->
                    if (embedding == null) {
                        skippedLines += 1
                        return@mapNotNull null
                    }
                    val entity = DocumentChunk(
                        documentId = docId,
                        chunkIndex = chunkIndex,
                        text = chunk.chunkText,
                        pageNumber = chunk.pageNumber,
                        embeddingRaw = embeddingEngine.serialise(embedding),
                        tokenCount = chunk.tokenCount,
                        clauseNumber = chunk.sectionNumber,
                    )
                    chunkIndex += 1
                    entity
                }
                repository.insertChunks(entities)
                inserted += entities.size
            }

            if (inserted == 0) {
                repository.deleteDocument(docId)
                Timber.w("Preloaded corpus had zero embeddable chunks, document removed")
                return@runCatching null
            }

            repository.updateDocument(
                Document(
                    id = docId,
                    fileName = BUILTIN_FILE_NAME,
                    displayName = BUILTIN_DISPLAY_NAME,
                    filePath = assetFilePath,
                    pageCount = 0,
                    chunkCount = inserted,
                    isIndexed = true,
                    indexedAt = System.currentTimeMillis(),
                    summary = "Bundled statutory corpus for BNS retrieval.",
                    clauses = corpusSections.joinToString(","),
                )
            )

            Timber.i(
                "Preloaded corpus schema=$corpusSchemaVersion, sections=${corpusSections.size}, inserted=$inserted"
            )

            PreloadLegalCorpusResult(
                documentId = docId,
                insertedChunks = inserted,
                skippedLines = skippedLines,
            )
        }.onFailure { error ->
            Timber.e(error, "Failed to preload legal corpus")
        }
    }

    private fun parseChunkLine(line: String): ParsedLineResult {
        return try {
            val json = JSONObject(line)
            if (json.has("_meta") || json.has("schema_version")) {
                val version = if (json.has("_meta")) {
                    json.optJSONObject("_meta")?.optString("version", "1.0.0-legacy") ?: "1.0.0-legacy"
                } else {
                    json.optString("schema_version", "1.0.0-legacy")
                }
                return ParsedLineResult.Metadata(version)
            }

            val sectionNumber = parseSectionNumber(json)
            val text = buildChunkText(json, sectionNumber)
            if (text.isBlank()) return ParsedLineResult.Skip

            val hasLegacyLegalityScore = json.has("legality_score")
            val legalityScore = json.optDouble("legality_score", 0.0)
            // Skip weak legal chunks that likely represent summaries or TOC leftovers.
            if (hasLegacyLegalityScore && legalityScore in 0.0..0.34 && sectionNumber.isBlank()) {
                return ParsedLineResult.Skip
            }

            val pageStart = when {
                json.has("page_start") -> json.optInt("page_start", 1)
                json.optJSONObject("metadata")?.has("page_start") == true ->
                    json.optJSONObject("metadata")?.optInt("page_start", 1) ?: 1
                else -> 1
            }
            val tokenCount = json.optInt("token_count", text.split(Regex("\\s+")).size)

            ParsedLineResult.Chunk(
                ParsedLegalChunk(
                chunkText = text,
                pageNumber = pageStart,
                tokenCount = tokenCount,
                sectionNumber = sectionNumber,
                )
            )
        } catch (_: Exception) {
            ParsedLineResult.Skip
        }
    }

    private fun parseSectionNumber(json: JSONObject): String {
        val explicit = json.optString("section_number", "").trim()
        if (explicit.isNotBlank()) return explicit

        val metadataSection = json.optJSONObject("metadata")?.optString("section", "")?.trim().orEmpty()
        if (metadataSection.isNotBlank()) return metadataSection

        val idValue = json.optString("id", "")
        val idMatch = Regex("""BNS_SEC_(\d+)""").find(idValue)
        return idMatch?.groupValues?.get(1).orEmpty()
    }

    private fun buildChunkText(json: JSONObject, sectionNumber: String): String {
        val directText = json.optString("text", "").trim()
        if (directText.isNotBlank()) {
            return if (sectionNumber.isNotBlank() && !directText.startsWith("Section", ignoreCase = true)) {
                "Section $sectionNumber: $directText"
            } else {
                directText
            }
        }

        val title = json.optString("title", "").trim()
        val fairUseSummary = json.optString("fair_use_summary", "").trim()
        val punishmentSummary = json.optString("punishment_summary", "").trim()
        val officialReference = json.optString("official_reference_link", "").trim()
        val severityTier = if (json.has("severity_tier")) json.optInt("severity_tier", -1) else -1

        val parts = mutableListOf<String>()
        if (sectionNumber.isNotBlank() || title.isNotBlank()) {
            val heading = buildString {
                if (sectionNumber.isNotBlank()) append("Section ").append(sectionNumber)
                if (title.isNotBlank()) {
                    if (isNotBlank()) append(". ")
                    append(title)
                }
            }.trim()
            if (heading.isNotBlank()) parts += heading
        }
        if (fairUseSummary.isNotBlank()) parts += fairUseSummary
        if (punishmentSummary.isNotBlank()) parts += "Punishment: $punishmentSummary"
        if (severityTier >= 0) parts += "Severity Tier: $severityTier"
        if (officialReference.isNotBlank()) parts += "Official Source: $officialReference"

        return parts.joinToString(separator = "\n").trim()
    }
}

