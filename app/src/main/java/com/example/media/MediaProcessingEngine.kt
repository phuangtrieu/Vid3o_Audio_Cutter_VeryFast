package com.example.media

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import java.io.*
import java.nio.ByteBuffer

object MediaProcessingEngine {
    private const val TAG = "MediaProcessingEngine"

    /**
     * Trims an MP4 video file and outputs a trimmed MP4 file.
     * Maintains identical quality by doing direct track copying (no transcoding).
     */
    fun trimMp4(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit
    ) {
        val startTimeUs = startMs * 1000
        val endTimeUs = endMs * 1000

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IOException("Failed to open input URI")

            val trackCount = extractor.trackCount
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = HashMap<Int, Int>()
            var maxInputSize = 0

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val dstIndex = muxer.addTrack(format)
                    trackMap[i] = dstIndex

                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val inputSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (inputSize > maxInputSize) {
                            maxInputSize = inputSize
                        }
                    }
                }
            }

            if (maxInputSize == 0) {
                maxInputSize = 1024 * 1024 // 1MB fallback
            }

            muxer.start()

            // Seek to start time
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buffer = ByteBuffer.allocate(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()

            val trackOffsets = HashMap<Int, Long>()
            val totalDurationUs = (endTimeUs - startTimeUs).toFloat()
            var lastProgress = 0f

            while (true) {
                val sampleTrackIndex = extractor.sampleTrackIndex
                if (sampleTrackIndex < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endTimeUs) {
                    extractor.advance()
                    continue
                }

                if (sampleTimeUs >= startTimeUs) {
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    bufferInfo.presentationTimeUs = sampleTimeUs
                    bufferInfo.flags = extractor.sampleFlags

                    val offset = trackOffsets.getOrPut(sampleTrackIndex) { sampleTimeUs }
                    val adjustedTimeUs = sampleTimeUs - offset

                    if (adjustedTimeUs >= 0) {
                        bufferInfo.presentationTimeUs = adjustedTimeUs
                        val dstTrackIndex = trackMap[sampleTrackIndex]
                        if (dstTrackIndex != null) {
                            muxer.writeSampleData(dstTrackIndex, buffer, bufferInfo)
                        }
                    }

                    if (totalDurationUs > 0) {
                        val currentProgress = (sampleTimeUs - startTimeUs).toFloat() / totalDurationUs
                        val roundedProgress = (currentProgress.coerceIn(0f, 1f) * 100).toInt() / 100f
                        if (roundedProgress != lastProgress) {
                            lastProgress = roundedProgress
                            onProgress(lastProgress)
                        }
                    }
                }

                extractor.advance()
            }

            onProgress(1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming MP4", e)
            throw e
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) { /* ignore */ }
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * Decodes any audio track in a media file to raw PCM and writes a high fidelity WAV file.
     */
    fun decodeAudioToWav(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit
    ) {
        val startTimeUs = startMs * 1000
        val endTimeUs = endMs * 1000

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var rawAudioFile: File? = null
        var rawOutputStream: FileOutputStream? = null

        try {
            context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IOException("Failed to open input URI")

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                throw IOException("No audio track found in the selected file")
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            rawAudioFile = File(context.cacheDir, "temp_raw_audio.pcm")
            rawOutputStream = FileOutputStream(rawAudioFile)

            var sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val bufferInfo = MediaCodec.BufferInfo()
            var isExtractorEOS = false
            var isDecoderEOS = false
            var totalPcmBytes = 0L

            val totalDurationUs = (endTimeUs - startTimeUs).toFloat()
            var lastProgress = 0f

            while (!isDecoderEOS) {
                if (!isExtractorEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        val sampleTimeUs = extractor.sampleTime

                        if (sampleSize < 0 || sampleTimeUs > endTimeUs) {
                            codec.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isExtractorEOS = true
                        } else {
                            codec.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                sampleTimeUs,
                                0
                            )
                            extractor.advance()

                            if (totalDurationUs > 0) {
                                val currentProgress = (sampleTimeUs - startTimeUs).toFloat() / totalDurationUs
                                val roundedProgress = (currentProgress.coerceIn(0f, 1f) * 100).toInt() / 100f
                                if (roundedProgress != lastProgress) {
                                    lastProgress = roundedProgress
                                    onProgress(lastProgress * 0.9f)
                                }
                            }
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                    
                    if (bufferInfo.presentationTimeUs >= startTimeUs && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(chunk)
                        rawOutputStream.write(chunk)
                        totalPcmBytes += chunk.size
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }

            rawOutputStream.flush()
            rawOutputStream.close()
            rawOutputStream = null

            onProgress(0.95f)
            writeWavFile(rawAudioFile, outputFile, sampleRate, channelCount, totalPcmBytes)
            onProgress(1.0f)

        } catch (e: Exception) {
            Log.e(TAG, "Error converting to WAV", e)
            throw e
        } finally {
            try { extractor.release() } catch (e: Exception) {}
            try { codec?.stop(); codec?.release() } catch (e: Exception) {}
            try { rawOutputStream?.close() } catch (e: Exception) {}
            try { rawAudioFile?.delete() } catch (e: Exception) {}
        }
    }

    private fun writeWavFile(
        pcmFile: File,
        wavFile: File,
        sampleRate: Int,
        channels: Int,
        totalPcmBytes: Long
    ) {
        val header = ByteArray(44)
        val totalDataLen = totalPcmBytes + 36
        val byteRate = sampleRate * channels * 2L

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalPcmBytes and 0xff).toByte()
        header[41] = ((totalPcmBytes shr 8) and 0xff).toByte()
        header[42] = ((totalPcmBytes shr 16) and 0xff).toByte()
        header[43] = ((totalPcmBytes shr 24) and 0xff).toByte()

        wavFile.outputStream().use { fos ->
            fos.write(header)
            pcmFile.inputStream().use { fis ->
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    /**
     * Fast-trims a WAV audio file by copying specific frame portions.
     */
    fun trimWav(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long,
        endMs: Long
    ) {
        context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
            val header = ByteArray(44)
            if (inputStream.read(header) < 44) {
                throw IOException("Invalid WAV file header")
            }

            val riff = String(header, 0, 4)
            val wave = String(header, 8, 4)
            if (riff != "RIFF" || wave != "WAVE") {
                throw IOException("Format input is not a valid RIFF/WAVE audio")
            }

            val channels = ((header[23].toInt() and 0xff) shl 8) or (header[22].toInt() and 0xff)
            val sampleRate = ((header[27].toInt() and 0xff) shl 24) or
                             ((header[26].toInt() and 0xff) shl 16) or
                             ((header[25].toInt() and 0xff) shl 8) or
                             (header[24].toInt() and 0xff)
            val bitsPerSample = ((header[35].toInt() and 0xff) shl 8) or (header[34].toInt() and 0xff)
            
            val bytesPerSample = bitsPerSample / 8
            val frameSize = channels * bytesPerSample
            val byteRate = sampleRate * frameSize

            val startByteOffset = (startMs * byteRate / 1000).let { it - (it % frameSize) }
            val endByteOffset = (endMs * byteRate / 1000).let { it - (it % frameSize) }
            val bytesToCopy = (endByteOffset - startByteOffset).coerceAtLeast(0)

            val newPcmSize = bytesToCopy
            val newTotalSize = newPcmSize + 36

            val newHeader = header.clone()
            newHeader[4] = (newTotalSize and 0xff).toByte()
            newHeader[5] = ((newTotalSize ushr 8) and 0xff).toByte()
            newHeader[6] = ((newTotalSize ushr 16) and 0xff).toByte()
            newHeader[7] = ((newTotalSize ushr 24) and 0xff).toByte()

            newHeader[40] = (newPcmSize and 0xff).toByte()
            newHeader[41] = ((newPcmSize ushr 8) and 0xff).toByte()
            newHeader[42] = ((newPcmSize ushr 16) and 0xff).toByte()
            newHeader[43] = ((newPcmSize ushr 24) and 0xff).toByte()

            outputFile.outputStream().use { fos ->
                fos.write(newHeader)

                var skipped = 0L
                while (skipped < startByteOffset) {
                    val sk = inputStream.skip(startByteOffset - skipped)
                    if (sk <= 0) break
                    skipped += sk
                }

                val buffer = ByteArray(4096)
                var bytesRemaining = bytesToCopy
                while (bytesRemaining > 0) {
                    val limit = bytesRemaining.coerceAtMost(buffer.size.toLong()).toInt()
                    val read = inputStream.read(buffer, 0, limit)
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    bytesRemaining -= read
                }
            }
        } ?: throw IOException("Could not open input audio file stream")
    }

    /**
     * Trims an MP3 file directly by reading MP3 frames and copying targeted ranges.
     */
    fun trimMp3(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit
    ) {
        val startTimeUs = startMs * 1000
        val endTimeUs = endMs * 1000

        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IOException("Failed to load input audio source")

            var audioTrackIndex = -1
            var maxBufferSize = 4096 * 4

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    extractor.selectTrack(i)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxBufferSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    }
                    break
                }
            }

            if (audioTrackIndex == -1) {
                throw IOException("No audio track detected in input MP3 source")
            }

            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val totalDurationUs = (endTimeUs - startTimeUs).toFloat()
            var lastProgress = 0f

            outputFile.outputStream().use { fos ->
                while (true) {
                    val trackIndex = extractor.sampleTrackIndex
                    if (trackIndex < 0) break

                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs > endTimeUs) {
                        break
                    }

                    if (sampleTimeUs >= startTimeUs) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize > 0) {
                            val data = ByteArray(sampleSize)
                            buffer.position(0)
                            buffer.get(data)
                            fos.write(data)

                            if (totalDurationUs > 0) {
                                val currentProgress = (sampleTimeUs - startTimeUs).toFloat() / totalDurationUs
                                val roundedProgress = (currentProgress.coerceIn(0f, 1f) * 100).toInt() / 100f
                                if (roundedProgress != lastProgress) {
                                    lastProgress = roundedProgress
                                    onProgress(lastProgress)
                                }
                            }
                        }
                    }
                    extractor.advance()
                }
            }
            onProgress(1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming MP3", e)
            throw e
        } finally {
            try { extractor.release() } catch (e: Exception) {}
        }
    }

    /**
     * Extracts raw audio track from MP4 and muxes it as M4A (high quality AAC)
     * Named appropriately with target formats.
     */
    fun extractAudioFromVideo(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit
    ) {
        val startTimeUs = startMs * 1000
        val endTimeUs = endMs * 1000

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IOException("Failed to load video source")

            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIdx == -1 || audioFormat == null) {
                throw IOException("No audio track detected in MP4 source")
            }

            extractor.selectTrack(audioTrackIdx)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val addedTrackIdx = muxer.addTrack(audioFormat)

            var maxInputSize = 1024 * 512
            if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxInputSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }

            muxer.start()
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buffer = ByteBuffer.allocate(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var firstSampleTimeUs = -1L

            val totalDurationUs = (endTimeUs - startTimeUs).toFloat()
            var lastProgress = 0f

            while (true) {
                val sampleTrackIndex = extractor.sampleTrackIndex
                if (sampleTrackIndex < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endTimeUs) {
                    break
                }

                if (sampleTimeUs >= startTimeUs) {
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    bufferInfo.flags = extractor.sampleFlags

                    if (firstSampleTimeUs == -1L) {
                        firstSampleTimeUs = sampleTimeUs
                    }
                    bufferInfo.presentationTimeUs = sampleTimeUs - firstSampleTimeUs

                    if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= 0) {
                        muxer.writeSampleData(addedTrackIdx, buffer, bufferInfo)

                        if (totalDurationUs > 0) {
                            val currentProgress = (sampleTimeUs - startTimeUs).toFloat() / totalDurationUs
                            val roundedProgress = (currentProgress.coerceIn(0f, 1f) * 100).toInt() / 100f
                            if (roundedProgress != lastProgress) {
                                  lastProgress = roundedProgress
                                  onProgress(lastProgress)
                            }
                        }
                    }
                }
                extractor.advance()
            }
            onProgress(1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting audio track", e)
            throw e
        } finally {
            try { extractor.release() } catch (e: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {}
        }
    }
}
