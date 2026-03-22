package com.vakildoot.domain.usecase

import timber.log.Timber
import javax.inject.Inject

data class DocumentStructure(
    val clauses: List<String>,           // ["3.1", "3.2", "4.0", ...]
    val tables: List<TableRegion>,
    val sections: List<SectionHeader>,
)

data class TableRegion(
    val pageNumber: Int,
    val startLine: Int,
    val endLine: Int,
    val content: String,
)

data class SectionHeader(
    val pageNumber: Int,
    val level: Int,                      // 1=Chapter, 2=Section, 3=Clause
    val number: String,                  // "3.2" or "Chapter 1"
    val title: String,
)

/**
 * StructureAnalyzerUseCase
 *
 * Detects clause numbers, table regions, and section headers from PDF text.
 * Prevents hallucinated citations by providing authoritative clause list.
 */
class StructureAnalyzerUseCase @Inject constructor() {
    operator fun invoke(fullText: String, pageTexts: List<String>): DocumentStructure {
        val clauses = extractClauses(fullText)
        val tables = detectTables(pageTexts)
        val sections = extractSections(fullText)

        Timber.i("Structure detected: ${clauses.size} clauses, ${tables.size} tables, ${sections.size} sections")
        return DocumentStructure(
            clauses = clauses,
            tables = tables,
            sections = sections,
        )
    }

    private fun extractClauses(text: String): List<String> {
        val patterns = listOf(
            Regex("""(?:Clause|Cl\.)\s+([\d.]+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:Section|Sec\.)\s+([\d.]+)""", RegexOption.IGNORE_CASE),
            Regex("""^\s*([\d]{1,2}\.\d{1,2})\s+""", RegexOption.MULTILINE),
        )

        val found = mutableSetOf<String>()
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val clauseNum = match.groupValues[1].trim()
                if (isValidClauseNumber(clauseNum)) {
                    found.add(clauseNum)
                }
            }
        }

        return found.sorted()
    }

    private fun isValidClauseNumber(s: String): Boolean {
        // Valid: "3", "3.1", "3.1.2", "1", "10.5"
        // Invalid: "123.456", "", ".", "1."
        val parts = s.split(".")
        if (parts.isEmpty() || parts.size > 3) return false
        return parts.all { it.isNotEmpty() && it.toIntOrNull() != null }
    }

    private fun detectTables(pageTexts: List<String>): List<TableRegion> {
        val tables = mutableListOf<TableRegion>()

        pageTexts.forEachIndexed { pageIdx, pageText ->
            val lines = pageText.split("\n")
            var inTable = false
            var tableStart = 0

            lines.forEachIndexed { lineIdx, line ->
                val isTableLine = looksLikeTableRow(line)
                if (isTableLine && !inTable) {
                    inTable = true
                    tableStart = lineIdx
                } else if (!isTableLine && inTable) {
                    if (lineIdx - tableStart >= 2) { // At least 2 rows
                        val tableContent = lines.subList(tableStart, lineIdx).joinToString("\n")
                        tables.add(TableRegion(
                            pageNumber = pageIdx + 1,
                            startLine = tableStart,
                            endLine = lineIdx,
                            content = tableContent,
                        ))
                    }
                    inTable = false
                }
            }

            if (inTable && lines.size - tableStart >= 2) {
                val tableContent = lines.subList(tableStart, lines.size).joinToString("\n")
                tables.add(TableRegion(
                    pageNumber = pageIdx + 1,
                    startLine = tableStart,
                    endLine = lines.size,
                    content = tableContent,
                ))
            }
        }

        return tables
    }

    private fun looksLikeTableRow(line: String): Boolean {
        // Signs of a table row:
        // - Multiple tab-separated or pipe-separated values
        // - Aligned columns (multiple spaces)
        // - Contains mixed text/numbers
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length < 10) return false

        val pipeCount = trimmed.count { it == '|' }
        val tabCount = trimmed.count { it == '\t' }
        val multiSpace = Regex("""\s{2,}""").findAll(trimmed).count()

        return pipeCount >= 2 || tabCount >= 2 || multiSpace >= 2
    }

    private fun extractSections(text: String): List<SectionHeader> {
        val sections = mutableListOf<SectionHeader>()
        val lines = text.split("\n")

        lines.forEachIndexed { idx, line ->
            val chapterMatch = Regex("""^\s*(?:CHAPTER|Chapter)\s+([IVXLCDM0-9]+)[:\-\s](.*)""")
                .find(line)
            if (chapterMatch != null) {
                sections.add(SectionHeader(
                    pageNumber = 1, // Approximate
                    level = 1,
                    number = chapterMatch.groupValues[1],
                    title = chapterMatch.groupValues[2].trim(),
                ))
            }

            val sectionMatch = Regex("""^\s*(?:Section|Sec\.)\s+(\d+)[:\-\s](.*)""")
                .find(line)
            if (sectionMatch != null) {
                sections.add(SectionHeader(
                    pageNumber = 1,
                    level = 2,
                    number = sectionMatch.groupValues[1],
                    title = sectionMatch.groupValues[2].trim(),
                ))
            }
        }

        return sections
    }
}

