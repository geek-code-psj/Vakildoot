package com.vakildoot

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class CorpusAuditTest {

    @Test
    fun `bns corpus exposes section coverage and supports metadata header`() {
        val corpusPath = resolveCorpusPath()
        val lines = Files.readAllLines(corpusPath)
        assertTrue("Corpus file is empty", lines.isNotEmpty())

        var schemaVersion = "1.0.0-legacy"
        val sections = linkedSetOf<String>()
        val sectionTierMap = linkedMapOf<String, Int>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.contains("\"_meta\"") || trimmed.contains("\"schema_version\"")) {
                val match = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
                    ?: Regex("\"schema_version\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
                if (match != null) {
                    schemaVersion = match.groupValues[1]
                }
                continue
            }

            val sectionMatch = Regex("\"section_number\"\\s*:\\s*\"([^\"]*)\"").find(trimmed)
            val section = sectionMatch?.groupValues?.get(1)?.trim().orEmpty()
            if (section.isNotEmpty()) {
                sections += section
                val tier = Regex("\"severity_tier\"\\s*:\\s*(\\d+)")
                    .find(trimmed)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                if (tier != null) {
                    sectionTierMap[section] = tier
                }
            }
        }

        println("Corpus schema version: $schemaVersion")
        println("Unique section_number count: ${sections.size}")
        println("Section 2 present: ${"2" in sections}")

        // Baseline health checks for strict corpus build.
        assertTrue("Expected at least 100 sections in strict corpus", sections.size >= 100)
        assertTrue("Expected Section 2 coverage in corpus", "2" in sections)

        if (sections.size >= 358) {
            assertEquals("Section 1 must be Tier 0", 0, sectionTierMap["1"])
            assertEquals("Section 66 must be Tier 3", 3, sectionTierMap["66"])
            assertEquals("Section 70 must be Tier 3", 3, sectionTierMap["70"])
            assertEquals("Section 358 must be Tier 0", 0, sectionTierMap["358"])
        }
    }

    private fun resolveCorpusPath(): Path {
        val candidates = listOf(
            Paths.get("src", "main", "assets", "legal", "bns_manifest.jsonl"),
            Paths.get("app", "src", "main", "assets", "legal", "bns_manifest.jsonl"),
            Paths.get("src", "main", "assets", "legal", "bns_chunks.jsonl"),
            Paths.get("app", "src", "main", "assets", "legal", "bns_chunks.jsonl"),
        )
        return candidates.firstOrNull { Files.exists(it) }
            ?: error("Cannot locate legal corpus JSONL in expected test paths")
    }
}

