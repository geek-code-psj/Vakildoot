package com.vakildoot.data.repository

import android.content.Context
import android.net.Uri
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy
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
 * Uses iText 7 Community (AGPL) to extract text from legal PDFs entirely on-device.
 * Handles:
 *   - Standard text PDFs (contracts, NDAs, deeds)
 *   - Multi-column layouts (legal gazette pages)
 *   - Tables (payment schedules, fee structures)
 *
 * Does NOT handle scanned/image PDFs — those require OCR (Phase 3: Bhashini OCR).
 *
 * iText 7 Community is AGPL-licensed. For commercial use, obtain a commercial license
 * from iText Group or use Apache PDFBox as an alternative.
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

            if (fileSize > MAX_PDF_SIZE_MB * 1024 * 1024) {
                return@withContext Result.failure(
                    IllegalArgumentException("PDF exceeds ${MAX_PDF_SIZE_MB}MB limit")
                )
            }

            // Open stream — iText reads directly from InputStream
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open URI: $uri"))

            val pdfReader   = PdfReader(inputStream)
            val pdfDocument = PdfDocument(pdfReader)

            val pageCount  = pdfDocument.numberOfPages
            val pageTexts  = mutableListOf<String>()
            val fullTextSb = StringBuilder()

            for (pageNum in 1..pageCount) {
                val strategy = SimpleTextExtractionStrategy()
                val pageText = PdfTextExtractor.getTextFromPage(
                    pdfDocument.getPage(pageNum), strategy
                )
                pageTexts.add(pageText)
                fullTextSb.append(PAGE_MARKER).append("$pageNum]\n")
                fullTextSb.append(pageText).append("\n")
            }

            pdfDocument.close()
            inputStream.close()

            val fullText = fullTextSb.toString()
            Timber.i("Parsed PDF: $fileName | $pageCount pages | ${fullText.length} chars")

            Result.success(ParsedPdf(
                fileName      = fileName,
                pageCount     = pageCount,
                fullText      = fullText,
                pageTexts     = pageTexts,
                fileSizeBytes = fileSize,
            ))
        } catch (e: Exception) {
            Timber.e(e, "PDF parsing failed for $uri")
            Result.failure(e)
        }
    }

    /**
     * Quick page count check without full parse — used to estimate processing time.
     */
    suspend fun getPageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext 0
            val pdfReader   = PdfReader(inputStream)
            val pdfDocument = PdfDocument(pdfReader)
            val count       = pdfDocument.numberOfPages
            pdfDocument.close()
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
