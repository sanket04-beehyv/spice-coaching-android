package com.medtroniclabs.microcoaching.domain.telemetry

import android.util.Log
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.util.concurrent.TimeUnit

/**
 * Manages OpenTelemetry initialization and provides span/metric helpers for the SDK.
 *
 * All configurable via [MicroCoachingConfig]:
 *   - [MicroCoachingConfig.otelEndpoint] — OTLP/HTTP receiver URL
 *   - [MicroCoachingConfig.otelHeaders] — auth headers (SigNoz, Grafana, etc.)
 *   - [MicroCoachingConfig.otelServiceName] — service.name on all spans
 *   - [MicroCoachingConfig.otelSamplingRate] — 0.0 to 1.0
 *   - [MicroCoachingConfig.otelBatchExportIntervalMs] — flush cadence
 *   - [MicroCoachingConfig.enableOtelDebugLogging] — Logcat echo
 *
 * Privacy rules (enforced here):
 *   - No prompt or response text in span attributes
 *   - No patient-identifiable data in span attributes
 *   - User IDs should be SHA-256 hashed before passing to span attributes
 *
 * Span naming follows OpenTelemetry GenAI semantic conventions where applicable.
 */
class TelemetryManager(private val config: MicroCoachingConfig) {

    private val openTelemetry: OpenTelemetry
    val tracer: Tracer
    private val meter: Meter

    // Counters (initialized after meter is ready)
    lateinit var chatMessageCounter: LongCounter
    lateinit var inferenceCounter: LongCounter
    lateinit var inferenceErrorCounter: LongCounter

    init {
        openTelemetry = if (config.enableTelemetry) {
            buildOpenTelemetry()
        } else {
            OpenTelemetry.noop()
        }

        tracer = openTelemetry.getTracer(
            "com.medtroniclabs.microcoaching",
            BuildConfig.SDK_VERSION
        )
        meter = openTelemetry.getMeter("com.medtroniclabs.microcoaching")

        initMetrics()
    }

    private fun buildOpenTelemetry(): OpenTelemetry {
        val resource = Resource.getDefault().merge(
            Resource.create(
                Attributes.of(
                    AttributeKey.stringKey("service.name"), config.otelServiceName,
                    AttributeKey.stringKey("service.version"), BuildConfig.SDK_VERSION,
                    AttributeKey.stringKey("deployment.environment"), "android",
                )
            )
        )

        val sampler = if (config.otelSamplingRate >= 1.0) {
            Sampler.alwaysOn()
        } else {
            Sampler.traceIdRatioBased(config.otelSamplingRate)
        }

        val tracerProviderBuilder = SdkTracerProvider.builder()
            .setResource(resource)
            .setSampler(sampler)

        // OTLP/HTTP exporter — only if endpoint is configured
        if (config.otelEndpoint.isNotBlank()) {
            val exporterBuilder = OtlpHttpSpanExporter.builder()
                .setEndpoint("${config.otelEndpoint.trimEnd('/')}/v1/traces")

            config.otelHeaders.forEach { (key, value) ->
                exporterBuilder.addHeader(key, value)
            }

            val batchProcessor = BatchSpanProcessor.builder(exporterBuilder.build())
                .setScheduleDelay(config.otelBatchExportIntervalMs, TimeUnit.MILLISECONDS)
                .setMaxExportBatchSize(config.otelMaxBatchSize)
                .build()

            tracerProviderBuilder.addSpanProcessor(batchProcessor)
        }

        // Debug logging — Logcat echo (only when explicitly enabled)
        if (config.enableOtelDebugLogging) {
            tracerProviderBuilder.addSpanProcessor(
                SimpleSpanProcessor.create(LoggingSpanExporter.create())
            )
        }

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProviderBuilder.build())
            .build()
    }

    private fun initMetrics() {
        chatMessageCounter = meter
            .counterBuilder("chat.messages.total")
            .setDescription("Total chat messages sent by the CHW")
            .setUnit("{message}")
            .build()

        inferenceCounter = meter
            .counterBuilder("llm.inference.total")
            .setDescription("Total LLM inference requests")
            .setUnit("{request}")
            .build()

        inferenceErrorCounter = meter
            .counterBuilder("llm.inference.errors")
            .setDescription("Total LLM inference failures")
            .setUnit("{error}")
            .build()
    }

    // ── Chat spans ─────────────────────────────────────────────────────────────

    /** Start a chat.session span. Call [endChatSession] when the CHW closes the chat. */
    fun startChatSession(sessionId: String): Span {
        return tracer.spanBuilder("chat.session")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("session.id", sessionId)
            .startSpan()
    }

    fun endChatSession(span: Span) {
        span.setStatus(StatusCode.OK)
        span.end()
    }

    // ── LLM inference spans ────────────────────────────────────────────────────

    /**
     * Start an LLM inference stream span.
     * Parent should be the active chat.session span.
     *
     * @param modelName File name of the model (e.g. "gemma3-1B-it-int4.task")
     * @param engineName Inference engine identifier (e.g. "mediapipe")
     * @param sessionId Active session identifier
     */
    fun startInferenceStream(modelName: String, engineName: String, sessionId: String): Span {
        return tracer.spanBuilder("llm.inference.stream")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("gen_ai.system", engineName)
            .setAttribute("gen_ai.model.name", modelName)
            .setAttribute("gen_ai.operation.name", "text_completion")
            .setAttribute("session.id", sessionId)
            .startSpan()
    }

    /**
     * Record inference result attributes on the span and end it.
     * Token counts are estimated (chars ÷ 4) — no prompt/response text is recorded.
     */
    fun endInferenceStream(
        span: Span,
        estimatedInputTokens: Long,
        estimatedOutputTokens: Long,
        latencyMs: Long,
        success: Boolean,
        errorMessage: String? = null,
    ) {
        span.setAttribute("gen_ai.usage.input_tokens", estimatedInputTokens)
        span.setAttribute("gen_ai.usage.output_tokens", estimatedOutputTokens)
        span.setAttribute("inference.latency_ms", latencyMs)
        if (success) {
            span.setStatus(StatusCode.OK)
        } else {
            span.setStatus(StatusCode.ERROR, errorMessage ?: "inference failed")
            inferenceErrorCounter.add(1)
        }
        inferenceCounter.add(1)
        span.end()
    }

    /** Record model cold-start load time. */
    fun recordModelLoad(modelName: String, engineName: String, latencyMs: Long) {
        val span = tracer.spanBuilder("llm.model.load")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("gen_ai.model.name", modelName)
            .setAttribute("gen_ai.system", engineName)
            .setAttribute("model.load_time_ms", latencyMs)
            .startSpan()
        span.setStatus(StatusCode.OK)
        span.end()
    }

    // ── Flush ─────────────────────────────────────────────────────────────────

    /**
     * Force-flush all pending spans to the OTLP endpoint.
     * Call this when connectivity is restored after an offline period.
     */
    fun flushPendingSpans() {
        if (!config.enableTelemetry) return
        val sdk = openTelemetry
        if (sdk is OpenTelemetrySdk) {
            sdk.sdkTracerProvider.forceFlush()
        }
    }

    /** Shut down the OTel SDK. Call in Application.onTerminate() if needed. */
    fun shutdown() {
        if (openTelemetry is OpenTelemetrySdk) {
            openTelemetry.sdkTracerProvider.shutdown()
        }
    }

    companion object {
        private const val TAG = "MicroCoachingTelemetry"
    }
}
