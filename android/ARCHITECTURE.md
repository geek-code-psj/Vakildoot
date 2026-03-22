# VakilDoot Production Architecture

_Last updated: 2026-03-22_

This document is the single source of truth for the repository in **Production/Maintenance mode** after ETL completion.

## 1) Repository State (Clean Production Tree)

```text
android/
  250884_2_english_01042024.pdf
  BNS Book_After Correction.pdf
  bns_manifest.jsonl
  ARCHITECTURE.md
  README.md
  build.gradle.kts
  gradle.properties
  gradlew.bat
  local.properties
  settings.gradle.kts
  app/
    src/
      main/
        assets/
          legal/
            bns_manifest.jsonl        # production corpus (active)
            bns_chunks.jsonl          # legacy fallback corpus
        java/com/vakildoot/
          MainActivity.kt
          AppModule.kt
          data/
            model/Models.kt
            repository/
              DocumentRepository.kt
              InMemoryDocumentStore.kt
              PdfParser.kt
          domain/
            usecase/
              PreloadLegalCorpusUseCase.kt
              UseCases.kt
              CitationValidatorUseCase.kt
              StructureAnalyzerUseCase.kt
          ml/
            rag/RagPipeline.kt
            embedding/*
            inference/*
          ui/
            VakilDootViewModel.kt
            screens/Screens.kt
            components/*
            theme/*
  archive/
    etl_scripts/                       # ETL/development scripts retained for traceability
    build_reports/                     # intermediate build-phase reports
```

### Cleanup action taken
ETL scaffolding has been moved out of root into `archive/`:
- `archive/etl_scripts/`: extraction, patch, inspection, summary, verify scripts and manifest backup
- `archive/build_reports/`: temporary/generated markdown status reports

## 2) Data Engine: `bns_manifest.jsonl`

`bns_manifest.jsonl` is the production legal corpus consumed by startup preload.

### Corpus guarantees
- **358 records** mapped to **Section 1..358** of BNS 2023
- **4 severity tiers**:
  - `Tier 0`: Non-penal / administrative / definitions / exceptions
  - `Tier 1`: Minor offences (fine/community service/short imprisonment)
  - `Tier 2`: Moderate offences
  - `Tier 3`: Heinous offences (long imprisonment/life/death)
- **Safety/legal disclaimer block** in each record

### Record schema (production)

```json
{
  "id": "BNS_SEC_66",
  "section_number": "66",
  "title": "...",
  "severity_tier": 3,
  "punishment_summary": "...",
  "fair_use_summary": "...",
  "official_reference_link": "https://...",
  "app_safety_disclaimers": {
    "copyright_notice": "...",
    "ai_generation_warning": "...",
    "legal_liability_shield": "..."
  }
}
```

### Ingestion adaptation in app
`PreloadLegalCorpusUseCase.kt` now supports both schemas:
1. **Manifest-first**: `legal/bns_manifest.jsonl`
2. **Legacy fallback**: `legal/bns_chunks.jsonl`

For manifest rows, preload builds chunk text with:
- section heading
- fair-use summary
- punishment summary
- severity tier
- official source link

This ensures retrieval context is legally grounded and source-citable.

## 3) Golden Path: End-to-End App Flow

## 3.1 Initialization (Startup preload)
1. `MainActivity.kt` launches UI and `VakilDootViewModel`.
2. `VakilDootViewModel.init` triggers:
   - `observeDocuments()`
   - `preloadBundledLegalCorpus()`
3. `PreloadLegalCorpusUseCase.invoke()`:
   - selects corpus asset (`bns_manifest.jsonl` preferred)
   - skips preload if already indexed (via `asset://...` lookup)
   - parses JSONL into `ParsedLegalChunk`
   - embeds chunk text via `EmbeddingEngine`
   - inserts `Document` + `DocumentChunk`s into `DocumentRepository`
4. Repository path:
   - `DocumentRepository` uses `InMemoryDocumentStore` (Phase 1 runtime)
   - ObjectBox remains optional (`AppModule.provideBoxStore()` returns null in Phase 1)
5. ViewModel auto-selects preloaded indexed document as active if no active doc exists.

Result: app boots with BNS corpus available for query without requiring user upload.

## 3.2 Querying (User question to grounded response)
1. User submits query in UI (`Screens.kt` -> `onSendMessage`).
2. `VakilDootViewModel.sendMessage()` calls `QueryDocumentUseCase`.
3. `QueryDocumentUseCase` pipeline:
   - retrieves top relevant chunks via `RagPipeline.retrieve()`
   - formats retrieval context with similarity + page markers
   - runs LLM inference with injected context
   - validates citations/claims using `CitationValidatorUseCase`
   - persists user + assistant chat messages in repository
4. `RagPipeline` internals:
   - query embedding
   - cosine similarity over indexed chunk embeddings
   - top-K selection with minimum similarity threshold

Result: responses are grounded in local BNS section chunks and include source-aware context.

## 3.3 Rendering (Safe display of legal metadata)
Current rendering path in `Screens.kt` uses:
- `VakilDootRoot`
- `ChatScreen`
- message stream from `VakilDootViewModel.uiState`

Because preload chunk text now embeds metadata lines, UI can surface:
- Severity Tier
- Fair-use summary
- Punishment summary
- Official source URL

### `BnsSectionCard`-style rendering contract (recommended/compatible)
When a dedicated card component (e.g., `BnsSectionCard`) is used, bind these fields in order:
1. `Section {section_number}. {title}`
2. badge: `Severity Tier`
3. `fair_use_summary`
4. `punishment_summary`
5. `official_reference_link` (tappable)
6. disclaimer hint (from `app_safety_disclaimers`)

This preserves legal UX safety: concise summary + explicit source + non-advice disclaimer.

## 4) PDF Parsing Backend in Production

`PdfParser.kt` remains active for user-uploaded PDFs and runs fully on-device:
- primary extraction: PDFBox
- fallback extraction: raw text-object heuristics
- output: normalized full text with page markers

Upload flow remains independent from bundled BNS preload flow:
- Upload pipeline: `IndexDocumentUseCase` -> `RagPipeline.indexDocument`
- Bundled corpus pipeline: `PreloadLegalCorpusUseCase`

Both converge into the same `DocumentRepository`/retrieval layer.

## 5) Operational Rules

- Do not edit or remove:
  - `app/src/main/assets/legal/bns_manifest.jsonl`
  - source PDFs in root
  - core Android runtime files under `app/src/main/java`
- Keep ETL scripts and temporary reports in `archive/` only.
- For corpus quality gates, use unit test:
  - `app/src/test/java/com/vakildoot/CorpusAuditTest.kt`

## 6) Verified Health Snapshot

The targeted corpus audit test passes after cleanup and integration updates:
- command run: `gradlew.bat --no-daemon testDebugUnitTest --tests com.vakildoot.CorpusAuditTest`
- status: **BUILD SUCCESSFUL**

This confirms production corpus wiring and critical tier assertions are active in CI-testable form.

