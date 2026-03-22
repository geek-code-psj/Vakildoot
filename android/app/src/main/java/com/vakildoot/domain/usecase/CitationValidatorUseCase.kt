package com.vakildoot.domain.usecase

import timber.log.Timber
import javax.inject.Inject

data class ValidatedResponse(
    val originalText: String,
    val validatedText: String,         // With [VERIFIED] / [UNVERIFIED] badges
    val foundClauses: List<String>,    // Clauses actually found in response
    val verifiedClauses: List<String>, // Clauses that exist in document
    val notInContext: List<String>,    // Clauses not present in retrieved context
    val hallucinated: List<String>,    // Clauses mentioned but not in document
    val confidenceScore: Float,        // 0.0–1.0
)

/**
 * CitationValidatorUseCase
 *
 * Validates that clause references in LLM responses actually exist in the document.
 * Prevents hallucinated clause numbers like "Clause 6.1" when document only has "3.1–3.5".
 */
class CitationValidatorUseCase @Inject constructor() {

    operator fun invoke(
        llmResponse: String,
        validClausesInDocument: List<String>,
        retrievedContext: String,
    ): ValidatedResponse {
        val foundClauses = extractClauseReferences(llmResponse)
        val contextClauses = extractClauseReferences(retrievedContext)
        val verified = foundClauses.filter { it in validClausesInDocument && it in contextClauses }
        val notInContext = foundClauses.filter { it in validClausesInDocument && it !in contextClauses }
        val hallucinated = foundClauses.filter { it !in validClausesInDocument }

        val confidence = calculateConfidence(verified, hallucinated, notInContext)
        val validatedText = addValidationBadges(llmResponse, verified, hallucinated, notInContext)

        Timber.d(
            "Validation: ${verified.size} verified, ${notInContext.size} out-of-context, ${hallucinated.size} hallucinated, confidence=${confidence}"
        )

        return ValidatedResponse(
            originalText = llmResponse,
            validatedText = validatedText,
            foundClauses = foundClauses,
            verifiedClauses = verified,
            notInContext = notInContext,
            hallucinated = hallucinated,
            confidenceScore = confidence,
        )
    }

    private fun extractClauseReferences(text: String): List<String> {
        val patterns = listOf(
            Regex("""(?:Clause|Cl\.)\s+([\d.]+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:Section|Sec\.)\s+([\d.]+)""", RegexOption.IGNORE_CASE),
            Regex("""[Aa]rticle\s+([\d.]+)"""),
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
        val parts = s.split(".")
        if (parts.isEmpty() || parts.size > 3) return false
        return parts.all { it.isNotEmpty() && it.toIntOrNull() != null }
    }

    private fun calculateConfidence(
        verified: List<String>,
        hallucinated: List<String>,
        notInContext: List<String>,
    ): Float {
        val total = verified.size + hallucinated.size + notInContext.size
        if (total == 0) return 0.5f // Neutral if no clauses mentioned

        val verifiedRatio = verified.size.toFloat() / total.coerceAtLeast(1)
        // Confidence = all verified ? 1.0 : some hallucinated ? 0.3-0.7
        return when {
            hallucinated.isEmpty() && notInContext.isEmpty() && verified.isNotEmpty() -> 0.95f
            hallucinated.isEmpty() && verified.isEmpty() -> 0.6f
            hallucinated.size >= verified.size -> 0.2f
            notInContext.isNotEmpty() -> 0.35f + (verifiedRatio * 0.4f)
            else -> 0.3f + (verifiedRatio * 0.5f)
        }
    }

    private fun addValidationBadges(
        text: String,
        verified: List<String>,
        hallucinated: List<String>,
        notInContext: List<String>,
    ): String {
        var result = text

        // Mark verified clauses
        verified.forEach { clause ->
            val patterns = listOf(
                """(?:Clause|Cl\.)\s+${Regex.escape(clause)}""",
                """(?:Section|Sec\.)\s+${Regex.escape(clause)}""",
            )
            patterns.forEach { pattern ->
                result = result.replace(Regex(pattern), "$0 ✓ [VERIFIED]")
            }
        }

        // Mark hallucinated clauses with warning
        hallucinated.forEach { clause ->
            val patterns = listOf(
                """(?:Clause|Cl\.)\s+${Regex.escape(clause)}""",
                """(?:Section|Sec\.)\s+${Regex.escape(clause)}""",
            )
            patterns.forEach { pattern ->
                result = result.replace(Regex(pattern), "$0 ⚠️ [NOT FOUND]")
            }
        }

        // Mark clauses that exist globally but were not present in retrieved local context.
        notInContext.forEach { clause ->
            val patterns = listOf(
                """(?:Clause|Cl\.)\s+${Regex.escape(clause)}""",
                """(?:Section|Sec\.)\s+${Regex.escape(clause)}""",
            )
            patterns.forEach { pattern ->
                result = result.replace(Regex(pattern), "$0 ⚠ [OUT OF CONTEXT]")
            }
        }

        return result
    }
}

