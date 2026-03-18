package com.vakildoot.ml.inference

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ThermalManager
 *
 * Implements Android Dynamic Performance Framework (ADPF) integration.
 * Polls getThermalHeadroom() every 10 seconds and adjusts inference
 * quality to prevent thermal throttling from ruining user experience.
 *
 * From the Sentinel PDF research:
 *   Thermal throttling can drop performance by 40% on mobile devices.
 *   The agent must "ramp down" before the OS enforces throttling.
 *
 * Quality tiers (matching Sentinel ADPF table):
 *   NONE     (0.0–0.7)  → Full quality: flagship model, 512 max tokens
 *   LIGHT    (0.7–0.85) → Reduce background indexing frequency
 *   MODERATE (0.85–0.95)→ Lower temperature, reduce max tokens to 256
 *   SEVERE   (0.95–1.0) → Switch to smaller draft model, disable pre-analysis
 *   CRITICAL (>1.0)     → Suspend inference, alert user
 */
@Singleton
class ThermalManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class ThermalTier {
        NONE, LIGHT, MODERATE, SEVERE, CRITICAL
    }

    data class InferenceQuality(
        val tier: ThermalTier,
        val maxNewTokens: Int,
        val temperature: Float,
        val enablePreAnalysis: Boolean,
        val enableBackgroundIndexing: Boolean,
        val userMessage: String?,
    )

    private val _thermalTier = MutableStateFlow(ThermalTier.NONE)
    val thermalTier: StateFlow<ThermalTier> = _thermalTier.asStateFlow()

    val inferenceQuality: StateFlow<InferenceQuality> = thermalTier.map { tier ->
        when (tier) {
            ThermalTier.NONE     -> InferenceQuality(tier, 512,  0.10f, true,  true,  null)
            ThermalTier.LIGHT    -> InferenceQuality(tier, 512,  0.10f, true,  false, null)
            ThermalTier.MODERATE -> InferenceQuality(tier, 256,  0.15f, true,  false, null)
            ThermalTier.SEVERE   -> InferenceQuality(tier, 128,  0.20f, false, false, "Device is warm — responses may be shorter")
            ThermalTier.CRITICAL -> InferenceQuality(tier, 0,    0.20f, false, false, "Device too hot — AI paused. Please wait.")
        }
    }.stateIn(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        started = SharingStarted.Eagerly,
        initialValue = InferenceQuality(ThermalTier.NONE, 512, 0.10f, true, true, null),
    )

    private var pollingJob: Job? = null

    fun startMonitoring(scope: CoroutineScope) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                updateThermalStatus()
                delay(10_000) // Poll every 10 seconds per ADPF recommendation
            }
        }
        Timber.d("Thermal monitoring started")
    }

    fun stopMonitoring() {
        pollingJob?.cancel()
        Timber.d("Thermal monitoring stopped")
    }

    private fun updateThermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

            // getThermalHeadroom(forecastSeconds) — predict headroom N seconds ahead
            // 0 = current, 10 = predict 10 seconds from now (for proactive throttling)
            val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                powerManager.getThermalHeadroom(10)
            } else {
                // Pre-API 31: use thermal status as fallback
                val status = powerManager.currentThermalStatus
                when (status) {
                    PowerManager.THERMAL_STATUS_NONE     -> 0.5f
                    PowerManager.THERMAL_STATUS_LIGHT    -> 0.8f
                    PowerManager.THERMAL_STATUS_MODERATE -> 0.92f
                    PowerManager.THERMAL_STATUS_SEVERE   -> 0.98f
                    PowerManager.THERMAL_STATUS_CRITICAL -> 1.1f
                    PowerManager.THERMAL_STATUS_EMERGENCY-> 1.2f
                    else                                 -> 0.5f
                }
            }

            val newTier = headroomToTier(headroom)
            if (newTier != _thermalTier.value) {
                Timber.i("Thermal tier changed: ${_thermalTier.value} → $newTier (headroom=$headroom)")
                _thermalTier.value = newTier
            }
        } catch (e: Exception) {
            Timber.w(e, "Thermal status check failed — defaulting to NONE")
        }
    }

    private fun headroomToTier(headroom: Float): ThermalTier = when {
        headroom <= 0.70f -> ThermalTier.NONE
        headroom <= 0.85f -> ThermalTier.LIGHT
        headroom <= 0.95f -> ThermalTier.MODERATE
        headroom <= 1.00f -> ThermalTier.SEVERE
        else              -> ThermalTier.CRITICAL
    }

    fun getCurrentQuality(): InferenceQuality = inferenceQuality.value
}
