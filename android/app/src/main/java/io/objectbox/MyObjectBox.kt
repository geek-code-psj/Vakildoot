package io.objectbox

/**
 * PHASE 1 BOOTSTRAP: Dummy MyObjectBox class
 * 
 * This file is a placeholder that allows the app to build during Phase 1,
 * before the KSP annotation processor generates the real MyObjectBox.
 * 
 * The actual runtime behavior is handled by AppModule.kt which catches
 * the ClassNotFoundException and provides a fallback.
 * 
 * Once KSP generates the real MyObjectBox.kt in Phase 2, this file will be
 * superseded and can be deleted.
 */
object MyObjectBox {
    object builder
}

