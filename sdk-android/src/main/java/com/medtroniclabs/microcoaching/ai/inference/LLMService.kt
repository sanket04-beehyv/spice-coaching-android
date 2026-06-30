package com.medtroniclabs.microcoaching.ai.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Common interface for all on-device LLM inference engines.
 *
 * Implementations:
 *   - [GemmaService] — MediaPipe Gemma 3 1B INT4 (`.task` model file)
 *
 * The implementation is selected at runtime by [InferenceRouter]
 * based on the model file extension.
 */
interface LLMService {

    /** True when a model is loaded and ready for inference. */
    val isModelLoaded: StateFlow<Boolean>

    /**
     * Load the model from [configuration]. This is a blocking operation (~4–10 seconds).
     * Should be called on a background dispatcher.
     *
     * @throws LLMError.ModelLoadFailed if the model file is missing or incompatible.
     */
    suspend fun loadModel(configuration: LLMConfiguration)

    /**
     * Generate a single complete response for [prompt].
     * Waits for the entire response before returning.
     *
     * For streaming output use [generateResponseStream].
     *
     * @return [Result.success] with the response text, or [Result.failure] with [LLMError].
     */
    suspend fun generateResponse(prompt: String): Result<String>

    /**
     * Generate a streaming response for [prompt].
     * Emits partial text tokens as they are produced by the model.
     *
     * Example:
     * ```kotlin
     * llmService.generateResponseStream(prompt).collect { token ->
     *     appendToUI(token)
     * }
     * ```
     */
    fun generateResponseStream(prompt: String): Flow<String>

    /**
     * Release model resources. Call when the user navigates away from the chat.
     * The model can be reloaded by calling [loadModel] again.
     */
    fun unloadModel()
}

/** Configuration for loading and running an LLM. */
data class LLMConfiguration(
    /** Absolute path to the model file (.task). */
    val modelPath: String,
    /** Maximum number of tokens to generate per response. */
    val maxTokens: Int = 1024,
    /** Top-K sampling parameter. */
    val topK: Int = 40,
    /** Sampling temperature. */
    val temperature: Float = 0.8f,
    /** Preferred inference backend. CPU works on all devices; GPU is faster on high-end. */
    val preferredBackend: InferenceBackend = InferenceBackend.CPU,
)

/** Hardware backend for model inference. */
enum class InferenceBackend {
    /** CPU inference — works on all API 24+ devices. Slower but universal. */
    CPU,
    /** GPU inference — faster on Adreno 530+ / Mali G76+. Falls back to CPU if unavailable. */
    GPU,
}

/** Sealed class representing all LLM-related errors. */
sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Model file not found at the specified path. */
    class ModelNotFound(path: String) :
        LLMError("Model file not found at: $path")

    /** Model file exists but failed to load (wrong format, OOM, etc.). */
    class ModelLoadFailed(reason: String, cause: Throwable? = null) :
        LLMError("Model load failed: $reason", cause)

    /** Inference was attempted before calling [LLMService.loadModel]. */
    object ModelNotLoaded :
        LLMError("Model is not loaded. Call loadModel() first.")

    /** The model is already running an inference; concurrent requests are not supported. */
    object InferenceBusy :
        LLMError("Inference is already in progress. Only one request at a time is supported.")

    /** The inference engine returned an error during generation. */
    class InferenceFailed(reason: String, cause: Throwable? = null) :
        LLMError("Inference failed: $reason", cause)
}
