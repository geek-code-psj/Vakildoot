# VakilDoot — Phase 1 MVP

> **Sovereign AI Legal Assistant · Zero-Cloud · On-Device · Built for India**

Everything runs on your device. No API keys. No data egress. Zero cloud dependency.

---

## What Phase 1 Delivers

| Feature | Status |
|---------|--------|
| PDF upload + iText 7 parsing | ✅ Complete |
| 512-token chunking with overlap | ✅ Complete |
| FunctionGemma-270M on-device embeddings | ✅ (stub — swap model file) |
| ObjectBox local vector store | ✅ Complete |
| Phi-4-mini-instruct inference via ExecuTorch | ✅ (stub — swap model file) |
| Adaptive device tier (flagship / midrange) | ✅ Complete |
| ADPF thermal management | ✅ Complete |
| Jetpack Compose UI | ✅ Complete |
| Network blocked at manifest level | ✅ Complete |
| Unit tests | ✅ Complete |

---

## Legal Intelligence Pipeline (NEW)

VakilDoot doesn't just use a generic model; it uses a specialized pipeline to "teach" the AI Indian Law:

1.  **Specialized Dataset (`bns_indian_law_qa.json`):** A curated set of instructions covering the 2024 law transition (BNS/BNSS/BSA), Contract Act nuances, and RERA rights.
2.  **Fine-Tuning (`train_lora.py`):** A LoRA (Low-Rank Adaptation) script designed for Phi-4-mini. This allows you to train a tiny 80MB "Legal Brain" adapter that sits on top of the base model.
3.  **BNS-Aware System Prompt:** A hardcoded system prompt in `LlmInferenceEngine` that enforces the July 1st, 2024 transition logic, ensuring the AI never cites old laws for new crimes.

---

## Architecture

```
PDF (Uri)
   │
   ▼
PdfParser (iText 7)          ← runs on IO dispatcher
   │  fullText + pageTexts
   ▼
TextChunker                  ← 512-token windows, 50-token overlap
   │  List<RawChunk>
   ▼
EmbeddingEngine              ← FunctionGemma-270M via MediaPipe
   │  FloatArray[1024]
   ▼
ObjectBox (local)            ← DocumentChunk with embeddingRaw
   │
   ▼  (at query time)
RagPipeline.retrieve()       ← cosine similarity over all chunks
   │  top-5 RetrievedChunk
   ▼
LlmInferenceEngine           ← Phi-4-mini-instruct + Legal LoRA
   │  SYSTEM_PROMPT + context + query → InferenceResult
   ▼
ChatMessage stored + UI      ← Jetpack Compose streaming display
```

---

## Quick Start

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android device or emulator — API 28+ (Android 9+)
- 4GB+ device RAM for Phi-4 Mini

### Step 1 — Clone and open
```bash
git clone https://github.com/yourname/vakildoot
cd vakildoot/android
# Open in Android Studio: File → Open → select android/ folder
```

### Step 2 — Build (without model files)
The app compiles and runs in **stub mode** without any model files.
All AI responses use deterministic mock outputs. This is intentional
for UI development — you can build and run immediately.

```bash
./gradlew assembleDebug
```

---

## Phase 2 Upgrade Path

1. **sqlite-vss HNSW** — replace ObjectBox brute-force with HNSW ANN search.
2. **LoRA Deployment** — Convert your `legal_lora.bin` using the training script and push it to `/models/`.
3. **Structured clause extraction** — add JSON output schema to LLM prompt for automated risk scoring.
4. **Bhashini voice input** — Hindi + 21 regional languages for better accessibility.

---

*VakilDoot Phase 1 — Built with ❤️ for the Indian legal ecosystem*
*Zero cloud. Zero compromise. Sovereign AI.*
