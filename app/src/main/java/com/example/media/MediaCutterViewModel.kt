package com.example.media

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

sealed class ProcessStatus {
    object Idle : ProcessStatus()
    object Processing : ProcessStatus()
    data class Success(val outPath: String) : ProcessStatus()
    data class Error(val message: String) : ProcessStatus()
}

class MediaCutterViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MediaCutterViewModel"
    private val context = application.applicationContext

    // File selection states
    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri = _selectedUri.asStateFlow()

    private val _fileName = MutableStateFlow("")
    val fileName = _fileName.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _isVideo = MutableStateFlow(false)
    val isVideo = _isVideo.asStateFlow()

    // Trimming range states
    private val _startMs = MutableStateFlow(0L)
    val startMs = _startMs.asStateFlow()

    private val _endMs = MutableStateFlow(0L)
    val endMs = _endMs.asStateFlow()

    // Player position syncing
    private val _playerPositionMs = MutableStateFlow(0L)
    val playerPositionMs = _playerPositionMs.asStateFlow()

    // Output Directory Setup
    private val _outputDirectoryUri = MutableStateFlow<Uri?>(null)
    val outputDirectoryUri = _outputDirectoryUri.asStateFlow()

    private val _outputDirectoryName = MutableStateFlow("Thư mục mặc định (Tải về)")
    val outputDirectoryName = _outputDirectoryName.asStateFlow()

    // Format choices
    private val _selectedFormat = MutableStateFlow("mp4")
    val selectedFormat = _selectedFormat.asStateFlow()

    private val _availableFormats = MutableStateFlow(listOf("mp4", "mp3", "wav"))
    val availableFormats = _availableFormats.asStateFlow()

    // Processing feedback
    private val _processStatus = MutableStateFlow<ProcessStatus>(ProcessStatus.Idle)
    val processStatus = _processStatus.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _statusText = MutableStateFlow("Vui lòng chọn file video hoặc file âm thanh.")
    val statusText = _statusText.asStateFlow()

    init {
        createNotificationChannel()
    }

    /**
     * Resolves the picked file, queries Metadata and initializes defaults.
     */
    fun selectFile(uri: Uri) {
        _selectedUri.value = uri
        _processStatus.value = ProcessStatus.Idle
        _progress.value = 0f
        _statusText.value = "Đang tải thông tin file..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Read display name
                var name = "File_không_xác_định"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        name = cursor.getString(nameIndex)
                    }
                }
                _fileName.value = name

                // Extract Audio/Video metadata
                val retriever = MediaMetadataRetriever()
                var duration = 0L
                var videoDetected = false

                try {
                    retriever.setDataSource(context, uri)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    if (durStr != null) {
                        duration = durStr.toLong()
                    }
                    val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    videoDetected = hasVideo != null && hasVideo.contains("yes", ignoreCase = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi lấy metadata", e)
                    videoDetected = name.lowercase().endsWith(".mp4") || name.lowercase().endsWith(".mkv") || name.lowercase().endsWith(".mov")
                    duration = 30000L // 30s fallback
                } finally {
                    try { retriever.release() } catch (ex: Exception) {}
                }

                _durationMs.value = duration
                _isVideo.value = videoDetected
                _startMs.value = 0L
                _endMs.value = duration

                // Update formats based on input type
                if (videoDetected) {
                    _availableFormats.value = listOf("mp4", "mp3", "wav")
                    _selectedFormat.value = "mp4"
                } else {
                    _availableFormats.value = listOf("mp3", "wav")
                    _selectedFormat.value = "mp3"
                }

                _statusText.value = "Nạp tệp thành công! Hãy chọn khoảng thời gian cần cắt."
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving media", e)
                _statusText.value = "Lỗi nạp tệp: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Updates selected crop values.
     */
    fun updateTrimRange(start: Long, end: Long) {
        _startMs.value = start
        _endMs.value = end
    }

    /**
     * Updates formatting choice.
     */
    fun setExportFormat(format: String) {
        _selectedFormat.value = format
    }

    /**
     * Updates selected output directory Uri and queries folder name using native DocumentsContract.
     */
    fun setOutputDirectory(uri: Uri) {
        _outputDirectoryUri.value = uri
        var folderName = "Thư mục tùy chỉnh"
        try {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            context.contentResolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    folderName = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            folderName = uri.path?.substringAfterLast('/') ?: "Thư mục tùy chỉnh"
        }
        _outputDirectoryName.value = folderName
        _statusText.value = "Đã đổi thư mục xuất sang: $folderName"
    }

    /**
     * Initiates the media trim/cut and format extraction.
     */
    fun performCut() {
        val uri = _selectedUri.value
        if (uri == null) {
            _statusText.value = "Chưa chọn file đầu vào!"
            return
        }

        val start = _startMs.value
        val end = _endMs.value
        if (start >= end) {
            _statusText.value = "Thời điểm bắt đầu phải nhỏ hơn thời điểm kết thúc!"
            return
        }

        _processStatus.value = ProcessStatus.Processing
        _progress.value = 0f
        _statusText.value = "Đang xử lý media..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputFormat = _selectedFormat.value
                val rawName = _fileName.value.substringBeforeLast(".")
                val outputFileName = "${rawName}_trimmed_${System.currentTimeMillis()}.$inputFormat"

                val outputFolderUri = _outputDirectoryUri.value
                val outputFile: File
                var finalSaveUri: Uri? = null

                if (outputFolderUri != null) {
                    val mimeType = when (inputFormat) {
                        "mp3" -> "audio/mpeg"
                        "wav" -> "audio/x-wav"
                        else -> "video/mp4"
                    }
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(outputFolderUri, DocumentsContract.getTreeDocumentId(outputFolderUri))
                    val createdFileUri = DocumentsContract.createDocument(context.contentResolver, documentUri, mimeType, outputFileName)
                    if (createdFileUri != null) {
                        finalSaveUri = createdFileUri
                        outputFile = File(context.cacheDir, outputFileName)
                    } else {
                        throw IOException("Không tạo được file trong thư mục tùy chỉnh")
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (downloadsDir.exists() || downloadsDir.mkdirs()) {
                        outputFile = File(downloadsDir, outputFileName)
                        finalSaveUri = Uri.fromFile(outputFile)
                    } else {
                        outputFile = File(context.cacheDir, outputFileName)
                        finalSaveUri = Uri.fromFile(outputFile)
                    }
                }

                val isInputVideo = _isVideo.value

                Log.d(TAG, "Starting processing. InputIsVideo: $isInputVideo, TargetFormat: $inputFormat")

                when {
                    isInputVideo && inputFormat == "mp4" -> {
                        MediaProcessingEngine.trimMp4(
                            context, uri, outputFile, start, end
                        ) { progressValue ->
                            _progress.value = progressValue
                            _statusText.value = "Đang cắt video: ${(progressValue * 100).toInt()}%"
                        }
                    }
                    isInputVideo && inputFormat == "wav" -> {
                        MediaProcessingEngine.decodeAudioToWav(
                            context, uri, outputFile, start, end
                        ) { progressValue ->
                            _progress.value = progressValue
                            _statusText.value = "Đang giải mã và viết WAV: ${(progressValue * 100).toInt()}%"
                        }
                    }
                    isInputVideo && inputFormat == "mp3" -> {
                        MediaProcessingEngine.extractAudioFromVideo(
                            context, uri, outputFile, start, end
                        ) { progressValue ->
                            _progress.value = progressValue
                            _statusText.value = "Đang trích xuất nhạc audio: ${(progressValue * 100).toInt()}%"
                        }
                    }
                    !isInputVideo && inputFormat == "wav" -> {
                        val originalName = _fileName.value.lowercase()
                        if (originalName.endsWith(".wav")) {
                            MediaProcessingEngine.trimWav(context, uri, outputFile, start, end)
                        } else {
                            MediaProcessingEngine.decodeAudioToWav(
                                context, uri, outputFile, start, end
                            ) { progressValue ->
                                _progress.value = progressValue
                                _statusText.value = "Đang giải mã MP3 sang WAV: ${(progressValue * 100).toInt()}%"
                            }
                        }
                        _progress.value = 1.0f
                    }
                    !isInputVideo && inputFormat == "mp3" -> {
                        MediaProcessingEngine.trimMp3(
                            context, uri, outputFile, start, end
                        ) { progressValue ->
                            _progress.value = progressValue
                            _statusText.value = "Đang cắt file nhạc MP3: ${(progressValue * 100).toInt()}%"
                        }
                    }
                }

                if (outputFolderUri != null && finalSaveUri != null) {
                    context.contentResolver.openOutputStream(finalSaveUri)?.use { outStream ->
                        outputFile.inputStream().use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    try { outputFile.delete() } catch (e: Exception) {}
                }

                val savedName = finalSaveUri?.path ?: outputFileName
                _processStatus.value = ProcessStatus.Success(savedName)
                _statusText.value = "Cắt tệp thành công! File lưu tại: $savedName"
                _progress.value = 1.0f

                showCompletionNotification(outputFileName)

            } catch (e: Exception) {
                Log.e(TAG, "Error in background processing", e)
                _processStatus.value = ProcessStatus.Error(e.localizedMessage ?: "Lỗi không xác định")
                _statusText.value = "Gặp lỗi kỹ thuật: ${e.localizedMessage}"
                _progress.value = 0f
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Lịch sử Cắt Media"
            val descriptionText = "Thông báo trạng thái hoàn tất xử lý video và âm thanh"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("media_cutter_channel_id", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showCompletionNotification(fileName: String) {
        val notificationId = 1010
        val builder = NotificationCompat.Builder(context, "media_cutter_channel_id")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Xử lý hoàn tất! 🎉")
            .setContentText("File $fileName đã được lưu thành công.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = NotificationManagerCompat.from(context)
        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Cấp quyền gửi thông báo bị từ chối", e)
        }
    }
}
