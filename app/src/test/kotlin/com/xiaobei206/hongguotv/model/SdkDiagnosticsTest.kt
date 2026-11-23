package com.xiaobei206.hongguotv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkDiagnosticsTest {
    @Test
    fun retainsDeviceHeartbeatAndActivationEvents() {
        val heartbeat = "playback device heartbeat accepted: egdi_present=true"
        val activation = "playback device activation response: status=200 response_bytes=32 duration_ms=12"

        SdkDiagnostics.record(heartbeat)
        SdkDiagnostics.record(activation)

        val recent = SdkDiagnostics.snapshot().takeLast(2).map { it.message }
        assertEquals(listOf(heartbeat, activation), recent)
    }

    @Test
    fun emptyPlaybackResponseDoesNotSuggestReplacingDevice() {
        val emptyResponse = PlaybackFailure(
            occurredAt = "2026-11-23T12:00:00Z",
            episodeId = "episode",
            sdkCode = "ANONYMOUS_LIMITED",
            sdkMessage = "HTTP 200 empty response",
            serverBusinessCode = null,
            serverErrorName = null,
            serverErrorMessage = null,
            httpStatus = 200,
            requestMethod = "POST",
            endpoint = "api.example/novel/player/multi_video_model/v1/",
            responseBytes = 0,
            decodedResponseBytes = 0,
            responseContentType = "application/json",
            responseContentEncoding = null,
            responseBodyNote = "empty response",
            retryable = false,
            retryAfterMs = 0,
            events = emptyList(),
        )
        assertEquals(false, emptyResponse.canRefreshDevice)

        val rejectedDevice = emptyResponse.copy(sdkCode = "DEVICE_REJECTED")
        assertTrue(rejectedDevice.canRefreshDevice)
    }
}
