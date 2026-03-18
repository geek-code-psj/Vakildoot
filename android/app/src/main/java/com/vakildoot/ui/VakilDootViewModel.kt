package com.vakildoot.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vakildoot.data.model.*
import com.vakildoot.data.repository.DocumentRepository
import com.vakildoot.domain.usecase.IndexDocumentUseCase
import com.vakildoot.domain.usecase.QueryDocumentUseCase
import com.vakildoot.ml.inference.LlmInferenceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
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
)

@HiltViewModel
class VakilDootViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val indexDocumentUseCase: IndexDocumentUseCase,
    private val queryDocumentUseCase: QueryDocumentUseCase,
    private val llmEngine: LlmInferenceEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VakilDootUiState())
    val uiState: StateFlow<VakilDootUiState> = _uiState.asStateFlow()

    init {
        observeDocuments()
        _uiState.update { it.copy(deviceTier = llmEngine.getDeviceTier()) }
    }

    private fun observeDocuments() {
        viewModelScope.launch {
            repository.getAllDocuments().collect { docs ->
                _uiState.update { it.copy(documents = docs) }
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
            repository.getMessagesForDocument(documentId).collect { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    fun sendMessage(query: String) {
        val docId = _uiState.value.activeDocument?.id ?: return
        if (query.isBlank()) return

        _uiState.update { it.copy(chatState = ChatState.Thinking, streamingToken = "") }

        viewModelScope.launch {
            val result = queryDocumentUseCase(
                documentId = docId,
                query      = query,
                onToken    = { token ->
                    // Stream tokens to UI in real time
                    _uiState.update { it.copy(streamingToken = it.streamingToken + token) }
                }
            )
            result.fold(
                onSuccess = { msg ->
                    _uiState.update {
                        it.copy(
                            chatState     = ChatState.Idle,
                            streamingToken = "",
                            lastLatencyMs = msg.latencyMs,
                        )
                    }
                },
                onFailure = { e ->
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
