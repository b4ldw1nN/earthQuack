package com.example.earthquack.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.earthquack.R
import com.example.earthquack.databinding.ActivityVideoPlayerBinding
import com.example.earthquack.storage.RcloneEngine
import com.example.earthquack.storage.RcloneRangeSource
import com.example.earthquack.storage.RcloneConfigManager
import com.example.earthquack.stream.RangeHttpServer
import com.example.earthquack.stream.RangeReadException
import com.example.earthquack.stream.RangeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays one remote video without downloading it first.
 *
 * ```text
 * this screen → RangeHttpServer (127.0.0.1, token) → RcloneRangeSource
 *             → rclone earthquack/read → the remote
 * ```
 *
 * ## Why ExoPlayer and not the VideoView the preview screen uses
 *
 * `VideoView` takes a URI it can open through the platform media stack, which
 * means the file has to already exist locally: the platform has no way to ask
 * a server for the bytes at offset 90% of a movie. ExoPlayer asks for exactly
 * that, with an HTTP `Range` request per seek, which is what the loopback range
 * server here answers. It also brings the transport controls, the seek bar and
 * the buffering indicator with it.
 *
 * ## Lifecycle
 *
 * The player and the local server are released in `onStop`, and the playback
 * position is kept in `onSaveInstanceState`. A screen left in the background
 * therefore holds no socket, no player and no rclone read; coming back
 * restarts the stream from where it was rather than from zero. That is the
 * behaviour that keeps "no server running indefinitely" true in practice,
 * without a service or a thread that outlives the UI.
 *
 * ## Failure handling
 *
 * Three failure shapes, all visible, all with a way out:
 *
 *  - the remote reports no size, or it rejects the range outright → the file
 *    cannot be streamed and the screen says so, offering a download;
 *  - the remote read fails mid-playback → a player error, with a retry and the
 *    same download route;
 *  - the player itself cannot decode the container → same error state.
 *
 * In every case the download button goes through the *caller's* normal
 * download path, so nothing about the existing cache, `.part` staging or
 * size verification changes.
 */
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding

    /** The rclone filesystem root the entry was listed from. */
    private lateinit var fs: String

    /** Path relative to [fs], exactly as `operations/list` returned it. */
    private lateinit var remote: String

    private var name: String = ""
    private var mime: String = ""

    private var engine: RcloneEngine? = null
    private var source: RangeSource? = null
    private var server: RangeHttpServer? = null
    private var player: ExoPlayer? = null

    private var starting = false

    /** Restored across rotation by [onSaveInstanceState]. */
    private var resumePosition = 0L
    private var resumePlayWhenReady = true

    /** Keeps the same file from being streamed twice in two activities. */
    private val streamKey: String get() = "$fs\u0000$remote"

    private val prepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fs = intent.getStringExtra(EXTRA_FS).orEmpty().ifBlank { finish(); return }
        remote = intent.getStringExtra(EXTRA_REMOTE).orEmpty().ifBlank { finish(); return }
        name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { remote.substringAfterLast('/') }
        mime = intent.getStringExtra(EXTRA_MIME).orEmpty().ifBlank {
            FileOpener(applicationContext).mimeType(name, null)
        }

        if (savedInstanceState != null) {
            resumePosition = savedInstanceState.getLong(KEY_POSITION, 0L)
            resumePlayWhenReady = savedInstanceState.getBoolean(KEY_PLAY_WHEN_READY, true)
        }

        if (!active.add(streamKey)) {
            // Already streaming this exact file. A second activity would hold a
            // second socket and a second read cursor for no gain.
            toast(getString(R.string.files_stream_no_copy))
            finish()
            return
        }

        binding.appbar.btnBack.isVisible = true
        binding.appbar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.appbar.btnAction.isVisible = false
        binding.appbar.title.text = name
        binding.appbar.subtitle.isVisible = true
        binding.appbar.subtitle.text = subtitle()

        binding.btnDownload.isVisible = true
        binding.btnDownload.setOnClickListener { downloadInstead() }
        binding.btnRetry.setOnClickListener { startStream() }
        binding.player.setShutterBackgroundColor(0)

        startEngine()
    }

    /** True once rclone is running; shows the failure state and returns false. */
    private fun startEngine(): Boolean {
        val started = try {
            RcloneConfigManager(this).ensureReady()
            engine = RcloneEngine().also { it.initialize(RcloneConfigManager(this).configPath) }
            true
        } catch (e: Exception) {
            // UnsatisfiedLinkError is the expected case on a device the AAR
            // does not carry an .so for.
            Log.w(TAG, "rclone could not be initialised: ${e.javaClass.simpleName}")
            false
        }
        return if (started) {
            startStream()
            true
        } else {
            showFailure(getString(R.string.state_unavailable), false)
            false
        }
    }

    /**
     * Builds the range source and the local server, then the player.
     *
     * The size is probed first, on IO, because it is the one thing that decides
     * whether this file can be streamed at all: with no size there is no
     * `Content-Range` to send and no way to answer a seek.
     */
    private fun startStream() {
        if (starting) return
        val rpc = engine ?: return
        starting = true
        showLoading(getString(R.string.files_stream_connecting))

        prepScope.launch {
            val src = RcloneRangeSource(rpc, fs, remote)
            val size = try {
                withContext(Dispatchers.IO) { src.size() }
            } catch (e: RangeReadException) {
                Log.w(TAG, "remote not streamable: ${e.javaClass.simpleName}")
                lifecycleScope.launch {
                    showFailure(e.message ?: getString(R.string.files_stream_failed), true)
                }
                starting = false
                return@launch
            }

            if (size == null) {
                lifecycleScope.launch {
                    // Not an error in the remote: some backends simply do not
                    // report a size, and a range server cannot invent one.
                    showFailure(getString(R.string.files_stream_unknown_size), true)
                }
                starting = false
                return@launch
            }

            val localServer = RangeHttpServer(src, contentType = mime)
            val port = localServer.start()
            source = src
            server = localServer

            lifecycleScope.launch {
                buildPlayer(localServer.url(name))
                starting = false
            }
        }
    }

    private fun buildPlayer(url: String) {
        showLoading(null)
        releasePlayer()

        // A loopback URL over plain HTTP: the local network security config
        // still applies to the socket, so this is allowed because the request
        // never leaves the device. The token is in the path, not in a header.
        @SuppressLint("UnsafeOptInUsageError")
        val dataSource = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(false)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        player = exo

        binding.player.player = exo
        exo.addListener(PlayerListener())
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.playWhenReady = resumePlayWhenReady
        exo.prepare()
        if (resumePosition > 0) exo.seekTo(resumePosition)
    }

    /** Saves the file through the normal download path instead of streaming. */
    private fun downloadInstead() {
        val rpc = engine
        if (rpc == null) {
            showFailure(getString(R.string.state_unavailable), false)
            return
        }
        releasePlayer()
        stopServer()
        setResult(RESULT_DOWNLOAD)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_POSITION, player?.currentPosition ?: resumePosition)
        outState.putBoolean(KEY_PLAY_WHEN_READY, player?.playWhenReady ?: resumePlayWhenReady)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        // Everything the stream holds is released here, not in onDestroy:
        // a backgrounded player must not keep a socket, a decoder or an rclone
        // read alive. The position survives in onSaveInstanceState, so the
        // next onResume restarts where the screen left off.
        resumePosition = player?.currentPosition ?: resumePosition
        resumePlayWhenReady = player?.playWhenReady ?: resumePlayWhenReady
        releasePlayer()
        stopServer()
        super.onStop()
    }

    override fun onDestroy() {
        active.remove(streamKey)
        prepScope.cancel()
        source = null
        releasePlayer()
        stopServer()
        binding.player.player = null
        super.onDestroy()
    }

    private fun releasePlayer() {
        player?.let { exo ->
            binding.player.player = null
            exo.removeListener(PlayerListener())
            exo.release()
        }
        player = null
    }

    /** Idempotent: safe from both the player's error path and `onStop`. */
    private fun stopServer() {
        server?.stop()
        server = null
    }

    // ── states ──────────────────────────────────────────────────────────────

    private fun showLoading(message: String?) {
        binding.player.isVisible = false
        binding.stateGroup.isVisible = false
        binding.loadingGroup.isVisible = true
        if (message != null) binding.loadingText.setText(message)
        binding.progress.isVisible = true
    }

    private fun showPlaying() {
        binding.loadingGroup.isVisible = false
        binding.stateGroup.isVisible = false
        binding.player.isVisible = true
    }

    /**
     * @param offerDownload whether downloading the file instead is offered.
     *   It is not, when the reason it could not play is that rclone could not
     *   start at all: a download would fail the same way.
     */
    private fun showFailure(message: String, offerDownload: Boolean) {
        binding.loadingGroup.isVisible = false
        binding.player.isVisible = false
        binding.stateGroup.isVisible = true
        binding.stateText.text = message
        binding.btnRetry.isVisible = true
        binding.btnDownload.isVisible = offerDownload
    }

    private fun subtitle(): String =
        server?.let { "127.0.0.1:${it.port}" } ?: fs.trimEnd(':')

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Player events, in the order they happen during a normal stream. */
    private inner class PlayerListener : Player.Listener {

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING ->
                    showLoading(getString(R.string.files_stream_buffering))
                Player.STATE_READY -> showPlaying()
                Player.STATE_ENDED -> finish()
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // The cause is logged, never shown: it contains the URL with the
            // session token in it, and that token is the only thing standing
            // between a loopback port and anyone who could reach it.
            Log.w(TAG, "playback failed: ${error.errorCodeName}")
            val message = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
                    getString(R.string.files_stream_failed)
                else ->
                    getString(R.string.files_stream_unsupported)
            }
            showFailure(message, true)
        }
    }

    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val EXTRA_FS = "fs"
        private const val EXTRA_REMOTE = "remote"
        private const val EXTRA_NAME = "display_name"
        private const val EXTRA_MIME = "mime"
        private const val KEY_POSITION = "position"
        private const val KEY_PLAY_WHEN_READY = "play_when_ready"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        /** Result code: the caller should download the file instead. */
        const val RESULT_DOWNLOAD = RESULT_FIRST_USER + 1

        /** Files currently being streamed, so one is not streamed twice. */
        private val active = mutableSetOf<String>()

        /**
         * @param fs the rclone filesystem root the entry was listed from.
         * @param remote the entry's path relative to [fs].
         * @param size the size the listing reported; not needed by the player
         *   itself (the source probes it), and kept for the caller's records.
         * @param name the display name, used for the title and the URL path.
         * @param mime the type reported by the listing, used as the response's
         *   content type. Falls back to the extension when blank.
         */
        fun intent(
            context: Context,
            fs: String,
            remote: String,
            size: Long?,
            name: String,
            mime: String?
        ): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_FS, fs)
                .putExtra(EXTRA_REMOTE, remote)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_MIME, mime.orEmpty())

        /**
         * Launches the player with `startActivity`.
         *
         * Prefer [intent] and a registered result launcher: the caller then
         * receives the "download instead" fallback, which is the whole point of
         * offering one.
         */
        fun start(
            context: Context,
            fs: String,
            remote: String,
            size: Long?,
            name: String,
            mime: String?
        ) {
            context.startActivity(intent(context, fs, remote, size, name, mime))
        }
    }
}
