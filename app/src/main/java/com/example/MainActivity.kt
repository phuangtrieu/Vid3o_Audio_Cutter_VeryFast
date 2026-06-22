package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.media.MediaCutterViewModel
import com.example.media.ProcessStatus
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val viewModel: MediaCutterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    MediaCutterScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaCutterScreen(
    viewModel: MediaCutterViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val selectedUri by viewModel.selectedUri.collectAsState()
    val fileName by viewModel.fileName.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val isVideo by viewModel.isVideo.collectAsState()
    val startMs by viewModel.startMs.collectAsState()
    val endMs by viewModel.endMs.collectAsState()

    val outputDirName by viewModel.outputDirectoryName.collectAsState()
    val selectedFormat by viewModel.selectedFormat.collectAsState()
    val availableFormats by viewModel.availableFormats.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val processStatus by viewModel.processStatus.collectAsState()

    val fileSelector = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.selectFile(uri)
        }
    }

    val directorySelector = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {}
            viewModel.setOutputDirectory(uri)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    var startInputText by remember { mutableStateOf("") }
    var endInputText by remember { mutableStateOf("") }

    LaunchedEffect(startMs) {
        val currentParsed = parseTimeStringToMs(startInputText)
        if (currentParsed != startMs) {
            startInputText = formatDurationWithMs(startMs)
        }
    }

    LaunchedEffect(endMs) {
        val currentParsed = parseTimeStringToMs(endInputText)
        if (currentParsed != endMs) {
            endInputText = formatDurationWithMs(endMs)
        }
    }

    LaunchedEffect(selectedUri) {
        if (selectedUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A071E),
                        Color(0xFF130D2E),
                        Color(0xFF070514)
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x2A9F5BFF), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(width * 0.85f, height * 0.2f),
                    radius = width * 0.8f
                ),
                radius = width * 0.8f,
                center = androidx.compose.ui.geometry.Offset(width * 0.85f, height * 0.2f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x1F00F2FE), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(width * 0.15f, height * 0.55f),
                    radius = width * 0.7f
                ),
                radius = width * 0.7f,
                center = androidx.compose.ui.geometry.Offset(width * 0.15f, height * 0.55f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x1FFF2A85), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(width * 0.75f, height * 0.85f),
                    radius = width * 0.65f
                ),
                radius = width * 0.65f,
                center = androidx.compose.ui.geometry.Offset(width * 0.75f, height * 0.85f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF8F5BFF), Color(0xFF00F2FE))
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCut,
                        contentDescription = "Cut Icon",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "FastCut Pro",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                        color = Color.White
                    )
                )
            }
            Text(
                text = "Cắt ghép Video - Audio giữ nguyên chất lượng",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.7f)
                ),
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            if (selectedUri == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.linearGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.18f),
                                        Color.White.copy(alpha = 0.02f),
                                        Color(0xFF8F5BFF).copy(alpha = 0.15f)
                                    )
                                )
                            ),
                            RoundedCornerShape(24.dp)
                        )
                        .clickable { fileSelector.launch("video/*,audio/*") }
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Chọn tệp",
                            tint = Color(0xFF00F2FE),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Chọn Video hoặc Audio",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Nhấp để duyệt tệp MP4, MP3 hoặc WAV từ thiết bị của bạn",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { fileSelector.launch("*/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8F5BFF)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.testTag("select_file_button")
                        ) {
                            Text("Mở Thư Viện", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.linearGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.15f),
                                        Color.White.copy(alpha = 0.02f)
                                    )
                                )
                            ),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isVideo) Icons.Default.VideoLibrary else Icons.Default.AudioFile,
                        contentDescription = "Tệp đang chọn",
                        tint = if (isVideo) Color(0xFF00F2FE) else Color(0xFFD0BCFF),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White.copy(alpha = 0.12f),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = if (isVideo) "VIDEO (MP4)" else "AUDIO",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isVideo) Color(0xFF00F2FE) else Color(0xFFD0BCFF),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "Độ dài: ${formatDuration(durationMs)}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }
                    IconButton(
                        onClick = { fileSelector.launch("*/*") },
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                            .testTag("reload_file_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Đổi tệp", tint = Color.White)
                    }
                }
            }

            selectedUri?.let { mediaUri ->
                MediaPreviewCard(
                    uri = mediaUri,
                    isVideo = isVideo,
                    startMs = startMs,
                    endMs = endMs
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Khu vực kéo thả Trim Cắt:",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                textAlign = TextAlign.Start
            )
            
            MediaTrimSlider(
                startMs = startMs,
                endMs = endMs,
                durationMs = durationMs,
                onRangeChanged = { newStart, newEnd ->
                    viewModel.updateTrimRange(newStart, newEnd)
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = startInputText,
                    onValueChange = { newVal ->
                        startInputText = newVal
                        val parsed = parseTimeStringToMs(newVal)
                        if (parsed != null && parsed >= 0 && parsed < endMs) {
                            viewModel.updateTrimRange(parsed, endMs)
                        }
                    },
                    label = { Text("BẮT ĐẦU (START)", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = Color(0xFF00F2FE),
                        fontWeight = FontWeight.Bold
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("start_trim_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00F2FE),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedLabelColor = Color(0xFF00F2FE),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                        focusedContainerColor = Color.White.copy(alpha = 0.04f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                    ),
                    placeholder = { Text("00:00.0", color = Color.White.copy(alpha = 0.3f)) }
                )

                OutlinedTextField(
                    value = endInputText,
                    onValueChange = { newVal ->
                        endInputText = newVal
                        val parsed = parseTimeStringToMs(newVal)
                        if (parsed != null && parsed > startMs && parsed <= durationMs) {
                            viewModel.updateTrimRange(startMs, parsed)
                        }
                    },
                    label = { Text("KẾT THÚC (END)", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = Color(0xFFD0BCFF),
                        fontWeight = FontWeight.Bold
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("end_trim_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFD0BCFF),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedLabelColor = Color(0xFFD0BCFF),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                        focusedContainerColor = Color.White.copy(alpha = 0.04f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                    ),
                    placeholder = { Text("00:00.0", color = Color.White.copy(alpha = 0.3f)) }
                )
            }

            Text(
                text = "💡 Nhập thời gian: mm:ss (ví dụ 01:30) hoặc mm:ss.S (ví dụ 01:30.5) hoặc giây (ví dụ 90)",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = TextAlign.Start
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.02f)
                        )
                    )
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Thiết Lập Đầu Ra",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Left Column: Output Format Selection
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "ĐỊNH DẠNG XUẤT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                            
                            var expandedFormatDropdown by remember { mutableStateOf(false) }

                            Box(modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = { expandedFormatDropdown = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("format_dropdown_trigger")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = selectedFormat.uppercase(Locale.getDefault()),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            contentDescription = "Dropdown indicators",
                                            tint = Color.White
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = expandedFormatDropdown,
                                    onDismissRequest = { expandedFormatDropdown = false },
                                    modifier = Modifier.background(Color(0xFF1E1B38))
                                ) {
                                    availableFormats.forEach { format ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    format.uppercase(Locale.getDefault()),
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            },
                                            onClick = {
                                                viewModel.setExportFormat(format)
                                                expandedFormatDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Right Column: Dynamic Static Quality Indicator
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "CHẤT LƯỢNG",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.6f)
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = "Gốc (Source)",
                                    fontSize = 14.sp,
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Thư mục lưu:",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = outputDirName,
                            onValueChange = {},
                            readOnly = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("output_path_textbox"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFD0BCFF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedContainerColor = Color.White.copy(alpha = 0.04f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                            )
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Button(
                            onClick = { directorySelector.launch(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(56.dp)
                                .testTag("change_folder_button"),
                            contentPadding = PaddingValues(horizontal = 14.dp)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = "Đổi thư mục", tint = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.02f)
                        )
                    )
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (processStatus is ProcessStatus.Processing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.2.dp,
                                color = Color(0xFF00F2FE)
                            )
                        } else {
                            Icon(
                                imageVector = if (processStatus is ProcessStatus.Success) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = "Trạng thái",
                                tint = if (processStatus is ProcessStatus.Success) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Trạng thái:",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = statusText,
                        onValueChange = {},
                        readOnly = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF00F2FE),
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .testTag("status_textbox"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.White.copy(alpha = 0.04f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                        )
                    )

                    if (progress > 0f || processStatus is ProcessStatus.Processing) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .testTag("cut_progress_bar"),
                                color = Color(0xFF8F5BFF),
                                trackColor = Color.White.copy(alpha = 0.15f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            val cutButtonGradient = if (processStatus !is ProcessStatus.Processing) {
                Brush.linearGradient(
                    colors = listOf(Color(0xFF8F5BFF), Color(0xFF00F2FE))
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(Color(0x3BFFFFFF), Color(0x1BFFFFFF))
                )
            }

            Button(
                onClick = { viewModel.performCut() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(if (processStatus !is ProcessStatus.Processing) 12.dp else 0.dp, RoundedCornerShape(28.dp))
                    .background(cutButtonGradient, RoundedCornerShape(28.dp))
                    .testTag("cut_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                ),
                enabled = processStatus !is ProcessStatus.Processing,
                shape = RoundedCornerShape(28.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ContentCut,
                        contentDescription = "Cut icon button",
                        tint = if (processStatus is ProcessStatus.Processing) Color.White.copy(alpha = 0.4f) else Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Bắt đầu cắt (Cut)",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (processStatus is ProcessStatus.Processing) Color.White.copy(alpha = 0.4f) else Color.White
                        )
                    )
                }
            }

            AnimatedVisibility(
                visible = processStatus is ProcessStatus.Success,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f)), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Thành công",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Cắt ghép thành công! Đã lưu file.",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun MediaPreviewCard(
    uri: Uri,
    isVideo: Boolean,
    startMs: Long,
    endMs: Long
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableStateOf(startMs) }

    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    val mediaPlayer = remember(uri, isVideo) {
        if (!isVideo) {
            MediaPlayer().apply {
                try {
                    setDataSource(context, uri)
                    prepare()
                    seekTo(startMs.toInt())
                } catch (e: Exception) {}
            }
        } else {
            null
        }
    }

    LaunchedEffect(isPlaying, mediaPlayer, videoViewRef) {
        if (isPlaying) {
            if (isVideo) {
                try {
                    videoViewRef?.start()
                } catch (e: Exception) {}
            } else {
                try {
                    mediaPlayer?.start()
                } catch (e: Exception) {}
            }
        } else {
            if (isVideo) {
                try {
                    videoViewRef?.pause()
                } catch (e: Exception) {}
            } else {
                try {
                    mediaPlayer?.pause()
                } catch (e: Exception) {}
            }
        }
    }

    LaunchedEffect(isPlaying, isVideo, mediaPlayer, videoViewRef, startMs, endMs) {
        if (isPlaying) {
            while (isPlaying) {
                val pos = if (isVideo) {
                    try {
                        videoViewRef?.currentPosition?.toLong() ?: startMs
                    } catch (e: Exception) {
                        startMs
                    }
                } else {
                    try {
                        mediaPlayer?.currentPosition?.toLong() ?: startMs
                    } catch (e: Exception) {
                        startMs
                    }
                }

                if (pos >= endMs || pos < startMs - 2000L) {
                    if (isVideo) {
                        try {
                            videoViewRef?.seekTo(startMs.toInt())
                        } catch (e: Exception) {}
                    } else {
                        try {
                            mediaPlayer?.seekTo(startMs.toInt())
                        } catch (e: Exception) {}
                    }
                    currentPosMs = startMs
                } else {
                    currentPosMs = pos
                }
                delay(30)
            }
        }
    }

    LaunchedEffect(startMs) {
        currentPosMs = startMs
        if (isVideo) {
            try {
                videoViewRef?.seekTo(startMs.toInt())
            } catch (e: Exception) {}
        } else {
            try {
                mediaPlayer?.seekTo(startMs.toInt())
            } catch (e: Exception) {}
        }
    }

    DisposableEffect(uri, isVideo, mediaPlayer, videoViewRef) {
        onDispose {
            if (!isVideo) {
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                } catch (e: Exception) {}
            } else {
                try {
                    videoViewRef?.stopPlayback()
                } catch (e: Exception) {}
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.15f),
                    Color.White.copy(alpha = 0.02f)
                )
            )
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (isVideo) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    key(uri) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(uri)
                                    val mc = MediaController(ctx)
                                    mc.setAnchorView(this)
                                    setMediaController(mc)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = false
                                        mp.setVolume(1.0f, 1.0f)
                                        try {
                                            seekTo(startMs.toInt())
                                        } catch (e: Exception) {}
                                    }
                                    videoViewRef = this
                                }
                            },
                            update = { videoView ->
                                // State syncing is fully handled by our robust LaunchedEffects!
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.01f))
                            )
                        )
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    var animTick by remember { mutableStateOf(0f) }
                    LaunchedEffect(isPlaying) {
                        if (isPlaying) {
                            while (isPlaying) {
                                animTick += 0.2f
                                delay(30)
                            }
                        }
                    }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val widthPx = size.width
                        val heightPx = size.height
                        val lineCount = 36
                        val spacing = widthPx / lineCount
                        
                        for (i in 0 until lineCount) {
                            val x = i * spacing + spacing / 2
                            val distFromCenter = 1.0f - abs(i - lineCount / 2f) / (lineCount / 2f)
                            val amplitudeMultiplier = if (isPlaying) (sin(animTick + i * 0.5f) * 0.4f + 0.6f) else 0.15f
                            val barHeight = (heightPx * 0.6f) * distFromCenter * amplitudeMultiplier

                            drawLine(
                                color = if (isPlaying) Color(0xFF00F2FE) else Color.White.copy(alpha = 0.25f),
                                start = androidx.compose.ui.geometry.Offset(x, (heightPx - barHeight) / 2),
                                end = androidx.compose.ui.geometry.Offset(x, (heightPx + barHeight) / 2),
                                strokeWidth = 8f,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { isPlaying = !isPlaying },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8F5BFF)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("preview_play_button")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Phát trước",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isPlaying) "Tạm Dừng" else "Phát Thử", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Text(
                    text = "${formatDuration(currentPosMs)} / ${formatDuration(endMs)}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
    }
}

@Composable
fun MediaTrimSlider(
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    onRangeChanged: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val durationValue = durationMs.coerceAtLeast(1L).toFloat()
    var draggingStart by remember { mutableStateOf(false) }
    var draggingEnd by remember { mutableStateOf(false) }

    val currentStartMs by rememberUpdatedState(startMs)
    val currentEndMs by rememberUpdatedState(endMs)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val currentDurationValueState by rememberUpdatedState(durationValue)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .padding(vertical = 12.dp)
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }

        val startPct = (startMs.toFloat() / durationValue).coerceIn(0f, 1f)
        val endPct = (endMs.toFloat() / durationValue).coerceIn(0f, 1f)

        val startX = startPct * widthPx
        val endX = endPct * widthPx

        val activeColor = Color(0xFF00F2FE).copy(alpha = 0.25f)
        val handleColor = Color(0xFF8F5BFF)
        val trackBgColor = Color.White.copy(alpha = 0.08f)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(widthPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val currentDuration = currentDurationValueState
                            val selStartMs = currentStartMs
                            val selEndMs = currentEndMs
                            val pStart = (selStartMs.toFloat() / currentDuration).coerceIn(0f, 1f)
                            val pEnd = (selEndMs.toFloat() / currentDuration).coerceIn(0f, 1f)
                            val sX = pStart * widthPx
                            val eX = pEnd * widthPx

                            val distToStart = abs(offset.x - sX)
                            val distToEnd = abs(offset.x - eX)
                            if (distToStart < distToEnd && distToStart < 80f) {
                                draggingStart = true
                                draggingEnd = false
                            } else if (distToEnd < 80f) {
                                draggingEnd = true
                                draggingStart = false
                            } else {
                                draggingStart = false
                                draggingEnd = false
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val currentDuration = currentDurationValueState
                            val newX = change.position.x.coerceIn(0f, widthPx)
                            val targetMs = ((newX / widthPx) * currentDuration).toLong()

                            val selStartMs = currentStartMs
                            val selEndMs = currentEndMs

                            if (draggingStart) {
                                val limitEnd = selEndMs - 1000L
                                onRangeChanged(targetMs.coerceIn(0L, limitEnd.coerceAtLeast(0L)), selEndMs)
                            } else if (draggingEnd) {
                                val limitStart = selStartMs + 1000L
                                onRangeChanged(selStartMs, targetMs.coerceIn(limitStart.coerceAtLeast(0L), currentDurationMs))
                            }
                        },
                        onDragEnd = {
                            draggingStart = false
                            draggingEnd = false
                        },
                        onDragCancel = {
                            draggingStart = false
                            draggingEnd = false
                        }
                    )
                }
        ) {
            drawRoundRect(
                color = trackBgColor,
                topLeft = androidx.compose.ui.geometry.Offset(0f, heightPx / 2 - 8f),
                size = androidx.compose.ui.geometry.Size(widthPx, 16f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
            )

            drawRoundRect(
                color = activeColor,
                topLeft = androidx.compose.ui.geometry.Offset(startX, heightPx / 2 - 10f),
                size = androidx.compose.ui.geometry.Size((endX - startX).coerceAtLeast(0f), 20f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
            )

            drawRect(
                color = handleColor,
                topLeft = androidx.compose.ui.geometry.Offset(startX, 0f),
                size = androidx.compose.ui.geometry.Size(4f, heightPx)
            )
            drawRect(
                color = handleColor,
                topLeft = androidx.compose.ui.geometry.Offset(endX - 4f, 0f),
                size = androidx.compose.ui.geometry.Size(4f, heightPx)
            )

            val lineSpace = 16f
            var lX = startX + lineSpace
            while (lX < endX - 6f) {
                drawLine(
                    color = Color.White.copy(alpha = 0.18f),
                    start = androidx.compose.ui.geometry.Offset(lX, heightPx / 2 - 6f),
                    end = androidx.compose.ui.geometry.Offset(lX, heightPx / 2 + 6f),
                    strokeWidth = 3f
                )
                lX += lineSpace
            }

            drawCircle(
                color = handleColor,
                radius = 24f,
                center = androidx.compose.ui.geometry.Offset(startX, heightPx / 2)
            )
            drawCircle(
                color = Color.White,
                radius = 10f,
                center = androidx.compose.ui.geometry.Offset(startX, heightPx / 2)
            )

            drawCircle(
                color = handleColor,
                radius = 24f,
                center = androidx.compose.ui.geometry.Offset(endX, heightPx / 2)
            )
            drawCircle(
                color = Color.White,
                radius = 10f,
                center = androidx.compose.ui.geometry.Offset(endX, heightPx / 2)
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

fun formatDurationWithMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millisFraction = (ms % 1000) / 100
    return String.format(Locale.getDefault(), "%02d:%02d.%d", minutes, seconds, millisFraction)
}

fun parseTimeStringToMs(text: String): Long? {
    val cleanedText = text.trim()
    if (cleanedText.isEmpty()) return null
    try {
        if (cleanedText.contains(":")) {
            val parts = cleanedText.split(":")
            if (parts.size == 2) {
                val min = parts[0].toLongOrNull() ?: return null
                val secPart = parts[1]
                if (secPart.contains(".")) {
                    val secParts = secPart.split(".")
                    val sec = secParts[0].toLongOrNull() ?: return null
                    val msStr = secParts[1].padEnd(3, '0').take(3)
                    val ms = msStr.toLongOrNull() ?: 0L
                    return min * 60 * 1000 + sec * 1000 + ms
                } else {
                    val sec = secPart.toLongOrNull() ?: return null
                    return min * 60 * 1000 + sec * 1000
                }
            } else if (parts.size == 3) {
                val hr = parts[0].toLongOrNull() ?: return null
                val min = parts[1].toLongOrNull() ?: return null
                val secPart = parts[2]
                if (secPart.contains(".")) {
                    val secParts = secPart.split(".")
                    val sec = secParts[0].toLongOrNull() ?: return null
                    val msStr = secParts[1].padEnd(3, '0').take(3)
                    val ms = msStr.toLongOrNull() ?: 0L
                    return hr * 3600 * 1000 + min * 60 * 1000 + sec * 1000 + ms
                } else {
                    val sec = secPart.toLongOrNull() ?: return null
                    return hr * 3600 * 1000 + min * 60 * 1000 + sec * 1000
                }
            }
        } else {
            if (cleanedText.contains(".")) {
                val parts = cleanedText.split(".")
                val sec = parts[0].toLongOrNull() ?: return null
                val msStr = parts[1].padEnd(3, '0').take(3)
                val ms = msStr.toLongOrNull() ?: 0L
                return sec * 1000 + ms
            } else {
                val sec = cleanedText.toLongOrNull() ?: return null
                return sec * 1000
            }
        }
    } catch (e: Exception) {
        return null
    }
    return null
}
