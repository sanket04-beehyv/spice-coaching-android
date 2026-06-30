package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for creating the SDK's OkHttpClient and Retrofit instance.
 *
 * The SPICE JWT ([MicroCoachingConfig.authToken]) is automatically included
 * as `Authorization: Bearer {token}` on every request.
 *
 * Note: This is separate from SPICE's own OkHttpClient. The SDK manages its
 * own HTTP client to avoid coupling to SPICE's AppInterceptor configuration.
 */
object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun createOkHttpClient(config: MicroCoachingConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectionTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder().apply {
                    if (config.authToken.isNotBlank()) {
                        header("Authorization", "Bearer ${config.authToken}")
                    }
                    if (config.tenantId.isNotBlank()) {
                        header("X-Tenant-Id", config.tenantId)
                    }
                    header("X-SDK-Version", BuildConfig.SDK_VERSION)
                    header("X-Client", "micro-coaching-android")
                }.build()
                chain.proceed(request)
            }

        if (config.enableOtelDebugLogging) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    redactHeader("Authorization")
                    redactHeader("X-Tenant-Id")
                }
            )
        }

        return builder.build()
    }

    fun createApiService(config: MicroCoachingConfig): CoachingApiService {
        val client = createOkHttpClient(config)
        val baseUrl = config.backendUrl.trimEnd('/') + "/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CoachingApiService::class.java)
    }
}
