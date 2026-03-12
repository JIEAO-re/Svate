package com.immersive.ui.agent.flow

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Uploads screenshots to GCS through a signed URL.
 *
 * The uploader keeps network work on Dispatchers.IO, limits concurrency, and
 * returns a gs:// URI that the cloud decision path can reference later.
 */
class GcsAsyncUploader(
    private val scope: CoroutineScope,
    private val config: GcsUploaderConfig = GcsUploaderConfig(),
) {
    companion object {
        private const val TAG = "GcsAsyncUploader"
        private const val CONTENT_TYPE_JPEG = "image/jpeg"
        private const val CONTENT_TYPE_PNG = "image/png"
        private const val CONTENT_TYPE_WEBP = "image/webp"
    }

    // Output streams.
    private val _uploadResults = MutableSharedFlow<UploadResult>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val uploadResults: SharedFlow<UploadResult> = _uploadResults.asSharedFlow()

    private val _state = MutableStateFlow(UploaderState.IDLE)
    val state: StateFlow<UploaderState> = _state.asStateFlow()

    // Internal state.
    private val uploadSemaphore = Semaphore(config.maxConcurrentUploads)
    private val pendingUploads = ConcurrentHashMap<String, Job>()
    private val uploadCounter = AtomicLong(0)

    // Signed URL endpoint provided by the cloud service.
    private var signedUrlEndpoint: String = config.signedUrlEndpoint

    fun setSignedUrlEndpoint(endpoint: String) {
        signedUrlEndpoint = endpoint
    }

    /** Upload a frame asynchronously. */
    fun uploadAsync(
        imageBytes: ByteArray,
        contentType: String = CONTENT_TYPE_JPEG,
        metadata: UploadMetadata = UploadMetadata(),
    ): UploadHandle {
        val uploadId = "upload_${System.currentTimeMillis()}_${uploadCounter.incrementAndGet()}"
        val handle = UploadHandle(uploadId)

        val job = scope.launch(Dispatchers.IO) {
            uploadSemaphore.acquire()
            try {
                _state.value = UploaderState.UPLOADING
                val result = executeUpload(uploadId, imageBytes, contentType, metadata)
                _uploadResults.emit(result)
            } finally {
                uploadSemaphore.release()
                pendingUploads.remove(uploadId)
                if (pendingUploads.isEmpty()) {
                    _state.value = UploaderState.IDLE
                }
            }
        }

        pendingUploads[uploadId] = job
        return handle
    }

    /** Upload a frame synchronously within the caller coroutine. */
    suspend fun uploadSync(
        imageBytes: ByteArray,
        contentType: String = CONTENT_TYPE_JPEG,
        metadata: UploadMetadata = UploadMetadata(),
    ): UploadResult {
        val uploadId = "upload_${System.currentTimeMillis()}_${uploadCounter.incrementAndGet()}"

        return withContext(Dispatchers.IO) {
            uploadSemaphore.acquire()
            try {
                executeUpload(uploadId, imageBytes, contentType, metadata)
            } finally {
                uploadSemaphore.release()
            }
        }
    }

    fun cancelUpload(uploadId: String) {
        pendingUploads[uploadId]?.cancel()
        pendingUploads.remove(uploadId)
    }

    fun cancelAll() {
        pendingUploads.values.forEach { it.cancel() }
        pendingUploads.clear()
        _state.value = UploaderState.IDLE
    }

    private suspend fun executeUpload(
        uploadId: String,
        imageBytes: ByteArray,
        contentType: String,
        metadata: UploadMetadata,
    ): UploadResult {
        var lastError: Exception? = null

        repeat(config.maxRetries) { attempt ->
            try {
                val signedUrlResponse = requestSignedUrl(contentType, metadata)
                if (signedUrlResponse == null) {
                    Log.w(TAG, "[$uploadId] Failed to get signed URL, attempt ${attempt + 1}")
                    lastError = Exception("Failed to get signed URL")
                    return@repeat
                }

                val uploadSuccess = uploadToGcs(
                    signedUrl = signedUrlResponse.signedUrl,
                    imageBytes = imageBytes,
                    contentType = contentType,
                )

                if (uploadSuccess) {
                    Log.d(TAG, "[$uploadId] Upload success: ${signedUrlResponse.gcsUri}")
                    return UploadResult(
                        uploadId = uploadId,
                        success = true,
                        gcsUri = signedUrlResponse.gcsUri,
                        metadata = metadata,
                    )
                } else {
                    Log.w(TAG, "[$uploadId] GCS upload failed, attempt ${attempt + 1}")
                    lastError = Exception("GCS upload failed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$uploadId] Upload error, attempt ${attempt + 1}", e)
                lastError = e
            }

            if (attempt < config.maxRetries - 1) {
                val delayMs = config.retryBaseDelayMs * (1 shl attempt)
                delay(delayMs)
            }
        }

        return UploadResult(
            uploadId = uploadId,
            success = false,
            gcsUri = null,
            metadata = metadata,
            errorMessage = lastError?.message,
        )
    }

    private suspend fun requestSignedUrl(
        contentType: String,
        metadata: UploadMetadata,
    ): SignedUrlResponse? {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(signedUrlEndpoint)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")

                // The API uses the same auth token as the other mobile-agent routes.
                val authToken = com.immersive.ui.BuildConfig.MOBILE_AGENT_AUTH_TOKEN
                if (authToken.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $authToken")
                }

                connection.connectTimeout = config.connectTimeoutMs.toInt()
                connection.readTimeout = config.readTimeoutMs.toInt()
                connection.doOutput = true

                val requestBody = JSONObject().apply {
                    put("content_type", contentType)
                    put("session_id", metadata.sessionId)
                    put("trace_id", metadata.traceId)
                    put("frame_index", metadata.frameIndex)
                }.toString()

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody)
                    writer.flush()
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "Signed URL request failed: ${connection.responseCode}")
                    return@withContext null
                }

                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseBody)

                SignedUrlResponse(
                    signedUrl = json.getString("signed_url"),
                    gcsUri = json.getString("gcs_uri"),
                    expiresAt = json.optLong("expires_at", 0L),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request signed URL", e)
                null
            } finally {
                connection?.disconnect()
            }
        }
    }

    private suspend fun uploadToGcs(
        signedUrl: String,
        imageBytes: ByteArray,
        contentType: String,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(signedUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"
                connection.setRequestProperty("Content-Type", contentType)
                connection.connectTimeout = config.connectTimeoutMs.toInt()
                connection.readTimeout = config.writeTimeoutMs.toInt()
                connection.doOutput = true

                connection.outputStream.use { outputStream ->
                    outputStream.write(imageBytes)
                    outputStream.flush()
                }

                connection.responseCode == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                Log.e(TAG, "GCS upload failed", e)
                false
            } finally {
                connection?.disconnect()
            }
        }
    }
}

// Data types.

data class GcsUploaderConfig(
    val signedUrlEndpoint: String = com.immersive.ui.BuildConfig.MOBILE_AGENT_BASE_URL + "/api/gcs/signed-url",
    val maxConcurrentUploads: Int = 3,
    val maxRetries: Int = 3,
    val retryBaseDelayMs: Long = 500L,
    val connectTimeoutMs: Long = 10_000L,
    val writeTimeoutMs: Long = 30_000L,
    val readTimeoutMs: Long = 10_000L,
)

enum class UploaderState {
    IDLE,
    UPLOADING,
}

data class UploadMetadata(
    val sessionId: String = "",
    val traceId: String = "",
    val frameIndex: Int = 0,
    val customTags: Map<String, String> = emptyMap(),
)

data class UploadHandle(
    val uploadId: String,
)

data class UploadResult(
    val uploadId: String,
    val success: Boolean,
    val gcsUri: String?,
    val metadata: UploadMetadata,
    val errorMessage: String? = null,
)

data class SignedUrlResponse(
    val signedUrl: String,
    val gcsUri: String,
    val expiresAt: Long,
)
