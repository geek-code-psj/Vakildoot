package com.vakildoot.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.vakildoot.data.model.*
import com.vakildoot.ui.VakilDootUiState
import com.vakildoot.ui.theme.VakilColors

// ── ROOT SCAFFOLD ─────────────────────────────────────────────────────────────

@Composable
fun VakilDootRoot(
    uiState: VakilDootUiState,
    onDocumentSelected: (Document) -> Unit,
    onUploadPdf: (android.net.Uri) -> Unit,
    onSendMessage: (String) -> Unit,
    onDeleteDocument: (Long) -> Unit,
    onShowUpload: (Boolean) -> Unit,
    onResetIndexing: () -> Unit,
) {
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onUploadPdf(it) } }

    // Show processing overlay when indexing
    if (uiState.indexingState is IndexingState.Processing) {
        ProcessingScreen(state = uiState.indexingState)
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left: Document sidebar
        DocumentSidebar(
            documents          = uiState.documents,
            activeDocument     = uiState.activeDocument,
            totalChunks        = uiState.totalChunksIndexed,
            lastLatencyMs      = uiState.lastLatencyMs,
            deviceTier         = uiState.deviceTier,
            onDocumentSelected = onDocumentSelected,
            onUploadClick      = { pdfLauncher.launch("application/pdf") },
            onDeleteDocument   = onDeleteDocument,
        )

        // Right: Chat / Welcome
        if (uiState.activeDocument != null) {
            ChatScreen(
                document   = uiState.activeDocument,
                messages   = uiState.messages,
                chatState  = uiState.chatState,
                streaming  = uiState.streamingToken,
                onSend     = onSendMessage,
            )
        } else {
            WelcomeScreen(onUploadClick = { pdfLauncher.launch("application/pdf") })
        }
    }
}

// ── DOCUMENT SIDEBAR ─────────────────────────────────────────────────────────

@Composable
fun DocumentSidebar(
    documents: List<Document>,
    activeDocument: Document?,
    totalChunks: Int,
    lastLatencyMs: Long,
    deviceTier: DeviceTier?,
    onDocumentSelected: (Document) -> Unit,
    onUploadClick: () -> Unit,
    onDeleteDocument: (Long) -> Unit,
) {
    Surface(
        modifier = Modifier.width(300.dp).fillMaxHeight(),
        color    = VakilColors.Ink2,
        tonalElevation = 0.dp,
    ) {
        Column {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text("DOCUMENTS", style = MaterialTheme.typography.labelSmall,
                    color = VakilColors.TextTertiary)
                FilledTonalButton(
                    onClick = onUploadClick,
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = VakilColors.Gold, contentColor = VakilColors.Ink
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Upload PDF", style = MaterialTheme.typography.labelLarge)
                }
            }

            Divider(color = VakilColors.Border)

            // Document list
            LazyColumn(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (documents.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center) {
                            Text("No documents yet", style = MaterialTheme.typography.bodySmall,
                                color = VakilColors.TextTertiary)
                        }
                    }
                }
                items(documents, key = { it.id }) { doc ->
                    DocumentListItem(
                        document   = doc,
                        isActive   = doc.id == activeDocument?.id,
                        onSelect   = { onDocumentSelected(doc) },
                        onDelete   = { onDeleteDocument(doc.id) },
                    )
                }
            }

            // Drop zone
            Box(
                modifier = Modifier
                    .fillMaxWidth().padding(8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, VakilColors.Border2, RoundedCornerShape(10.dp))
                    .clickable(onClick = onUploadClick)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📄", fontSize = 20.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Drop PDF here", style = MaterialTheme.typography.bodySmall,
                        color = VakilColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                    Text("All processing on-device", style = MaterialTheme.typography.bodySmall,
                        color = VakilColors.TextTertiary)
                }
            }

            Divider(color = VakilColors.Border)

            // Device stats
            DeviceStatsRow(
                totalChunks  = totalChunks,
                lastLatencyMs= lastLatencyMs,
                deviceTier   = deviceTier,
            )
        }
    }
}

@Composable
fun DocumentListItem(
    document: Document,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val riskColor = when (document.riskLevel) {
        "HIGH" -> VakilColors.RiskHigh
        "MED"  -> VakilColors.RiskMed
        else   -> VakilColors.RiskLow
    }
    val riskBg = when (document.riskLevel) {
        "HIGH" -> VakilColors.RiskHighBg
        "MED"  -> VakilColors.RiskMedBg
        else   -> VakilColors.RiskLowBg
    }

    Surface(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect),
        color  = if (isActive) VakilColors.Ink3 else Color.Transparent,
        shape  = RoundedCornerShape(10.dp),
        border = if (isActive) BorderStroke(1.dp, VakilColors.Gold.copy(alpha = 0.3f)) else null,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // PDF thumb
            Box(
                Modifier.width(34.dp).height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(VakilColors.Ink4)
                    .border(1.dp, VakilColors.Border2, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("PDF", style = MaterialTheme.typography.labelSmall,
                    color = VakilColors.Gold, fontWeight = FontWeight.ExtraBold)
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text      = document.displayName.ifBlank { document.fileName },
                    style     = MaterialTheme.typography.bodyMedium,
                    fontWeight= FontWeight.SemiBold,
                    color     = VakilColors.TextPrimary,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis,
                )
                Text(
                    text  = "${document.pageCount} pages · ${document.chunkCount} chunks",
                    style = MaterialTheme.typography.bodySmall,
                    color = VakilColors.TextTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (document.isIndexed) {
                    Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Chip(label = document.riskLevel, color = riskColor, bg = riskBg)
                        Chip(label = "${document.clauseCount} clauses", color = VakilColors.Gold, bg = VakilColors.GoldBg)
                    }
                }
            }
        }
    }
}

@Composable
fun Chip(label: String, color: Color, bg: Color) {
    Text(
        text     = label,
        style    = MaterialTheme.typography.labelSmall,
        color    = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun DeviceStatsRow(totalChunks: Int, lastLatencyMs: Long, deviceTier: DeviceTier?) {
    val modelName = when (deviceTier) {
        DeviceTier.FLAGSHIP -> "Phi-4 Mini"
        DeviceTier.MIDRANGE -> "Gemma-3 2B"
        null                -> "Loading…"
    }
    Row(
        Modifier.fillMaxWidth().padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatCell(value = modelName,          label = "Model",   modifier = Modifier.weight(1f))
        StatCell(value = "$totalChunks",     label = "Chunks",  modifier = Modifier.weight(1f))
        StatCell(value = if (lastLatencyMs > 0) "${lastLatencyMs}ms" else "—",
                                             label = "Latency", modifier = Modifier.weight(1f))
    }
}

@Composable
fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = VakilColors.Ink3) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(value, style = MaterialTheme.typography.labelLarge,
                color = VakilColors.Gold, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = VakilColors.TextTertiary)
        }
    }
}

// ── WELCOME SCREEN ────────────────────────────────────────────────────────────

@Composable
fun WelcomeScreen(onUploadClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(40.dp)
        ) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
                    .background(VakilColors.GoldBg)
                    .border(1.dp, VakilColors.Gold.copy(alpha=0.3f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("V", style = MaterialTheme.typography.displayLarge, color = VakilColors.Gold)
            }

            Text("VakilDoot", style = MaterialTheme.typography.displayMedium,
                color = VakilColors.TextPrimary)

            Text(
                "Upload a legal document to begin. Everything is processed entirely on your device — zero cloud, zero data egress.",
                style = MaterialTheme.typography.bodyLarge,
                color = VakilColors.TextSecondary,
                modifier = Modifier.widthIn(max = 380.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    Triple("01", "Upload PDF", "iText 7 · on-device parsing"),
                    Triple("02", "RAG indexing", "FunctionGemma embeddings"),
                    Triple("03", "Query Phi-4", "ExecuTorch · NPU accelerated"),
                ).forEach { (num, title, desc) ->
                    Surface(
                        modifier = Modifier.width(160.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = VakilColors.Ink2,
                        border = BorderStroke(1.dp, VakilColors.Border),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(num, style = MaterialTheme.typography.displayMedium,
                                color = VakilColors.Gold.copy(alpha=0.35f))
                            Text(title, style = MaterialTheme.typography.titleMedium,
                                color = VakilColors.TextPrimary)
                            Text(desc, style = MaterialTheme.typography.bodySmall,
                                color = VakilColors.TextTertiary, modifier = Modifier.padding(top=3.dp))
                        }
                    }
                }
            }

            Button(
                onClick = onUploadClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VakilColors.Gold, contentColor = VakilColors.Ink
                ),
                modifier = Modifier.padding(top = 8.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text("Upload your first document →",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── CHAT SCREEN ───────────────────────────────────────────────────────────────

@Composable
fun ChatScreen(
    document: Document,
    messages: List<ChatMessage>,
    chatState: ChatState,
    streaming: String,
    onSend: (String) -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streaming) {
        if (messages.isNotEmpty() || streaming.isNotEmpty()) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Surface(color = VakilColors.Ink2) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.width(32.dp).height(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(VakilColors.GoldBg)
                        .border(1.dp, VakilColors.Gold.copy(alpha=0.3f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("PDF", style = MaterialTheme.typography.labelSmall, color = VakilColors.Gold) }

                Column(Modifier.weight(1f)) {
                    Text(document.displayName, style = MaterialTheme.typography.titleMedium,
                        color = VakilColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${document.chunkCount} chunks indexed · ${document.pageCount} pages",
                        style = MaterialTheme.typography.bodySmall, color = VakilColors.TextTertiary)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(VakilColors.RiskLow))
                    Text("RAG active", style = MaterialTheme.typography.bodySmall,
                        color = VakilColors.TextTertiary)
                }
            }
            Divider(color = VakilColors.Border)
        }

        // Messages
        LazyColumn(
            state   = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 20.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    WelcomeChatMessage(document = document)
                }
            }
            items(messages, key = { it.id }) { msg ->
                MessageBubble(message = msg)
            }
            if (chatState is ChatState.Thinking) {
                item { TypingIndicator() }
            }
            if (streaming.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AvatarBubble(label = "V")
                        StreamingBubble(text = streaming)
                    }
                }
            }
        }

        // Suggestions
        if (messages.isEmpty()) {
            LazyRow(
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val sugs = listOf(
                    "Summarise this document",
                    "What are the termination clauses?",
                    "What are the key risks?",
                    "Explain the liability limits",
                    "What are the payment terms?",
                )
                items(sugs) { sug ->
                    SuggestionChip(text = sug, onClick = {
                        inputText = sug
                        onSend(sug)
                        inputText = ""
                    })
                }
            }
        }

        // Input bar
        Surface(color = VakilColors.Ink2) {
            Divider(color = VakilColors.Border)
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value         = inputText,
                    onValueChange = { inputText = it },
                    modifier      = Modifier.weight(1f),
                    placeholder   = {
                        Text("Ask anything about this document…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VakilColors.TextTertiary)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = VakilColors.Gold.copy(alpha=0.4f),
                        unfocusedBorderColor = VakilColors.Border2,
                        focusedTextColor     = VakilColors.TextPrimary,
                        unfocusedTextColor   = VakilColors.TextPrimary,
                        cursorColor          = VakilColors.Gold,
                    ),
                    shape    = RoundedCornerShape(12.dp),
                    textStyle= MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                )
                Button(
                    onClick  = {
                        if (inputText.isNotBlank()) {
                            onSend(inputText)
                            inputText = ""
                        }
                    },
                    enabled  = inputText.isNotBlank() && chatState !is ChatState.Thinking,
                    modifier = Modifier.size(48.dp),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = VakilColors.Gold,
                        contentColor   = VakilColors.Ink,
                        disabledContainerColor = VakilColors.Gold.copy(alpha=0.3f),
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Default.Send, "Send", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun WelcomeChatMessage(document: Document) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AvatarBubble(label = "V")
        Surface(
            shape = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp),
            color = VakilColors.Ink3,
            border = BorderStroke(1.dp, VakilColors.Border),
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Document indexed. ${document.chunkCount} chunks embedded into your local vector store.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VakilColors.TextPrimary,
                )
                Text(
                    "Ask me anything about this document, or use the suggestions below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VakilColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) AvatarBubble(label = "V")
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.widthIn(max = 480.dp)) {
            Surface(
                shape = if (isUser) RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp)
                        else         RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp),
                color = if (isUser) VakilColors.Gold else VakilColors.Ink3,
                border = if (!isUser) BorderStroke(1.dp, VakilColors.Border) else null,
            ) {
                Text(
                    text     = message.content,
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = if (isUser) VakilColors.Ink else VakilColors.TextPrimary,
                    modifier = Modifier.padding(12.dp, 10.dp),
                )
            }

            // Source citations
            if (!isUser && message.sourceClauses.isNotBlank()) {
                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    message.sourceClauses.split(",").forEach { clause ->
                        Text(
                            text  = "§ $clause",
                            style = MaterialTheme.typography.labelSmall,
                            color = VakilColors.TextTertiary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(VakilColors.Ink4)
                                .border(1.dp, VakilColors.Border2, RoundedCornerShape(5.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Latency
            if (!isUser && message.latencyMs > 0) {
                Text("${message.latencyMs}ms · ${message.tokensUsed} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = VakilColors.TextTertiary,
                    modifier = Modifier.padding(top = 3.dp))
            }
        }

        if (isUser) { Spacer(Modifier.width(10.dp)); AvatarBubble(label = "U") }
    }
}

@Composable
fun AvatarBubble(label: String) {
    Box(
        Modifier.size(30.dp).clip(CircleShape)
            .background(if (label == "V") VakilColors.GoldBg else VakilColors.Ink4)
            .border(1.dp,
                if (label == "V") VakilColors.Gold.copy(alpha=0.3f) else VakilColors.Border2,
                CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (label == "V") VakilColors.Gold else VakilColors.TextSecondary,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StreamingBubble(text: String) {
    Surface(
        shape  = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp),
        color  = VakilColors.Ink3,
        border = BorderStroke(1.dp, VakilColors.Border),
        modifier = Modifier.widthIn(max = 480.dp),
    ) {
        Row(Modifier.padding(12.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = VakilColors.TextPrimary)
            // Blinking cursor
            Text("▌", color = VakilColors.Gold)
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        AvatarBubble(label = "V")
        Surface(
            shape  = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp),
            color  = VakilColors.Ink3,
            border = BorderStroke(1.dp, VakilColors.Border),
        ) {
            Row(Modifier.padding(16.dp, 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // Simple static dots — animation in production via Compose animation APIs
                repeat(3) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(VakilColors.TextTertiary))
                }
            }
        }
    }
}

@Composable
fun SuggestionChip(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape    = RoundedCornerShape(20.dp),
        color    = VakilColors.Ink3,
        border   = BorderStroke(1.dp, VakilColors.Border2),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = VakilColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp))
    }
}

// ── PROCESSING SCREEN ─────────────────────────────────────────────────────────

@Composable
fun ProcessingScreen(state: IndexingState.Processing) {
    val stages = ProcessingStage.values().filter { it != ProcessingStage.DONE }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp).padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Indexing document…",
                style = MaterialTheme.typography.headlineLarge,
                color = VakilColors.TextPrimary)
            Text("Running on-device pipeline — no data leaves your device",
                style = MaterialTheme.typography.bodyMedium,
                color = VakilColors.TextTertiary)

            LinearProgressIndicator(
                progress = state.progress,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = VakilColors.Gold,
                trackColor = VakilColors.Ink4,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stages.forEach { stage ->
                    val isCurrent  = stage == state.stage
                    val isDone     = stage.ordinal < state.stage.ordinal

                    Surface(
                        shape  = RoundedCornerShape(10.dp),
                        color  = VakilColors.Ink2,
                        border = if (isCurrent) BorderStroke(1.dp, VakilColors.Gold.copy(alpha=0.4f))
                                 else BorderStroke(1.dp, VakilColors.Border),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(
                                    when {
                                        isDone    -> VakilColors.RiskLowBg
                                        isCurrent -> VakilColors.GoldBg
                                        else      -> VakilColors.Ink4
                                    }
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    isDone    -> Text("✓", style = MaterialTheme.typography.labelMedium, color = VakilColors.RiskLow)
                                    isCurrent -> CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                        color = VakilColors.Gold, trackColor = VakilColors.Ink4,
                                    )
                                    else      -> Text("○", style = MaterialTheme.typography.labelMedium, color = VakilColors.TextTertiary)
                                }
                            }
                            Column {
                                Text(stage.label, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isCurrent) VakilColors.Gold else VakilColors.TextPrimary)
                                Text(stage.detail, style = MaterialTheme.typography.bodySmall,
                                    color = VakilColors.TextTertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}
