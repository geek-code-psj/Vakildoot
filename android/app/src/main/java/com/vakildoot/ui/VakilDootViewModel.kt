package com.vakildoot.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vakildoot.data.model.*
import com.vakildoot.data.repository.DocumentRepository
import com.vakildoot.domain.usecase.IndexDocumentUseCase
import com.vakildoot.domain.usecase.PreloadLegalCorpusUseCase
import com.vakildoot.domain.usecase.QueryDocumentUseCase
import com.vakildoot.ml.inference.LlmInferenceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class VakilDootUiState(
    val documents: List<Document>         = emptyList(),
    val activeDocument: Document?         = null,
    val messages: List<ChatMessage>       = emptyList(),
    val indexingState: IndexingState      = IndexingState.Idle,
    val chatState: ChatState              = ChatState.Idle,
    val deviceTier: DeviceTier?           = null,
    val totalChunksIndexed: Int           = 0,
    val lastLatencyMs: Long               = 0L,
    val streamingToken: String            = "",  // live token during streaming
    val showUploadSheet: Boolean          = false,
    val runtimeWarning: String            = "",
)

@HiltViewModel
class VakilDootViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val indexDocumentUseCase: IndexDocumentUseCase,
    private val preloadLegalCorpusUseCase: PreloadLegalCorpusUseCase,
    private val queryDocumentUseCase: QueryDocumentUseCase,
    private val llmEngine: LlmInferenceEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VakilDootUiState())
    val uiState: StateFlow<VakilDootUiState> = _uiState.asStateFlow()

    // Token batching buffer to prevent excessive recompositions during streaming
    private val tokenBuffer = StringBuilder()
    private var isStreamingActive = false

    init {
        observeDocuments()
        preloadBundledLegalCorpus()
        _uiState.update { it.copy(deviceTier = llmEngine.deviceTier) }
    }

    private fun preloadBundledLegalCorpus() {
        viewModelScope.launch {
            preloadLegalCorpusUseCase()
                .onSuccess { result ->
                    if (result != null) {
                        Timber.i(
                            "Preloaded legal corpus: docId=${result.documentId}, chunks=${result.insertedChunks}, skipped=${result.skippedLines}"
                        )
                        if (_uiState.value.activeDocument == null) {
                            repository.getDocument(result.documentId)?.let { preloaded ->
                                _uiState.update { it.copy(activeDocument = preloaded) }
                                loadMessages(preloaded.id)
                            }
                        }
                    }
                }
                .onFailure { error ->
                    // Never block app startup due to corpus preload errors.
                    Timber.w(error, "Continuing without bundled legal corpus")
                }
        }
    }

    private fun observeDocuments() {
        viewModelScope.launch {
            repository.getAllDocuments()
                .distinctUntilChanged()  // Only emit when documents actually change
                .collect { docs ->
                _uiState.update { it.copy(documents = docs) }
                val activeId = _uiState.value.activeDocument?.id
                val activeStillPresent = activeId != null && docs.any { it.id == activeId }
                if (!activeStillPresent && docs.isNotEmpty()) {
                    val preloaded = docs.firstOrNull { it.filePath.startsWith("asset://") && it.isIndexed }
                    val indexedFallback = docs.firstOrNull { it.isIndexed }
                    val nextActive = preloaded ?: indexedFallback
                    if (nextActive != null) {
                        _uiState.update { it.copy(activeDocument = nextActive) }
                        loadMessages(nextActive.id)
                    }
                }
                // Update total chunk count
                val totalChunks = repository.getTotalChunkCount()
                _uiState.update { it.copy(totalChunksIndexed = totalChunks) }
            }
        }
    }

    // ── Document management ──────────────────────────────────────────────────

    fun onDocumentSelected(document: Document) {
        _uiState.update { it.copy(activeDocument = document) }
        loadMessages(document.id)
    }

    fun showUploadSheet(show: Boolean) {
        _uiState.update { it.copy(showUploadSheet = show) }
    }

    // ── Indexing pipeline ────────────────────────────────────────────────────

    fun indexDocument(uri: Uri) {
        viewModelScope.launch {
            showUploadSheet(false)
            indexDocumentUseCase(uri)
                .catch { e ->
                    Timber.e(e, "Index pipeline error")
                    _uiState.update { it.copy(indexingState = IndexingState.Error(e.message ?: "Unknown error")) }
                }
                .collect { state ->
                    _uiState.update { it.copy(indexingState = state) }
                    if (state is IndexingState.Success) {
                        onDocumentSelected(state.document)
                    }
                }
        }
    }

    fun resetIndexingState() {
        _uiState.update { it.copy(indexingState = IndexingState.Idle) }
    }

    // ── Chat ─────────────────────────────────────────────────────────────────

    private fun loadMessages(documentId: Long) {
        viewModelScope.launch {
            repository.getMessagesForDocument(documentId)
                .distinctUntilChanged()  // Only emit when messages change
                .collect { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    fun sendMessage(query: String) {
        val docId = _uiState.value.activeDocument?.id ?: return
        if (query.isBlank()) return

        _uiState.update { it.copy(chatState = ChatState.Thinking, streamingToken = "", runtimeWarning = "") }
        tokenBuffer.clear()
        isStreamingActive = true

        viewModelScope.launch {
            // Batch token updates to prevent excessive recompositions
            launch {
                while (isActive && isStreamingActive) {
                    delay(50)  // Batch tokens every 50ms instead of per-token
                    if (tokenBuffer.isNotEmpty()) {
                        val batchedTokens = tokenBuffer.toString()
                        _uiState.update { it.copy(streamingToken = it.streamingToken + batchedTokens) }
                        tokenBuffer.clear()
                    }
                }
            }

            val result = queryDocumentUseCase(
                documentId = docId,
                query      = query,
                onToken    = { token ->
                    // Buffer tokens instead of updating UI immediately
                    tokenBuffer.append(token)
                }
            )
            
            result.fold(
                onSuccess = { msg ->
                    isStreamingActive = false
                    // Flush remaining tokens
                    if (tokenBuffer.isNotEmpty()) {
                        _uiState.update { it.copy(streamingToken = it.streamingToken + tokenBuffer.toString()) }
                        tokenBuffer.clear()
                    }
                    val warning = msg.content.lineSequence().firstOrNull { it.startsWith("Note:") } ?: ""
                    _uiState.update {
                        it.copy(
                            chatState     = ChatState.Idle,
                            streamingToken = "",
                            lastLatencyMs = msg.latencyMs,
                            runtimeWarning = warning,
                        )
                    }
                },
                onFailure = { e ->
                    isStreamingActive = false
                    Timber.e(e, "Query failed")
                    _uiState.update {
                        it.copy(chatState = ChatState.Error(e.message ?: "Query failed"))
                    }
                }
            )
        }
    }

    fun clearMessages() {
        viewModelScope.launch {
            val docId = _uiState.value.activeDocument?.id ?: return@launch
            repository.clearMessages(docId)
        }
    }

    fun deleteDocument(id: Long) {
        viewModelScope.launch {
            repository.deleteDocument(id)
            if (_uiState.value.activeDocument?.id == id) {
                _uiState.update { it.copy(activeDocument = null, messages = emptyList()) }
            }
        }
    }
}
