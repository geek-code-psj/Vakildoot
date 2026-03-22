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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isMobile = screenWidth < 600.dp
    
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onUploadPdf(it) } }

    // Show processing overlay when indexing
    if (uiState.indexingState is IndexingState.Processing) {
        ProcessingScreen(state = uiState.indexingState)
        return
    }

    if (isMobile) {
        // Mobile: Stacked layout with bottom nav
        MobileLayout(
            uiState = uiState,
            onDocumentSelected = onDocumentSelected,
            onUploadClick = { pdfLauncher.launch("application/pdf") },
            onDeleteDocument = onDeleteDocument,
            onSendMessage = onSendMessage,
        )
    } else {
        // Tablet/Desktop: Side-by-side layout
        Row(modifier = Modifier.fillMaxSize()) {
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

            if (uiState.activeDocument != null) {
                ChatScreen(
                    document   = uiState.activeDocument,
                    messages   = uiState.messages,
                    chatState  = uiState.chatState,
                    streaming  = uiState.streamingToken,
                    runtimeWarning = uiState.runtimeWarning,
                    onUploadClick = { pdfLauncher.launch("application/pdf") },
                    onSend     = onSendMessage,
                )
            } else {
                WelcomeScreen(onUploadClick = { pdfLauncher.launch("application/pdf") })
            }
        }
    }
}

// ── MOBILE LAYOUT ─────────────────────────────────────────────────────────────

@Composable
fun MobileLayout(
    uiState: VakilDootUiState,
    onDocumentSelected: (Document) -> Unit,
    onUploadClick: () -> Unit,
    onDeleteDocument: (Long) -> Unit,
    onSendMessage: (String) -> Unit,
) {
    var showDocumentsList by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = VakilColors.Ink2) {
                NavigationBarItem(
                    selected = showDocumentsList,
                    onClick = { showDocumentsList = true },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = "Documents") },
                    label = { Text("Docs", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VakilColors.Gold,
                        selectedTextColor = VakilColors.Gold,
                        unselectedIconColor = VakilColors.TextTertiary,
                        indicatorColor = VakilColors.Ink3,
                    )
                )
                NavigationBarItem(
                    selected = !showDocumentsList,
                    onClick = { showDocumentsList = false },
                    icon = { Icon(Icons.Default.Message, contentDescription = "Chat") },
                    label = { Text("Chat", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VakilColors.Gold,
                        selectedTextColor = VakilColors.Gold,
                        unselectedIconColor = VakilColors.TextTertiary,
                        indicatorColor = VakilColors.Ink3,
                    )
                )
            }
        }
    ) { paddingValues ->
        if (showDocumentsList) {
            MobileDocumentsList(
                documents = uiState.documents,
                activeDocument = uiState.activeDocument,
                totalChunks = uiState.totalChunksIndexed,
                lastLatencyMs = uiState.lastLatencyMs,
                deviceTier = uiState.deviceTier,
                onDocumentSelected = {
                    onDocumentSelected(it)
                    showDocumentsList = false
                },
                onUploadClick = onUploadClick,
                onDeleteDocument = onDeleteDocument,
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            if (uiState.activeDocument != null) {
                ChatScreen(
                    document = uiState.activeDocument,
                    messages = uiState.messages,
                    chatState = uiState.chatState,
                    streaming = uiState.streamingToken,
                    runtimeWarning = uiState.runtimeWarning,
                    onUploadClick = onUploadClick,
                    onSend = onSendMessage,
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                    contentAlignment = Alignment.Center) {
                    WelcomeScreen(onUploadClick = onUploadClick)
                }
            }
        }
    }
}

// ── MOBILE DOCUMENTS LIST ─────────────────────────────────────────────────────

@Composable
fun MobileDocumentsList(
    documents: List<Document>,
    activeDocument: Document?,
    totalChunks: Int,
    lastLatencyMs: Long,
    deviceTier: DeviceTier?,
    onDocumentSelected: (Document) -> Unit,
    onUploadClick: () -> Unit,
    onDeleteDocument: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Top App Bar
        Surface(
            color = VakilColors.Ink2,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Documents",
                    style = MaterialTheme.typography.headlineMedium,
                    color = VakilColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                FilledTonalButton(
                    onClick = onUploadClick,
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = VakilColors.Orange,
                        contentColor = VakilColors.Ink
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Document list or empty state
        if (documents.isEmpty()) {
            // Gradient background for empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                VakilColors.GradientStart,
                                VakilColors.GradientEnd
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                EnhancedEmptyState(onUploadClick = onUploadClick)
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(documents, key = { it.id }) { doc ->
                    DocumentListItem(
                        document = doc,
                        isActive = doc.id == activeDocument?.id,
                        onSelect = { onDocumentSelected(doc) },
                        onDelete = { onDeleteDocument(doc.id) },
                    )
                }
            }

            // Bottom stats section
            Surface(
                color = VakilColors.Ink2,
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(4.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Divider(color = VakilColors.Border, thickness = 0.5.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Chip - Model
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = VakilColors.Ink3,
                            modifier = Modifier
                                .height(32.dp)
                                .border(1.dp, VakilColors.Border2, RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                Modifier
                                    .padding(horizontal = 12.dp)
                                    .fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    when (deviceTier) {
                                        DeviceTier.FLAGSHIP -> "Phi-4 Mini"
                                        DeviceTier.MIDRANGE -> "Gemma-2.0"
                                        null -> "Loading…"
                                    },
                                    fontSize = 11.sp,
                                    color = VakilColors.TextSecondary
                                )
                            }
                        }

                        // Chip - Latency
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = VakilColors.Ink3,
                            modifier = Modifier
                                .height(32.dp)
                                .border(1.dp, VakilColors.Border2, RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                Modifier
                                    .padding(horizontal = 12.dp)
                                    .fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "Latency: ${if (lastLatencyMs > 0) "${lastLatencyMs}ms" else "—"}",
                                    fontSize = 11.sp,
                                    color = VakilColors.TextSecondary
                                )
                            }
                        }

                        // Info chip
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = VakilColors.Ink3,
                            modifier = Modifier
                                .height(32.dp)
                                .border(1.dp, VakilColors.Border2, RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                Modifier
                                    .padding(horizontal = 12.dp)
                                    .fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text("${totalChunks}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = VakilColors.Orange)
                                Spacer(Modifier.width(2.dp))
                                Text(">", fontSize = 10.sp, color = VakilColors.TextTertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── ENHANCED EMPTY STATE ──────────────────────────────────────────────────────

@Composable
fun EnhancedEmptyState(onUploadClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 600.dp)
    ) {
        // Hero icon/illustration
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            VakilColors.Orange.copy(alpha = 0.15f),
                            VakilColors.Orange.copy(alpha = 0.05f)
                        ),
                        radius = 100f
                    )
                )
                .border(2.dp, VakilColors.Orange.copy(alpha = 0.3f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = VakilColors.Orange
            )
        }

        // Headline
        Text(
            "Upload your first legal document",
            style = MaterialTheme.typography.displaySmall,
            color = VakilColors.TextPrimary,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
            lineHeight = 36.sp
        )

        // Subtitle
        Text(
            "PDFs processed instantly with AI summaries",
            style = MaterialTheme.typography.bodyLarge,
            color = VakilColors.TextSecondary,
            textAlign = TextAlign.Center,
            fontSize = 15.sp,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(12.dp))

        // Hero Upload Button - Full Width
        Button(
            onClick = onUploadClick,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(56.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = VakilColors.Orange.copy(alpha = 0.3f),
                    spotColor = VakilColors.Orange.copy(alpha = 0.2f)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VakilColors.Orange,
                contentColor = Color.White
            ),
            elevation = ButtonDefaults.elevatedButtonElevation(
                defaultElevation = 8.dp,
                pressedElevation = 12.dp,
                hoveredElevation = 10.dp
            )
        ) {
            Icon(
                Icons.Default.CloudUpload,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Upload PDF",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        // Additional info
        Text(
            "All processing happens on-device — your data stays private",
            style = MaterialTheme.typography.bodySmall,
            color = VakilColors.TextTertiary,
            textAlign = TextAlign.Center,
            fontSize = 12.sp
        )
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
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val sidebarWidth = minOf(300.dp, screenWidth * 0.3f)
    
    Surface(
        modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
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
    // Memoize color lookups to avoid recalculation
    val colors = remember(document.riskLevel) {
        when (document.riskLevel) {
            "HIGH" -> VakilColors.RiskHigh to VakilColors.RiskHighBg
            "MED"  -> VakilColors.RiskMed to VakilColors.RiskMedBg
            else   -> VakilColors.RiskLow to VakilColors.RiskLowBg
        }
    }
    val (riskColor, riskBg) = colors

    // Memoize border to avoid recreation
    val activeBorder = remember(isActive) {
        if (isActive) BorderStroke(1.dp, VakilColors.Gold.copy(alpha = 0.3f)) else null
    }

    Surface(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect),
        color  = if (isActive) VakilColors.Ink3 else Color.Transparent,
        shape  = RoundedCornerShape(10.dp),
        border = activeBorder,
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
fun DeviceStatsRow(totalChunks: Int, lastLatencyMs: Long, deviceTier: DeviceTier?, modifier: Modifier = Modifier) {
    val modelName = when (deviceTier) {
        DeviceTier.FLAGSHIP -> "Phi-4 Mini"
        DeviceTier.MIDRANGE -> "Gemma-3 2B"
        null                -> "Loading…"
    }
    Row(
        modifier.fillMaxWidth().padding(10.dp),
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
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isMobile = screenWidth < 600.dp
    
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isMobile) 12.dp else 16.dp),
            modifier = Modifier.padding(if (isMobile) 20.dp else 40.dp)
        ) {
            Box(
                Modifier.size(if (isMobile) 64.dp else 72.dp).clip(RoundedCornerShape(16.dp))
                    .background(VakilColors.GoldBg)
                    .border(1.dp, VakilColors.Gold.copy(alpha=0.3f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("V", 
                    style = MaterialTheme.typography.displayLarge, 
                    color = VakilColors.Gold,
                    fontSize = if (isMobile) 32.sp else 48.sp)
            }

            Text("VakilDoot", 
                style = MaterialTheme.typography.displayMedium,
                color = VakilColors.TextPrimary,
                fontSize = if (isMobile) 28.sp else 40.sp)

            Text(
                "Upload a legal document to begin. Everything is processed on your device — zero cloud.",
                style = MaterialTheme.typography.bodyMedium,
                color = VakilColors.TextSecondary,
                modifier = Modifier.widthIn(max = if (isMobile) 300.dp else 380.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = if (isMobile) 13.sp else 16.sp,
            )

            if (!isMobile) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        Triple("01", "Upload PDF", "On-device parsing"),
                        Triple("02", "RAG indexing", "Embeddings"),
                        Triple("03", "Query Phi-4", "Accelerated"),
                    ).forEach { (num, title, desc) ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = VakilColors.Ink2,
                            border = BorderStroke(1.dp, VakilColors.Border),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(num, 
                                    style = MaterialTheme.typography.displayMedium,
                                    color = VakilColors.Gold.copy(alpha=0.35f),
                                    fontSize = 20.sp)
                                Text(title, 
                                    style = MaterialTheme.typography.titleMedium,
                                    color = VakilColors.TextPrimary)
                                Text(desc, 
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VakilColors.TextTertiary, 
                                    modifier = Modifier.padding(top=3.dp),
                                    fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onUploadClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VakilColors.Gold, contentColor = VakilColors.Ink
                ),
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = if (isMobile) 20.dp else 24.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isMobile) "Upload document" else "Upload your first document →",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
    runtimeWarning: String,
    onUploadClick: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val maxMessageWidth = minOf(500.dp, screenWidth * 0.85f)

    // Memoize callbacks to prevent lambda recreation on every recomposition
    val handleSend = remember(onSend) {
        { 
            if (inputText.isNotBlank()) {
                onSend(inputText)
                inputText = ""
            }
        }
    }

    LaunchedEffect(messages.size, streaming) {
        if (messages.isNotEmpty() || streaming.isNotEmpty()) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount)
        }
    }

    Column(modifier.fillMaxSize()) {
        // Header
        Surface(color = VakilColors.Ink2) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(VakilColors.GoldBg)
                        .border(1.dp, VakilColors.Gold.copy(alpha=0.3f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("PDF", style = MaterialTheme.typography.labelSmall, color = VakilColors.Gold, fontSize = 9.sp) }

                Column(Modifier.weight(1f)) {
                    Text(document.displayName, 
                        style = MaterialTheme.typography.labelLarge,
                        color = VakilColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp)
                    Text("${document.chunkCount} chunks · ${document.pageCount} pages",
                        style = MaterialTheme.typography.labelSmall, color = VakilColors.TextTertiary,
                        fontSize = 10.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = onUploadClick, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add PDF",
                            tint = VakilColors.Gold,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Box(Modifier.size(6.dp).clip(CircleShape).background(VakilColors.RiskLow))
                    Text("Active", style = MaterialTheme.typography.labelSmall,
                        color = VakilColors.TextTertiary, fontSize = 9.sp)
                }
            }
            Divider(color = VakilColors.Border, thickness = 0.5.dp)
        }

        // Messages
        LazyColumn(
            state   = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            if (runtimeWarning.isNotBlank()) {
                item {
                    Surface(
                        color = VakilColors.GoldBg,
                        border = BorderStroke(1.dp, VakilColors.Gold.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = runtimeWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = VakilColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            if (messages.isEmpty()) {
                item {
                    WelcomeChatMessage(document = document)
                }
            }
            items(messages, key = { it.id }) { msg ->
                MessageBubble(message = msg, maxWidth = maxMessageWidth)
            }
            if (chatState is ChatState.Thinking) {
                item { TypingIndicator() }
            }
            if (streaming.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AvatarBubble(label = "V")
                        StreamingBubble(text = streaming, maxWidth = maxMessageWidth)
                    }
                }
            }
        }

        // Suggestions
        if (messages.isEmpty()) {
            LazyRow(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val sugs = listOf(
                    "Summarise",
                    "Risks?",
                    "Termination",
                    "Liability",
                    "Payments",
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
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value         = inputText,
                    onValueChange = { inputText = it },
                    modifier      = Modifier.weight(1f).heightIn(min = 44.dp),
                    placeholder   = {
                        Text("Ask about document…",
                            style = MaterialTheme.typography.bodySmall,
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
                    textStyle= MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                )
                Button(
                    onClick  = handleSend,
                    enabled  = inputText.isNotBlank() && chatState !is ChatState.Thinking,
                    modifier = Modifier.size(44.dp),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = VakilColors.Gold,
                        contentColor   = VakilColors.Ink,
                        disabledContainerColor = VakilColors.Gold.copy(alpha=0.3f),
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Default.Send, "Send", modifier = Modifier.size(20.dp))
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
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Document indexed. ${document.chunkCount} chunks embedded into your local vector store.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VakilColors.TextPrimary,
                )
                
                // Show summary if available
                if (document.summary.isNotBlank()) {
                    Divider(color = VakilColors.Border2, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "📋 Summary",
                        style = MaterialTheme.typography.labelSmall,
                        color = VakilColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = document.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = VakilColors.TextSecondary,
                        maxLines = 5,
                    )
                }
                
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
fun MessageBubble(message: ChatMessage, maxWidth: Dp = 480.dp) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) AvatarBubble(label = "V")
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.widthIn(max = maxWidth)) {
            Surface(
                shape = if (isUser) RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp)
                        else         RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp),
                color = if (isUser) VakilColors.Gold else VakilColors.Ink3,
                border = if (!isUser) BorderStroke(1.dp, VakilColors.Border) else null,
            ) {
                Text(
                    text     = message.content,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = if (isUser) VakilColors.Ink else VakilColors.TextPrimary,
                    modifier = Modifier.padding(10.dp, 8.dp),
                )
            }

            // Source citations
            if (!isUser && message.sourceClauses.isNotBlank()) {
                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.sourceClauses.split(",").forEach { clause ->
                        Text(
                            text  = "§ $clause",
                            style = MaterialTheme.typography.labelSmall,
                            color = VakilColors.TextTertiary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(VakilColors.Ink4)
                                .border(1.dp, VakilColors.Border2, RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp
                        )
                    }
                }
            }

            // Latency
            if (!isUser && message.latencyMs > 0) {
                Text("${message.latencyMs}ms · ${message.tokensUsed} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = VakilColors.TextTertiary,
                    modifier = Modifier.padding(top = 3.dp),
                    fontSize = 9.sp)
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
fun StreamingBubble(text: String, maxWidth: Dp = 480.dp) {
    Surface(
        shape  = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp),
        color  = VakilColors.Ink3,
        border = BorderStroke(1.dp, VakilColors.Border),
        modifier = Modifier.widthIn(max = maxWidth),
    ) {
        Row(Modifier.padding(10.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = VakilColors.TextPrimary)
            // Blinking cursor
            Text("▌", color = VakilColors.Gold, fontSize = 12.sp)
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
        shape    = RoundedCornerShape(16.dp),
        color    = VakilColors.Ink3,
        border   = BorderStroke(1.dp, VakilColors.Border2),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = VakilColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 11.sp)
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
