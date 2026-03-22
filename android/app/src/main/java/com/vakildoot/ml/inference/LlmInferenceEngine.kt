package com.vakildoot.ml.inference

import android.app.ActivityManager
import android.content.Context
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
    val deviceTier: DeviceTier by lazy { detectDeviceTier() }

    companion object {
        private const val SYSTEM_PROMPT = "You are VakilDoot, an expert AI legal assistant specialising in Indian law."

        const val MAX_NEW_TOKENS    = 512
        const val TEMPERATURE       = 0.1f    // Low temp for legal accuracy
        const val TOP_P             = 0.9f
        const val CONTEXT_WINDOW    = 4096    // Phi-4 supports 128K but we cap for speed
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
