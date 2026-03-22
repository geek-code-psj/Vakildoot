package com.vakildoot.data.repository

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class ParsedPdf(
    val fileName: String,
    val pageCount: Int,
    val fullText: String,      // All pages concatenated with [PAGE N] markers
    val pageTexts: List<String>, // Per-page text for reference
    val fileSizeBytes: Long,
)

/**
 * PdfParser
 *
 * Uses Apache PDFBox (Apache 2.0) to extract text from legal PDFs entirely on-device.
 * Handles:
 *   - Standard text PDFs (contracts, NDAs, deeds)
 *   - Multi-column layouts (legal gazette pages)
 *   - Tables (payment schedules, fee structures)
 *
 * Does NOT handle scanned/image PDFs — those require OCR (Phase 3: Bhashini OCR).
 *
 * Apache PDFBox is Apache 2.0 licensed — free for commercial use.
 */
@Singleton
class PdfParser @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val PAGE_MARKER = "\n[PAGE "
        const val MAX_PDF_SIZE_MB = 50
    }

    /**
     * Parse a PDF from a content URI (file picker result or share intent).
     */
    suspend fun parse(uri: Uri): Result<ParsedPdf> = withContext(Dispatchers.IO) {
        return@withContext try {
            val fileName = resolveFileName(uri)
            val fileSize = resolveFileSize(uri)
            val reliablePageCount = safeReadPageCount(uri)

            if (fileSize > MAX_PDF_SIZE_MB * 1024 * 1024) {
                return@withContext Result.failure(
                    IllegalArgumentException("PDF exceeds ${MAX_PDF_SIZE_MB}MB limit")
                )
            }

            val parsed = tryPdfBoxExtraction(uri, fileName, fileSize)
                ?: tryFallbackExtraction(uri, fileName, fileSize, reliablePageCount)
                ?: return@withContext Result.failure(
                    IllegalStateException("Could not extract text. PDF may be encrypted, scanned, or corrupted.")
                )

            Timber.i("Parsed PDF: $fileName | ${parsed.pageCount} pages | ${parsed.fullText.length} chars")
            Result.success(parsed)
        } catch (e: Exception) {
            Timber.e(e, "PDF parsing failed for $uri")
            Result.failure(e)
        }
    }

    private fun tryPdfBoxExtraction(uri: Uri, fileName: String, fileSize: Long): ParsedPdf? {
        return try {
            val parsed = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        addMoreFormatting = true
                    }

                    val pageTexts = (1..document.numberOfPages).map { pageNumber ->
                        try {
                            stripper.startPage = pageNumber
                            stripper.endPage = pageNumber
                            val raw = stripper.getText(document)
                            normalizeExtractedText(raw)
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to extract page $pageNumber")
                            ""
                        }
                    }

                    val fullText = pageTexts.mapIndexed { index, pageText ->
                        "${PAGE_MARKER}${index + 1}]\n${pageText.ifBlank { "[No extractable text on this page]" }}"
                    }.joinToString("\n")

                    ParsedPdf(
                        fileName = fileName,
                        pageCount = document.numberOfPages,
                        fullText = fullText,
                        pageTexts = pageTexts,
                        fileSizeBytes = fileSize,
                    )
                }
            }

            if (parsed != null) {
                val extractableChars = parsed.pageTexts.sumOf { it.length }
                if (extractableChars < 50) {
                    Timber.w("PDFBox: extracted < 50 chars, trying fallback")
                    return null
                }
                Timber.i("PDFBox extraction successful: ${parsed.pageCount} pages")
                return parsed
            }
            null
        } catch (e: ExceptionInInitializerError) {
            Timber.w(e, "PDFBox initialization failed (missing resources) - trying fallback")
            null
        } catch (e: NoClassDefFoundError) {
            Timber.w(e, "PDFBox class not found - trying fallback")
            null
        } catch (e: Exception) {
            Timber.w(e, "PDFBox extraction failed - trying fallback")
            null
        }
    }

    private fun tryFallbackExtraction(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        reliablePageCount: Int,
    ): ParsedPdf? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val pdfBytes = inputStream.readBytes()
                if (pdfBytes.size < 100) {
                    Timber.w("PDF file too small: ${pdfBytes.size} bytes")
                    return null
                }

                // Check if it's actually a PDF
                if (!pdfBytes.sliceArray(0..3).toString(Charsets.ISO_8859_1).contains("%PDF")) {
                    Timber.w("File doesn't appear to be a valid PDF")
                    return null
                }

                val pdfText = pdfBytes.toString(Charsets.ISO_8859_1)

                // Extract text content using multiple strategies
                val textChunks = mutableListOf<String>()

                // Strategy 1: Extract from BT...ET text objects (most reliable)
                val btPattern = Regex("""BT(.*?)ET""", RegexOption.DOT_MATCHES_ALL)
                btPattern.findAll(pdfText).forEach { match ->
                    val block = match.groupValues[1]
                    // Extract Tj and TJ text operators
                    Regex("""\((.*?)\)""").findAll(block).forEach { textMatch ->
                        val extracted = textMatch.groupValues[1]
                            .replace(Regex("""\\[0-7]{1,3}"""), " ") // Octal escape
                            .replace(Regex("""\\[nrt]"""), " ")       // Line feeds
                            .replace(Regex("""\\."""), "")             // Other escapes
                            .trim()
                        if (extracted.isNotBlank() && extracted.length > 2) {
                            textChunks.add(extracted)
                        }
                    }
                }

                // Strategy 2: Look for text after Tj operators outside BT blocks
                Regex("""Tj""").findAll(pdfText).forEach {
                    val startIdx = maxOf(0, it.range.first - 200)
                    val endIdx = minOf(pdfText.length, it.range.last + 50)
                    val context = pdfText.substring(startIdx, endIdx)
                    Regex("""\((.*?)\)""").findAll(context).forEach { m ->
                        val text = m.groupValues[1].take(300).trim()
                        if (text.isNotBlank() && text.length > 3) {
                            textChunks.add(text)
                        }
                    }
                }

                if (textChunks.isEmpty()) {
                    Timber.w("Fallback: no text chunks extracted")
                    return null
                }

                // Combine and clean
                val fullContent = textChunks
                    .distinctBy { it.take(50) }
                    .joinToString(" ")
                    .replace(Regex("""\s+"""), " ")
                    .take(100000)

                if (fullContent.length < 50) {
                    Timber.w("Fallback: extracted content too short (${fullContent.length} chars)")
                    return null
                }

                val normalizedText = normalizeExtractedText(fullContent)
                val estimatedByStructure = Regex("""/Type\s*/Page\b""").findAll(pdfText).count()
                val pageCount = when {
                    reliablePageCount > 0 -> reliablePageCount
                    estimatedByStructure in 1..5000 -> estimatedByStructure
                    else -> 1
                }

                val fullText = "${PAGE_MARKER}1]\n$normalizedText"

                Timber.i("Fallback extraction successful: ~${pageCount} pages, ${normalizedText.length} chars extracted")
                ParsedPdf(
                    fileName = fileName,
                    pageCount = pageCount,
                    fullText = fullText,
                    pageTexts = listOf(normalizedText),
                    fileSizeBytes = fileSize,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Fallback extraction failed")
            null
        }
    }

    private fun safeReadPageCount(uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    doc.numberOfPages.coerceAtLeast(1)
                }
            } ?: 0
        } catch (e: Exception) {
            Timber.w(e, "Could not read reliable page count")
            0
        }
    }

    private fun normalizeExtractedText(raw: String): String {
        return raw
            .replace("\r", "\n")
            .replace(Regex("[\t\u000B]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .replace(Regex("[ ]{2,}"), " ")
            .trim()
    }


    /**
     * Quick page count check without full parse — used to estimate processing time.
     */
    suspend fun getPageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext 0
            val pdfDocument = PDDocument.load(inputStream)
            val count       = pdfDocument.numberOfPages
            pdfDocument.close()
            inputStream.close()
            count
        } catch (e: Exception) {
            Timber.e(e, "Page count check failed")
            0
        }
    }

    private fun resolveFileName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) cursor.getString(nameIdx) else "document.pdf"
                } else "document.pdf"
            } ?: "document.pdf"
        } catch (e: Exception) { "document.pdf" }
    }

    private fun resolveFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) { 0L }
    }
}
