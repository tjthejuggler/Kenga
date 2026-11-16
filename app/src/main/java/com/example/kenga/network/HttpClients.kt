package com.example.kenga.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Shared OkHttp client (Octo `network/HttpClients.kt` pattern).
 *
 * Used by: YouTube oEmbed metadata fetcher, thumbnails (Coil), and — in later
 * phases — the NewPipeExtractor bridge, video downloads and the LLM client.
 * Long reads are expected (video downloads) — no overall call timeout.
 */
fun kengaOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS) // no overall cap: downloads can run for minutes
    .retryOnConnectionFailure(true)
    .build()
