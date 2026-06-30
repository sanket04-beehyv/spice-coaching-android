package com.medtroniclabs.microcoaching.ai.model

/**
 * Model download source. [ModelManager] tries each provider in
 * [com.medtroniclabs.microcoaching.MicroCoachingConfig.modelProviders] order,
 * falling back to the next on failure.
 *
 * Configure via the SDK Builder:
 * ```kotlin
 * MicroCoachingSDK.Builder(this)
 *     .modelProviders(listOf(ModelProvider.HuggingFace, ModelProvider.Backend))
 *     .huggingFaceToken(BuildConfig.HF_TOKEN)
 *     .build()
 * ```
 */
sealed class ModelProvider {

    /**
     * Downloads from the MicroCoaching FastAPI backend.
     * Endpoint: `{backendUrl}/api/v1/models/gemma/download`
     * Auth: SPICE JWT via `Authorization: Bearer {authToken}`
     *
     * Requires [com.medtroniclabs.microcoaching.MicroCoachingConfig.backendUrl].
     */
    object Backend : ModelProvider()

    /**
     * Downloads from HuggingFace Hub.
     * Default model: Gemma3-1B-IT INT4 in `.task` format (~560 MB).
     *
     * **The default `Gemma3-1B-IT` repo is gated** — a HuggingFace token must be
     * supplied via [com.medtroniclabs.microcoaching.MicroCoachingConfig.huggingFaceToken]
     * for the download to succeed. The SDK does not bake in a default token, so
     * the published .aar requires every adopter to provide their own. The worker
     * only attaches the `Authorization` header when a token is supplied — point
     * at a non-gated model (e.g. an internal mirror or one of HuggingFace's
     * public Gemma variants) to download anonymously.
     *
     * Override the URL via [com.medtroniclabs.microcoaching.MicroCoachingConfig.huggingFaceModelUrl]
     * if you want to target a different model file or revision.
     */
    object HuggingFace : ModelProvider()

    /**
     * Downloads from Kaggle Datasets.
     *
     * **Not yet implemented.** Included as a placeholder — the SDK logs a warning and skips
     * to the next provider. Kaggle API integration will be added in a future release.
     */
    object Kaggle : ModelProvider()

    /** Serialise to a WorkManager input data string. */
    internal fun toKey(): String = when (this) {
        Backend -> KEY_BACKEND
        HuggingFace -> KEY_HUGGING_FACE
        Kaggle -> KEY_KAGGLE
    }

    companion object {
        /**
         * MediaPipe `.task` download URL — Gemma3-1B-IT INT4 (~560 MB).
         * Broad device compatibility (any arm64 API 24+) via MediaPipe.
         */
        const val HF_TASK_MODEL_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"

        /** Default MediaPipe download URL. */
        const val DEFAULT_HF_MODEL_URL = HF_TASK_MODEL_URL

        /** Filename for the `.task` (MediaPipe) model variant. */
        const val HF_TASK_MODEL_FILENAME = "gemma3-1b-it-int4.task"

        /** Filename for the default model on device. */
        const val DEFAULT_HF_MODEL_FILENAME = HF_TASK_MODEL_FILENAME


        /**
         * Empty by design. The SDK does not bake in a HuggingFace token — host apps
         * that need one supply it explicitly via
         * [com.medtroniclabs.microcoaching.MicroCoachingConfig.huggingFaceToken].
         * Kept as a constant so existing call sites resolve to the empty default
         * without a `null` check.
         */
        const val DEFAULT_HF_TOKEN: String = ""

        /** Default provider order — used when the host app does not configure [com.medtroniclabs.microcoaching.MicroCoachingConfig.modelProviders]. */
        val DEFAULT_ORDER: List<ModelProvider> = listOf(Backend, HuggingFace, Kaggle)

        // WorkManager serialisation keys (internal)
        internal const val KEY_BACKEND = "backend"
        internal const val KEY_HUGGING_FACE = "hugging_face"
        internal const val KEY_KAGGLE = "kaggle"

        /** Deserialise from a WorkManager input data string. */
        internal fun fromKey(key: String): ModelProvider? = when (key) {
            KEY_BACKEND -> Backend
            KEY_HUGGING_FACE -> HuggingFace
            KEY_KAGGLE -> Kaggle
            else -> null
        }
    }
}
