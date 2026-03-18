package com.vakildoot.ml.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import com.vakildoot.BuildConfig
import com.vakildoot.data.model.DeviceTier
import com.vakildoot.data.model.InferenceResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LlmInferenceEngine
 *
 * Wraps ExecuTorch's LlmModule to run Phi-4-mini-instruct (flagship)
 * or Gemma-3-2B (midrange) entirely on-device.
 *
 * Model files are expected at:
 *   /data/data/com.vakildoot/files/models/phi4_mini_4bit.pte   (flagship)
 *   /data/data/com.vakildoot/files/models/gemma3_2b_4bit.pte   (midrange)
 *
 * How to obtain model files:
 *   1. Download Phi-4-mini-instruct from HuggingFace
 *   2. Run: python -m executorch.examples.models.phi_4_mini.export \
 *        --checkpoint phi4_mini.pt --quantization 4bit --output phi4_mini_4bit.pte
 *   3. Copy .pte file into app's internal storage via adb or first-run download
 *
 * Key design decisions:
 *   - 4-bit INT4 quantisation: reduces 3.8B model from ~7.6 GB → ~1.9 GB VRAM
 *   - XNNPack backend: CPU inference with Armv9 SME2 acceleration
 *   - Single model instance kept warm in memory (lazy-loaded on first query)
 *   - System prompt baked in with Indian legal context
 */
@Singleton
class LlmInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // ExecuTorch LlmModule — loaded lazily
    // In production: import org.pytorch.executorch.LlmModule
    // Here typed as Any to compile without the AAR present
    private var llmModule: Any? = null
    private var isLoaded = false
    private var loadedModelPath = ""
    private val deviceTier: DeviceTier by lazy { detectDeviceTier() }

    companion object {
        // ─────────────────────────────────────────────────────────────────
        //  VakilDoot Legal System Prompt — Indian Law Specialist
        //
        //  CRITICAL UPDATE: As of 1 July 2024, three foundational laws replaced:
        //    IPC 1860   → BNS 2023  (Bharatiya Nyaya Sanhita)
        //    CrPC 1973  → BNSS 2023 (Bharatiya Nagarik Suraksha Sanhita)
        //    IEA 1872   → BSA 2023  (Bharatiya Sakshya Adhiniyam)
        //
        //  Old laws still apply to offences committed BEFORE 1 July 2024.
        // ─────────────────────────────────────────────────────────────────
        private val SYSTEM_PROMPT = """
You are VakilDoot, an expert AI legal assistant specialising in Indian law.
You analyse contracts, agreements, deeds, and legal documents and provide
precise, clause-level analysis grounded in current Indian statutes.

=== CRITICAL: THREE LAWS REPLACED FROM 1 JULY 2024 ===

REPEALED (do NOT cite for post-July 2024 matters):
  IPC 1860, CrPC 1973, IEA 1872

NEW LAW — BNS 2023 (Bharatiya Nyaya Sanhita) — replaces IPC:
  S.316 BNS = cheating (was S.420 IPC)
  S.318 BNS = cheating by personation (was S.419 IPC)
  S.303 BNS = theft (was S.378 IPC)
  S.351 BNS = criminal intimidation (was S.503 IPC)
  S.111-113 BNS = organised crime (NEW — no IPC equivalent)
  S.61-99 BNS = offences against persons

NEW LAW — BNSS 2023 (Bharatiya Nagarik Suraksha Sanhita) — replaces CrPC:
  S.173 BNSS = FIR filing (was S.154 CrPC)
  S.187 BNSS = arrest procedure (was S.41 CrPC)
  S.479 BNSS = undertrial detention halved for first offenders
  Zero FIR mandatory, trial must complete within 3 years
  Electronic summons now legally valid

NEW LAW — BSA 2023 (Bharatiya Sakshya Adhiniyam) — replaces IEA:
  S.57 BSA = electronic records admissible (was S.65B IEA)
  Digital evidence is now primary evidence

TRANSITIONAL: Offences BEFORE 1 July 2024 = cite old law (IPC/CrPC/IEA)
              Offences AFTER 1 July 2024  = cite new law (BNS/BNSS/BSA)

=== CIVIL & COMMERCIAL LAW (still in force — unchanged) ===

CONTRACTS — Indian Contract Act 1872:
  S.10: Valid contract needs free consent + lawful object
  S.23: Unlawful consideration voids the contract
  S.27: Restraint of trade — POST-EMPLOYMENT NON-COMPETES ARE VOID IN INDIA
        Indian courts will NOT enforce non-competes after employment ends
  S.73: Compensation for loss on breach
  S.74: Liquidated damages — courts reduce if clause is penal/excessive
  S.56: Frustration doctrine

PROPERTY — Transfer of Property Act 1882:
  S.108: STRUCTURAL REPAIRS are landlord's duty — tenant cannot be forced to pay
  S.105: Lease definition

REAL ESTATE — RERA 2016:
  Builder must register before selling any unit
  Carpet area definition is mandatory and binding
  Homebuyer gets interest at SBI PLR + 2% for builder delays
  S.31: Complaints to State RERA Authority

EMPLOYMENT:
  Non-competes after employment VOID under S.27 Contract Act
  Industrial Disputes Act 1947 — retrenchment rules
  EPF Act 1952 — provident fund obligations

DATA PRIVACY — DPDP Act 2023:
  Consent required before processing personal data
  Penalty up to Rs 250 crore for breach
  On-device processing = compliance-native (VakilDoot's core design)

COMPANIES — Companies Act 2013:
  S.166: Director duties
  S.447: Fraud provisions (serious — criminal liability)

CONSUMER — Consumer Protection Act 2019:
  Includes e-commerce and product liability
  District Commission: up to Rs 50 lakh
  State Commission: Rs 50 lakh to Rs 2 crore

ARBITRATION — Arbitration and Conciliation Act 1996 (amended 2019/2021):
  Domestic award must be made within 12 months
  Emergency arbitrator provisions now available

HINDI LEGAL TERMS — translate these if found in documents:
  Patta = land ownership certificate
  Khasra = land survey number
  Khatauni = record of rights / land register
  Vakalatnama = power of attorney for lawyer
  Bainama = sale deed / agreement to sell
  Kiraya = rent
  Girvee = mortgage

=== HOW TO RESPOND ===

1. CITE EXACTLY: State the specific section number.
   CORRECT: "S.27 Indian Contract Act 1872"
   CORRECT: "S.316 BNS 2023" (NOT "S.420 IPC" for post-July 2024 matters)
   WRONG:   "Under Indian law..." (too vague — always cite section)

2. USE CORRECT LAW: BNS/BNSS/BSA for anything after 1 July 2024.

3. RISK FLAG every issue: HIGH RISK / MEDIUM RISK / LOW RISK

4. ENFORCEABILITY: State clearly if a clause is likely void in Indian courts.
   Common voids: broad non-competes (S.27), excessive penalties (S.74),
   unregistered property docs, clauses violating public policy (S.23)

5. REDLINE: After each risk, give a one-sentence suggested replacement.

6. HONEST LIMITS: Say "Verify with a qualified advocate" when uncertain.
   Never guess a section number — leave it blank if unsure.

Context from document:
        """.trimIndent()

        const val MAX_NEW_TOKENS    = 512
        const val TEMPERATURE       = 0.1f   // Low temp for legal accuracy
        const val TOP_P             = 0.9f
        const val CONTEXT_WINDOW    = 4096   // Phi-4 supports 128K but we cap for speed
    }

    /**
     * Load the appropriate model based on device tier.
     * Called once, result cached. Runs on IO dispatcher.
     */
    suspend fun ensureLoaded(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext Result.success(Unit)

        val modelFile = modelFileForTier(deviceTier)
        Timber.d("Loading model: $modelFile (tier=$deviceTier)")

        return@withContext try {
            // Production code:
            // llmModule = LlmModule(modelFile.absolutePath, context.filesDir.absolutePath)
            // llmModule.load()

            // Stub for compilation without ExecuTorch AAR:
            loadedModelPath = modelFile
            isLoaded = true
            Timber.i("Model loaded: $loadedModelPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load model")
            Result.failure(e)
        }
    }

    /**
     * Run inference with RAG context injected.
     *
     * @param query        The user's question
     * @param ragContext   Retrieved document chunks (already formatted)
     * @param onToken      Streaming callback — called for each generated token
     */
    suspend fun infer(
        query: String,
        ragContext: String,
        onToken: ((String) -> Unit)? = null,
    ): Result<InferenceResult> = withContext(Dispatchers.Default) {
        if (!isLoaded) {
            ensureLoaded().getOrElse { return@withContext Result.failure(it) }
        }

        val startMs = System.currentTimeMillis()

        // Build full prompt
        val fullPrompt = buildPrompt(query, ragContext)

        return@withContext try {
            // Production streaming inference:
            // val sb = StringBuilder()
            // llmModule.generate(fullPrompt, MAX_NEW_TOKENS) { token ->
            //     sb.append(token)
            //     onToken?.invoke(token)
            // }
            // val output = sb.toString()

            // Stub: returns a realistic mock response for UI testing
            val output = generateStubResponse(query)
            onToken?.invoke(output)

            val latency = System.currentTimeMillis() - startMs
            val tokens  = output.split(" ").size

            // Log performance
            Timber.d("Inference complete: ${tokens}tok in ${latency}ms (${tokens * 1000 / latency.coerceAtLeast(1)} tok/s)")

            Result.success(InferenceResult(
                text             = output,
                tokensGenerated  = tokens,
                latencyMs        = latency,
                sourceClauses    = extractClauses(output),
            ))
        } catch (e: Exception) {
            Timber.e(e, "Inference failed")
            Result.failure(e)
        }
    }

    fun unload() {
        // Production: llmModule?.close(); llmModule = null
        isLoaded = false
        Timber.d("Model unloaded")
    }

    fun getDeviceTier(): DeviceTier = deviceTier
    fun isModelLoaded(): Boolean    = isLoaded

    // ── Private helpers ────────────────────────────────────────

    private fun buildPrompt(query: String, ragContext: String): String =
        "<|system|>\n$SYSTEM_PROMPT\n\n$ragContext\n<|end|>\n" +
        "<|user|>\n$query\n<|end|>\n<|assistant|>\n"

    private fun modelFileForTier(tier: DeviceTier): String {
        val dir = context.filesDir.absolutePath + "/models/"
        return when (tier) {
            DeviceTier.FLAGSHIP -> dir + BuildConfig.MODEL_TIER_FLAGSHIP + ".pte"
            DeviceTier.MIDRANGE -> dir + BuildConfig.MODEL_TIER_MIDRANGE + ".pte"
        }
    }

    private fun detectDeviceTier(): DeviceTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalRamGb = mi.totalMem / (1024f * 1024f * 1024f)
        Timber.d("Device RAM: %.1f GB".format(totalRamGb))
        return if (totalRamGb >= BuildConfig.RAM_THRESHOLD_GB) DeviceTier.FLAGSHIP
               else                                              DeviceTier.MIDRANGE
    }

    /** Extract clause references from model output e.g. "Clause 8.2", "Cl. 3.1" */
    private fun extractClauses(text: String): List<String> {
        val regex = Regex("""(?:Clause|Cl\.)\s*\d+\.\d+""", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value }.distinct().toList()
    }

    /** Stub response for UI development without a real model file */
    private fun generateStubResponse(query: String): String = when {
        query.contains("terminat", ignoreCase = true) ->
            "**Clause 8 — Termination**\n\n" +
            "• **Cl. 8.1 (Convenience):** Either party may terminate with 30 days written notice.\n" +
            "• **Cl. 8.2 (Breach):** Immediate termination for material breach — ⚠ MEDIUM RISK: no cure period defined.\n" +
            "• **Cl. 8.3 (Survival):** Confidentiality obligations survive for 5 years post-termination.\n\n" +
            "**Recommendation:** Add a 15-day cure period to Cl. 8.2(b) before signing."
        query.contains("risk", ignoreCase = true) ->
            "I identified **3 risk areas** in this document:\n\n" +
            "⚠ **HIGH — Cl. 12.1 (Liability Cap):** No carve-out for gross negligence or fraud.\n" +
            "⚠ **HIGH — Cl. 15.3 (IP Rights):** Overly broad assignment including pre-existing IP.\n" +
            "⚡ **MEDIUM — Cl. 8.2 (Termination):** No cure period before immediate termination.\n\n" +
            "Under the **Indian Contract Act 1872**, the liability cap may be challenged under Section 23 (unconscionable terms)."
        query.contains("summar", ignoreCase = true) ->
            "This is a **mutual Non-Disclosure Agreement** with a 5-year confidentiality term.\n\n" +
            "**Key parties:** Sharma Associates and counterparty\n" +
            "**Governing law:** Indian Contract Act 1872\n" +
            "**Jurisdiction:** Mumbai High Court\n\n" +
            "**Critical issues:** The liability cap (Cl. 12.1) lacks a fraud carve-out, and the IP assignment (Cl. 15.3) is broader than standard NDA scope.\n\n" +
            "**Overall assessment:** ⚠ HIGH RISK — recommend redlining before execution."
        else ->
            "Based on my analysis of the document context, the most relevant provisions are in **Clauses 8, 12, and 15**.\n\n" +
            "Please ask me about specific clauses — for example:\n" +
            "• 'What are the termination clauses?'\n" +
            "• 'Explain the liability limits'\n" +
            "• 'What are the IP rights provisions?'"
    }
}
