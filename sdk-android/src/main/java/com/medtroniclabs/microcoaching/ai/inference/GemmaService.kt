package com.medtroniclabs.microcoaching.ai.inference

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LLM inference engine using MediaPipe GenAI for Gemma 3 1B INT4 models.
 *
 * Supports `.task` model files — the SDK's only on-device inference engine.
 *
 * Device requirements:
 *   - Minimum: 3 GB RAM, API 24, arm64-v8a
 *   - Model: ~600 MB for Gemma 3 1B INT4 (.task variant)
 *   - Cold start: ~4 seconds
 *
 * Thread safety: A [Mutex] ensures only one inference runs at a time.
 * Concurrent requests will receive [LLMError.InferenceBusy].
 */
class GemmaService(private val context: Context) : LLMService {

    private val _isModelLoaded = MutableStateFlow(false)
    override val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private var llmInference: LlmInference? = null
    private var loadedConfig: LLMConfiguration? = null
    private val inferenceMutex = Mutex()

    override suspend fun loadModel(configuration: LLMConfiguration) {
        withContext(Dispatchers.IO) {
            val modelFile = File(configuration.modelPath)
            if (!modelFile.exists()) {
                throw LLMError.ModelNotFound(configuration.modelPath)
            }

            runCatching {
                val backend = when (configuration.preferredBackend) {
                    InferenceBackend.GPU -> LlmInference.Backend.GPU
                    InferenceBackend.CPU -> LlmInference.Backend.CPU
                }

                // maxTopK must be >= the topK used per session; set generously.
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(configuration.modelPath)
                    .setMaxTokens(configuration.maxTokens)
                    .setMaxTopK(configuration.topK.coerceAtLeast(40))
                    .setPreferredBackend(backend)
                    .build()

                // Release any previously loaded model first
                llmInference?.close()
                llmInference = LlmInference.createFromOptions(context, options)
                loadedConfig = configuration
                _isModelLoaded.value = true
                Log.i(TAG, "Gemma model loaded: ${modelFile.name}")
            }.onFailure { cause ->
                _isModelLoaded.value = false
                throw LLMError.ModelLoadFailed(cause.message ?: "unknown error", cause)
            }
        }
    }

    override suspend fun generateResponse(prompt: String): Result<String> {
        val inference = llmInference ?: return Result.failure(LLMError.ModelNotLoaded)
        if (!inferenceMutex.tryLock()) return Result.failure(LLMError.InferenceBusy)
        return try {
            runCatching {
                withContext(Dispatchers.Default) {
                    createSession(inference).use { session ->
                        session.addQueryChunk(prompt)
                        session.generateResponse()
                            .substringBefore("<end_of_turn>")
                            .trim()
                    }
                }
            }.mapFailure { cause ->
                LLMError.InferenceFailed(cause.message ?: "generation failed", cause)
            }
        } finally {
            inferenceMutex.unlock()
        }
    }

    override fun generateResponseStream(prompt: String): Flow<String> = callbackFlow {
        val inference = llmInference
            ?: run { close(LLMError.ModelNotLoaded); return@callbackFlow }

        // Atomically acquire — if busy, reject immediately
        if (!inferenceMutex.tryLock()) {
            close(LLMError.InferenceBusy)
            return@callbackFlow
        }

        // Guards: lock and session are each released exactly once.
        val lockReleased = AtomicBoolean(false)
        val sessionClosed = AtomicBoolean(false)
        fun releaseLock() {
            if (lockReleased.compareAndSet(false, true)) inferenceMutex.unlock()
        }

        val session = runCatching { createSession(inference).also { it.addQueryChunk(prompt) } }
            .getOrElse { cause ->
                releaseLock()
                close(LLMError.InferenceFailed(cause.message ?: "session creation failed", cause))
                return@callbackFlow
            }

        // Accumulate tokens to detect <end_of_turn> which Gemma inserts after its response.
        // Without this, the model runs to maxTokens and can enter a repetition loop.
        var streamStopped = false
        val accum = StringBuilder()

        session.generateResponseAsync(object : ProgressListener<String> {
            override fun run(partialResult: String?, done: Boolean) {
                if (streamStopped) return
                if (partialResult != null) {
                    accum.append(partialResult)
                    if (accum.contains("<end_of_turn>")) {
                        streamStopped = true
                        runCatching { session.cancelGenerateResponseAsync() }
                        releaseLock()
                        channel.close()
                        return
                    }
                    trySend(partialResult)
                }
                if (done) {
                    releaseLock()
                    channel.close()
                }
            }
        })

        awaitClose {
            // The collector can cancel mid-generation (downstream takeWhile cap,
            // user closing the sheet). Stop the native generation loop BEFORE
            // closing the session — closing while a generateResponseAsync is
            // still producing is the one ordering MediaPipe doesn't guarantee.
            if (sessionClosed.compareAndSet(false, true)) {
                if (!streamStopped) runCatching { session.cancelGenerateResponseAsync() }
                runCatching { session.close() }
            }
            releaseLock()
        }
    }

    override fun unloadModel() {
        llmInference?.close()
        llmInference = null
        loadedConfig = null
        _isModelLoaded.value = false
        Log.i(TAG, "Gemma model unloaded")
    }

    private fun createSession(inference: LlmInference): LlmInferenceSession {
        val config = loadedConfig
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(config?.topK ?: 40)
            .setTemperature(config?.temperature ?: 0.3f)
            .build()
        return LlmInferenceSession.createFromOptions(inference, sessionOptions)
    }

    companion object {
        private const val TAG = "GemmaService"
        const val MODEL_EXTENSION = ".task"
    }
}

// Extension to map Throwable inside Result
private fun <T> Result<T>.mapFailure(transform: (Throwable) -> Throwable): Result<T> =
    fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(transform(it)) })
