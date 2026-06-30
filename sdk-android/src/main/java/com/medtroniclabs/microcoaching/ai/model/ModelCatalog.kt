package com.medtroniclabs.microcoaching.ai.model

/**
 * On-device engine a [ModelVariant] runs on.
 *
 * Only [MEDIAPIPE] is runnable today — it's the single inference engine bundled
 * in the SDK ( `libllm_inference_engine_jni.so`, see `docs/apk-size-analysis.md`).
 * [LITERT_LM] variants are *listed* so the catalog can describe them, but they
 * can't load until the LiteRT-LM runtime is re-added (see
 * `docs/small-llm-allowlist-plan.md`). [LLAMA_CPP] is not planned.
 */
enum class ModelRuntime { MEDIAPIPE, LITERT_LM, LLAMA_CPP }

/**
 * One downloadable on-device model. The catalog is the **single source of truth**
 * for a model's URL, on-disk filename, expected size, runtime, and RAM class —
 * replacing the scattered `ModelProvider.HF_TASK_MODEL_URL` / `*_FILENAME`
 * constants and the global 750 MB size floor that were hard-wired to the 1B.
 *
 * @property id              Stable key used by [MicroCoachingConfig.selectedModelId].
 * @property fileName        On-disk name — drives file resolution (so multiple
 *                           variants can coexist for A/B testing without the old
 *                           "first `.task` on disk" ambiguity).
 * @property sizeInBytes     Approximate download size. Drives the per-variant
 *                           "is this download complete?" floor. **Verify against
 *                           the HF repo before trusting** — see the allowlist plan.
 * @property minDeviceMemoryGb RAM class for this variant. Stored now but NOT yet
 *                           enforced below the global 3 GB gate (the existing
 *                           [com.medtroniclabs.microcoaching.domain.system.DeviceCapability]
 *                           cut-off still applies). Lowering the gate is future work.
 * @property maxTokens/temperature/topK  Optional per-model sampling overrides;
 *                           when null the [MicroCoachingConfig] defaults apply.
 */
data class ModelVariant(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeInBytes: Long,
    val runtime: ModelRuntime,
    val minDeviceMemoryGb: Int,
    val requiresAccessToken: Boolean,
    val params: String,
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val topK: Int? = null,
)

/**
 * Compile-time allowlist of on-device models. Pure constants — adds ~0 to the APK
 * (models still download at runtime; nothing is bundled).
 *
 * Pick the active model with `MicroCoachingSDK.Builder.selectedModel(id)`; the SDK
 * threads the resolved [ModelVariant] through download ([ModelDownloadWorker]),
 * file resolution ([ModelManager.findLocalModel] /
 * [com.medtroniclabs.microcoaching.ai.inference.InferenceRouter]), and load.
 */
object ModelCatalog {

    /**
     * Default model when the host doesn't call `selectedModel(...)`.
     *
     * Set to the **Gemma 3 270M (q8)** `.task` — smaller, conversational, runs on
     * the MediaPipe engine already shipped. The global ≥ 3 GB RAM gate still
     * applies, so this is what loads on capable devices; reaching ~2 GB devices
     * is future work.
     */
    const val DEFAULT_ID = "gemma3-270m-it-q8-task"
    // const val DEFAULT_ID = "gemma3-1b-it-int4-task"

    val ALLOWLIST: List<ModelVariant> = listOf(
        ModelVariant(
            id = "gemma3-270m-it-q8-task",
            displayName = "Gemma 3 270M-IT (q8, MediaPipe)",
            fileName = "gemma3-270m-it-q8.task",
            downloadUrl = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.task",
            sizeInBytes = 319_000_000L,           // ~304 MiB — verify against repo
            runtime = ModelRuntime.MEDIAPIPE,
            minDeviceMemoryGb = 2,
            requiresAccessToken = false,          // non-gated repo — verify
            params = "270M",
        ),
        ModelVariant(
            id = "gemma3-270m-it-q4-task",
            displayName = "Gemma 3 270M-IT (q4, MediaPipe)",
            fileName = "gemma3-270m-it-q4_0-web.task",
            downloadUrl = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q4_0-web.task",
            sizeInBytes = 261_000_000L,           // ~249 MiB — verify against repo
            runtime = ModelRuntime.MEDIAPIPE,
            minDeviceMemoryGb = 2,
            requiresAccessToken = false,
            params = "270M",
        ),
        ModelVariant(
            // Listed for completeness; NOT runnable until the LiteRT-LM runtime
            // is re-added. Selecting it today fails loud at load.
            id = "gemma3-270m-it-q8-litertlm",
            displayName = "Gemma 3 270M-IT (q8, LiteRT-LM — not yet runnable)",
            fileName = "gemma3-270m-it-q8.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.litertlm",
            sizeInBytes = 319_000_000L,
            runtime = ModelRuntime.LITERT_LM,
            minDeviceMemoryGb = 2,
            requiresAccessToken = false,
            params = "270M",
        ),
        ModelVariant(
            id = "gemma3-1b-it-int4-task",
            displayName = "Gemma 3 1B-IT (INT4, MediaPipe)",
            fileName = "gemma3-1b-it-int4.task",
            downloadUrl = ModelProvider.HF_TASK_MODEL_URL,
            sizeInBytes = 555_000_000L,           // ~the previous default model
            runtime = ModelRuntime.MEDIAPIPE,
            minDeviceMemoryGb = 3,
            requiresAccessToken = true,           // litert-community/Gemma3-1B-IT is gated
            params = "1B",
        ),
    )

    /** Fraction of [ModelVariant.sizeInBytes] below which a download is treated as
     *  truncated/incomplete. Generous so an approximate `sizeInBytes` never causes a
     *  *complete* file to be wrongly deleted; the server `Content-Length` check in
     *  [ModelDownloadWorker] is the precise guard when the server provides it. */
    const val SIZE_FLOOR_FRACTION = 0.85

    fun byId(id: String): ModelVariant? = ALLOWLIST.firstOrNull { it.id == id }

    fun byFileName(name: String): ModelVariant? = ALLOWLIST.firstOrNull { it.fileName == name }

    /** The default variant. Non-null — [DEFAULT_ID] is always present in [ALLOWLIST]. */
    fun default(): ModelVariant = byId(DEFAULT_ID)
        ?: error("DEFAULT_ID '$DEFAULT_ID' missing from ModelCatalog.ALLOWLIST")

    /** Resolve [id] to a variant, falling back to [default] for an unknown id. */
    fun resolve(id: String): ModelVariant = byId(id) ?: default()

    /** True when the variant's runtime is bundled and can actually load today. */
    fun isRunnable(variant: ModelVariant): Boolean = variant.runtime == ModelRuntime.MEDIAPIPE

    /** Per-variant minimum-valid-size floor in bytes. */
    fun minValidSizeBytes(variant: ModelVariant): Long =
        (variant.sizeInBytes * SIZE_FLOOR_FRACTION).toLong()
}
