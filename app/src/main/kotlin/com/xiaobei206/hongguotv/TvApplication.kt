package com.xiaobei206.hongguotv

import android.app.Application
import android.util.Log
import androidx.media3.datasource.DefaultHttpDataSource
import com.xiaobei206.hongguotv.model.AppModel
import com.xiaobei206.hongguotv.model.SdkCatalog
import com.xiaobei206.hongguotv.model.SdkDiagnostics
import com.xiaobei206.hongguotv.playback.ExoVideo
import com.xiaobei206.hongguotv.playback.StreamRegistry
import dev.openhongguo.ClientOptions
import dev.openhongguo.OpenhongguoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

@androidx.media3.common.util.UnstableApi
class TvApplication : Application() {
    var model: AppModel? = null
        private set
    lateinit var video: ExoVideo
        private set
    lateinit var artwork: TvArtworkLoader
        private set
    private lateinit var appScope: CoroutineScope
    var startupError: String = ""
        private set
    var handoffServer: TvHandoffServer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        SdkDiagnostics.initialize(this)
        artwork = TvArtworkLoader(this)
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("hongguoTV/${BuildConfig.VERSION_NAME} AndroidTV")
        val streams = StreamRegistry(http)
        video = ExoVideo(this, streams, http)
        appScope = MainScope()
        try {
            val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            val client = OpenhongguoClient.create(this, ClientOptions(
                retryAttempts = 3,
                deviceResets = 3,
                logger = { line ->
                    SdkDiagnostics.record(line)
                    if (debug) Log.d("hongguoTV-sdk", line)
                },
            ))
            model = AppModel(SdkCatalog(client), filesDir, cacheDir, appScope, video, streams).also {
                it.artwork = artwork::cachedJpeg
            }
            handoffServer = TvHandoffServer(this).also { it.start() }
        } catch (e: Exception) {
            startupError = e.message ?: e.javaClass.simpleName
            Log.e("hongguoTV", "SDK 初始化失败", e)
        }
    }

    override fun onTerminate() {
        handoffServer?.stop()
        model?.flush()
        if (::video.isInitialized) video.exo.release()
        super.onTerminate()
    }
}
