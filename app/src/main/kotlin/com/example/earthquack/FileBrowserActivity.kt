package com.example.earthquack

import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.earthquack.ServerConfig
import com.example.earthquack.databinding.ActivityFileBrowserBinding
import com.example.earthquack.storage.DirectoryListingCache
import com.example.earthquack.storage.DownloadTracker
import com.example.earthquack.storage.RcloneConfigManager
import com.example.earthquack.storage.RcloneDownloadPlanner
import com.example.earthquack.storage.RcloneDownloadRequest
import com.example.earthquack.storage.RcloneDownloadResult
import com.example.earthquack.storage.RcloneEngine
import com.example.earthquack.storage.RcloneException
import com.example.earthquack.storage.RcloneFileTransfer
import com.example.earthquack.storage.RcloneRemoteManager
import com.example.earthquack.ui.FileEntry
import com.example.earthquack.ui.FileEntryAdapter
import com.example.earthquack.ui.FileOpener
import com.example.earthquack.ui.FilePreviewActivity
import com.example.earthquack.ui.OpenTarget
import com.example.earthquack.ui.formatModified
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * A file browser backed by the embedded rclone.
 *
 * ## Where it starts
 *
 * Internal storage, reached with an on-the-fly local remote
 * (`:local:` + `context.filesDir`). The leading colon is what makes it
 * on-the-fly: rclone constructs the backend from the spec after the colon and
 * never looks for a config section. A bare `local:/…` would fail with
 * `500 didn't find section in config file`, because `local` is only a real
 * remote name once rclone.conf has one.
 *
 * ## The two remote-name forms
 *
 * - `<name>:` — a section from rclone.conf. Shown in the overflow menu, built
 *   from `config/listremotes`, never guessed.
 * - `:local:<absolute path>` — constructed on the fly, needs no config.
 *
 * Both end up in the same `fs` string, and navigation within a remote is always
 * `operations/list` with `fs` plus a `remote` path relative to it. Both keys are
 * required: omitting `remote` gives `400 Didn't find key "remote" in input`.
 *
 * ## What this screen does not do
 *
 * It does not write. It does read one file — [openFile] copies a tapped file
 * into a cache directory and hands it to an installed viewer through a
 * FileProvider — but there is no in-app viewer, no upload, and no editing.
 */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var adapter: FileEntryAdapter

    /** Present only once rclone initialised; absent means the screen is stuck. */
    private var engine: RcloneEngine? = null

    /** rclone filesystem root: either `<name>:` or `:local:<absolute path>`. */
    private var fs: String = ""

    /** Breadcrumb segments, relative to [fs]. Empty at the root of a remote. */
    private val path = mutableListOf<String>()

    /** Everything the current directory returned, before the search filter. */
    private var entries: List<FileEntry> = emptyList()

    private var query: String = ""

    /**
     * The listing cache, rooted in the app's cache dir so the OS prunes it under
     * storage pressure.
     *
     * Lazy because [getCacheDir] is a Context call, and a property initialiser
     * runs before the activity is attached to one. Making this a plain
     * `val = DirectoryListingCache(rootFor(cacheDir))` crashes during
     * construction with a NullPointerException on the Context's base — which
     * is what "clicking a drive closes the app" turned out to be.
     */
    private val listingCache: DirectoryListingCache by lazy {
        DirectoryListingCache(DirectoryListingCache.rootFor(cacheDir))
    }

    /** When the visible listing was fetched, for the freshness line. */
    private var lastRefreshAt: Long? = null

    /** Guards against a slow reply arriving after the user navigated on. */
    private var listFsAtRequest: String = ""
    private var listPathAtRequest: String = ""

    /** In-flight downloads, so a second tap of the same file is not queued twice. */
    private val downloads = DownloadTracker()

    /** The entry currently being downloaded, for the progress line. */
    private var transferring: FileEntry? = null

    /**
     * Opener for whatever we just downloaded.
     *
     * Lazy, and constructed with the application context: it outlives this
     * activity (the preview screen and the chooser both hold one), so an
     * activity reference here would keep a finished screen alive.
     */
    private val opener: FileOpener by lazy { FileOpener(applicationContext) }

    /** Coroutine job for the current download, cancellable. */
    private var downloadJob: Job? = null

    /** True when the user pressed stop; the result is discarded when it lands. */
    private var cancelled = false

    /** Set when the tap asked for a share instead of a plain open. */
    private var sharePending: FileEntry? = null

    /** Set when the tap asked to save the file out of the app's cache. */
    private var savePending: FileEntry? = null

    /** Where the picked save URI lands while [saveLauncher] runs. */
    private var pendingSavePath: String? = null

    /**
     * Picks where a downloaded file is saved.
     *
     * Registered in `onCreate` rather than built per use, because the activity
     * result API needs one registration per request kind and this screen makes
     * exactly one: "save this". A `startActivityForResult` would race the
     * result against the coroutine that produced the file.
     */
    private val saveLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val source = pendingSavePath?.let { File(it) }
        val target = result.data?.data
        if (result.resultCode != RESULT_OK || target == null || source == null) {
            pendingSavePath = null
            toast(getString(R.string.files_save_cancelled))
            return@registerForActivityResult
        }
        pendingSavePath = null
        lifecycleScope.launch {
            binding.progress.isVisible = true
            try {
                withContext(Dispatchers.IO) { copyTo(source, target) }
                toast(getString(R.string.files_saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "save failed: ${e.javaClass.simpleName}")
                toast(getString(R.string.files_save_failed, source.name))
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // A caller can open a specific location (a named remote, shared
        // storage). Without this the screen could only ever start in the app's
        // own directory, so reaching a remote required navigating the overflow
        // menu -- several taps, and the reason remotes looked unreachable.
        fs = intent.getStringExtra(EXTRA_FS)?.takeIf { it.isNotBlank() } ?: localRoot()

        adapter = FileEntryAdapter(
            onOpen = { entry -> if (entry.isDir) openFolder(entry) else openFile(entry) },
            onOverflow = { entry, anchor -> showEntryMenu(entry, anchor) }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        wireBar()
        wireSearch()
        wireBack()

        if (startEngine()) load()

        // Entries whose directory is never revisited still go away, so the
        // cache cannot grow while the user explores. Cheap, and off the main
        // thread; nothing here depends on its result.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                listingCache.prune(ServerConfig.getCacheRetentionDays(this@FileBrowserActivity))
            }
        }
    }

    override fun onDestroy() {
        // A download in flight is abandoned, not left running: the native
        // RPC cannot be interrupted, so the job is cancelled and the next
        // attempt's planner deletes the partial file before copying over it.
        // lifecycleScope is already cancelled by this point; this only stops
        // anything the activity still owns.
        downloadJob?.cancel()
        downloadJob = null
        transferring = null
        super.onDestroy()
    }

    // ── Wiring ────────────────────────────────────────────────────────────────

    private fun wireBar() {
        binding.appbar.btnBack.isVisible = true
        binding.appbar.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        // The partial offers one trailing slot; this screen supplies two
        // buttons of its own, so its slot stays empty rather than duplicated.
        binding.appbar.btnAction.isVisible = false
        binding.appbar.title.setText(R.string.files_title)
        // The partial hides the subtitle until something is put in it; this
        // screen always has a current path to qualify the title with.
        binding.appbar.subtitle.isVisible = true

        binding.btnOverflow.setOnClickListener { showLocationMenu(it) }
        binding.btnRetry.setOnClickListener { load() }
        binding.btnCancel.setOnClickListener { cancelDownload() }
        binding.fab.setOnClickListener { load() }
    }

    private fun wireSearch() {
        binding.btnSearch.setOnClickListener {
            if (binding.searchRow.isVisible) closeSearch() else openSearch()
        }
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render()
            }
        })
        binding.searchField.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(view)
                true
            } else {
                false
            }
        }
    }

    /**
     * Back walks out of the browser before it leaves the screen: search first,
     * then up one directory, then back to the caller. Closing the search field
     * before popping a directory is deliberate, since a filter applied to the
     * directory you are leaving is just noise.
     */
    private fun wireBack() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.searchRow.isVisible -> closeSearch()
                        path.isNotEmpty() -> {
                            path.removeAt(path.lastIndex)
                            load()
                        }
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        )
    }

    // ── Download and open ────────────────────────────────────────────────────

    /**
     * Taps on a file come here: fetch it to cache, then hand it to an app that
     * can display it. There is no in-app viewer, and none is planned — Android
     * already ships better ones for every one of these types.
     *
     * The whole transfer runs in one [lifecycleScope] coroutine, so leaving the
     * screen cancels it (and the partial file with it) rather than leaving a
     * native RPC running against a finished activity.
     */
    private fun openFile(entry: FileEntry) {
        val rpc = engine
        if (rpc == null) {
            toast(getString(R.string.state_unavailable))
            return
        }

        val planner = RcloneDownloadPlanner(cacheDir.absolutePath)
        val token = UUID.randomUUID().toString()
        val request = planner.request(
            srcFs = fs,
            srcRemote = entry.path,
            fileName = entry.name.ifBlank { entry.path.substringAfterLast('/') },
            token = token
        )

        // Second tap of the same row: the first download is either running or
        // already finished, and starting another would race for the same name.
        if (!downloads.tryStart(request.localFile)) {
            toast(getString(R.string.files_download_running))
            return
        }
        if (transferring != null) {
            // One transfer at a time. Two at once would interleave progress
            // reporting on one progress bar, which reads as a bug.
            downloads.finish(request.localFile)
            toast(getString(R.string.files_download_running))
            return
        }

        cancelled = false
        transferring = entry
        showTransferProgress(entry)
        binding.btnCancel.isVisible = true

        downloadJob = lifecycleScope.launch {
            try {
                val transfer = RcloneFileTransfer(rpc)
                val result = transfer.download(
                    request,
                    entry.size,
                    onProgress = { copied ->
                        // The poller runs on IO; the progress bar does not.
                        withContext(Dispatchers.Main) {
                            showTransferProgress(transferring ?: entry, copied)
                        }
                    }
                )

                if (cancelled) {
                    // The user stopped it. The bytes were already written before
                    // the cancellation could land, so a full file is sitting at
                    // the destination of a download nobody wants; delete it
                    // rather than leaving nothing for the OS to prune.
                    val path = (result as? RcloneDownloadResult.Success)?.localPath
                        ?: request.localFile
                    File(path).delete()
                    throw CancellationException()
                }

                when (result) {
                    is RcloneDownloadResult.Success ->
                        openDownloaded(request, result, entry)
                    is RcloneDownloadResult.Failure -> toast(result.detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Covers the unwritable-cache and unexpected-native cases; the
                // transfer maps rclone's own errors onto Failure itself.
                Log.w(TAG, "download failed: ${e.javaClass.simpleName}")
                toast(getString(R.string.files_download_failed))
            } finally {
                downloads.finish(request.localFile)
                transferComplete()
            }
        }
    }

    /**
     * After a successful copy: resolve the type, find a viewer, open.
     *
     * The failure modes here are about the *receiving* half, so they are
     * reported separately from a failed download: a file that downloaded fine
     * and then found no viewer is a different problem from one that failed.
     */
    private fun openDownloaded(
        request: RcloneDownloadRequest,
        result: RcloneDownloadResult.Success,
        entry: FileEntry
    ) {
        val file = File(result.localPath)

        // An empty or unreadable file must never reach a viewer: some apps
        // crash on it, and the user would blame Android, not EarthQuack.
        if (!file.isFile || file.length() <= 0L) {
            file.delete()
            toast(getString(R.string.files_download_failed))
            return
        }

        // A tap that asked for something other than a plain open gets that,
        // now that the bytes are here.
        sharePending?.let { requested ->
            sharePending = null
            shareDownloaded(requested, file)
            return
        }
        savePending?.let { requested ->
            savePending = null
            saveToDownloads(requested, result.localPath)
            return
        }

        when (val target = opener.target(file.name, entry.mimeType)) {
            is OpenTarget.Preview -> {
                // Image, video or audio: shown by us rather than handed to
                // another app, because a viewer has to be installed and this
                // one is always present.
                FilePreviewActivity.start(
                    this@FileBrowserActivity,
                    result.localPath,
                    target.kind,
                    file.name
                )
                Log.i(TAG, "previewing ${target.kind} ${file.name}")
            }
            is OpenTarget.External -> openExternally(file, target.mime, file.name)
        }
    }

    /**
     * Puts a downloaded file on the share sheet.
     *
     * A separate stream from [openExternally] because ACTION_SEND takes a
     * `content://` URI in `EXTRA_STREAM`, not a view target, and the type is
     * read off the file rather than assumed. Videos are shared as files even
     * though this app can preview them, because "share" almost always means
     * "send the original", and the receiving app decides what it can do.
     */
    private fun shareDownloaded(entry: FileEntry, file: File) {
        val uri = try {
            FileProvider.getUriForFile(this, FileOpener.authorityFor(this), file)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "no provider root for ${file.path}")
            toast(getString(R.string.files_share_failed, entry.name))
            return
        }

        val mime = opener.mimeType(entry.name, entry.mimeType)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, entry.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            send,
            getString(R.string.files_share_title, entry.name)
        )
        // Reported if the destination app is not running — the file stays in
        // the cache, so a retry costs nothing.
        try {
            startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            toast(getString(R.string.files_share_failed, entry.name))
        } catch (e: SecurityException) {
            Log.w(TAG, "share refused the grant", e)
            toast(getString(R.string.files_share_failed, entry.name))
        }
    }

    /**
     * Hands [file] to another app through the system chooser.
     *
     * [Intent.createChooser] rather than a bare ACTION_VIEW for one reason: the
     * chooser is the only way the user sees *every* app that can open a type.
     * A resolved intent jumps straight to whichever app Android ranked first,
     * and the only way to reach a different one is to clear the default. The
     * title names the file, which is the thing the user is choosing an app
     * *for*.
     *
     * When only one app can handle the type, Android skips the chooser and
     * opens it directly — the sheet only appears when there is a choice, so
     * showing one unconditionally is not possible.
     */
    private fun openExternally(file: File, mime: String, displayName: String) {
        val uri = try {
            FileProvider.getUriForFile(this, FileOpener.authorityFor(this), file)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "no provider root for ${file.path}")
            toast(getString(R.string.files_open_failed))
            return
        }

        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // A chooser is itself an activity started with this app's identity, so
        // the grant reaches the target the user picks without any extra flags.
        val chooser = Intent.createChooser(
            view,
            getString(R.string.files_open_with, displayName)
        )
        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            // No app for this type anywhere on the device.
            dialog(getString(R.string.files_download_no_viewer, mime))
        } catch (e: SecurityException) {
            Log.w(TAG, "chooser refused the grant", e)
            toast(getString(R.string.files_open_failed))
        }
    }

    /** Shows the existing indeterminate progress line, or a determinate one. */
    private fun showTransferProgress(entry: FileEntry?, copied: Long = -1L) {
        binding.progress.isVisible = true
        val expected = entry?.size
        val determinate = expected != null && expected > 0L && copied >= 0L
        binding.progress.isIndeterminate = !determinate
        if (determinate) {
            val percent = (((copied * 100) / expected).toInt()).coerceIn(0, 100)
            binding.progress.setProgressCompat(percent, false)
        }
        binding.appbar.subtitle.text = getString(
            R.string.files_downloading,
            entry?.name ?: ""
        )
    }

    /**
     * Restores the screen after a transfer, successful or not.
     *
     * Reached from the download coroutine's `finally`, so it must be safe to
     * call while the activity is finishing; touching the binding at that point
     * is fine because the views are still the ones the coroutine created.
     */
    private fun transferComplete() {
        transferring = null
        binding.progress.isVisible = false
        binding.progress.isIndeterminate = true
        binding.btnCancel.isVisible = false
        binding.appbar.subtitle.text = fullPath()
    }

    /**
     * The stop button. Cancels the coroutine; the native copy cannot be
     * interrupted, so the transfer finishes writing and then throws away the
     * result and deletes the partial file. What the user sees is an immediate
     * stop of the indicator, and no file is opened.
     */
    private fun cancelDownload() {
        val job = downloadJob ?: return
        // Nothing to stop if the transfer already finished; the button is
        // hidden by then, but a queued click could still arrive.
        if (job.isCompleted || !job.isActive) return
        cancelled = true
        job.cancel()
        toast(getString(R.string.files_download_cancelled))
    }

    /**
     * A short message. Toasts rather than a dialog for the failure cases, since
     * the user's next action is obvious: try another file, or retry by tapping
     * the row again.
     */
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * A blocking message for the one state the user has to act on: there is no
     * app installed for this type, so nothing will happen if they retry.
     */
    private fun dialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.action_dismiss, null)
            .show()
    }

    // ── rclone ────────────────────────────────────────────────────────────────

    /** @return false when rclone could not be initialised at all. */
    private fun startEngine(): Boolean {
        val started = try {
            val config = RcloneConfigManager(this)
            config.ensureReady()
            engine = RcloneEngine().also { it.initialize(config.configPath) }
            true
        } catch (e: Exception) {            // UnsatisfiedLinkError on a device we do not ship the .so for is the
            // likely case. The path is not logged: these are app-private
            // directories and the message says nothing useful about the cause.
            Log.w(TAG, "rclone could not be initialised: ${e.javaClass.simpleName}")
            showFailure(getString(R.string.state_unavailable))
            binding.fab.isEnabled = false
            binding.btnSearch.isEnabled = false
            false
        }
        if (started) binding.appbar.subtitle.text = fullPath()
        return started
    }

    /**
     * Lists the current directory and renders it.
     *
     * ## Cache first, then the network
     *
     * A listing is the round trip the user waits on, and the same directory gets
     * revisited constantly. So the RPC and the JSON parse both run on IO — the
     * parse would otherwise land back on the main thread, and a listing of a few
     * thousand entries is not something to parse while drawing — and a usable
     * cached reply is rendered before the RPC is even issued. The network then
     * runs silently in the background and the list is updated when it lands.
     *
     * The effect is that a directory that has been seen within its retention
     * window appears immediately, and one that has not costs the same wait as
     * before. See [DirectoryListingCache].
     */
    private fun load() {
        val rpc = engine ?: return
        val relative = path.joinToString("/")
        val retention = ServerConfig.getCacheRetentionDays(this)
        val cached = listingCache.load(fs, relative, retention)

        // Before the RPC, not after it: the caller has already changed [path],
        // so the trail has to follow the navigation even when the listing then
        // fails. A breadcrumb that disagrees with the directory it describes is
        // worse than an error message on its own.
        renderCrumbs()

        if (cached != null) {
            // A cached listing is drawn from the same reply object a fresh one
            // is, so the two cannot render differently.
            entries = parse(JSONObject(cached.reply))
            lastRefreshAt = cached.cachedAt
            render()
        } else {
            binding.progress.isVisible = true
            binding.stateGroup.isVisible = false
            lastRefreshAt = null
        }

        lifecycleScope.launch {
            try {
                listFsAtRequest = fs
                listPathAtRequest = relative
                val reply = withContext(Dispatchers.IO) {
                    rpc.call(METHOD_LIST, JSONObject().put("fs", fs).put("remote", relative))
                }
                val listed = withContext(Dispatchers.IO) { parse(reply) }
                // The user has navigated on while this was in flight: the reply
                // describes a directory that is no longer on screen.
                if (fs != listFsAtRequest || relative != listPathAtRequest) return@launch
                entries = listed
                lastRefreshAt = System.currentTimeMillis()
                listingCache.store(fs, relative, reply.toString())
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RcloneException) {
                // A stale listing is better than an error page: it is what the
                // directory looked like a moment ago, and it says so.
                if (cached != null) {
                    toast(describe(e))
                } else {
                    entries = emptyList()
                    adapter.submitList(emptyList())
                    showFailure(describe(e))
                }
            } catch (e: Exception) {
                if (cached != null) {
                    toast(getString(R.string.state_unavailable))
                } else {
                    entries = emptyList()
                    adapter.submitList(emptyList())
                    showFailure(getString(R.string.state_unavailable))
                }
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    /**
     * Turns one `operations/list` reply into rows.
     *
     * Folders first, then files, each group case-insensitively by name: in a
     * file browser, finding the folder you want is the common case and the
     * files below it are noise until you are looking for one.
     */
    private fun parse(reply: JSONObject): List<FileEntry> {
        val list = reply.optJSONArray(KEY_LIST) ?: return emptyList()
        val rows = ArrayList<FileEntry>(list.length())

        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val name = o.optString("Name")
            if (name.isEmpty()) continue

            rows += FileEntry(
                name = name,
                path = o.optString("Path").ifEmpty { name },
                isDir = o.optBoolean("IsDir"),
                // Null, not -1: a missing size and a zero-byte file are
                // different facts and the row renders them differently.
                size = if (o.has("Size") && !o.isNull("Size")) o.optLong("Size") else null,
                mimeType = o.optString("MimeType"),
                modified = formatModified(o.optString("ModTime")).orEmpty()
            )
        }

        return rows.sortedWith(
            compareByDescending<FileEntry> { it.isDir }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    /**
     * A failure message safe to put on screen.
     *
     * [RcloneException.message] embeds the HTTP-style status number, which is
     * meaningless to a user and looks like a bug report when it appears in a
     * list. The `error` field of rclone's own envelope is the human-readable
     * half, so that is what is shown; anything missing or unbounded falls back
     * to a plain sentence.
     */
    private fun describe(error: RcloneException): String {
        val detail = try {
            JSONObject(error.rcloneOutput).optString("error").trim()
        } catch (_: JSONException) {
            ""
        }.lineSequence().joinToString(" ").trim()

        return if (detail.isEmpty()) {
            getString(R.string.state_unavailable)
        } else {
            detail.take(MAX_DETAIL_CHARS)
        }
    }

    private suspend fun listRemotes(): List<String>? = try {
        withContext(Dispatchers.IO) {
            RcloneRemoteManager(engine ?: return@withContext null).listRemotes()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // An unreadable config is not the same as an empty one, so this returns
        // null rather than an empty list and the menu can say so.
        Log.w(TAG, "could not read rclone remotes: ${e.javaClass.simpleName}")
        null
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun openFolder(entry: FileEntry) {
        if (!entry.isDir) return
        // rclone's `operations/list` returns each entry's `Path` **relative to
        // `fs`** -- already the full path inside the remote, not one segment.
        // So descending REPLACES the path rather than appending to it.
        //
        // `path.add(entry.path)` joined the two and produced paths like
        // `Android/Android/obj`: the breadcrumb looked right while every listing
        // was of a directory that did not exist. That is what made Android/data
        // look locked -- it was a 404 from our own bug, not a permission
        // failure.
        //
        // `entry.path` is rclone's own escaping, so it is split only to rebuild
        // the breadcrumb segments, never re-assembled into a request.
        path.clear()
        path.addAll(entry.path.split('/').filter { it.isNotEmpty() })
        load()
    }

    private fun switchTo(target: String) {        fs = target
        path.clear()
        if (binding.searchRow.isVisible) closeSearch()
        load()
    }

    /** The on-the-fly local remote. Leading colon is what makes it on-the-fly. */
    private fun localRoot(): String = ":local:" + filesDir.absolutePath

    /**
     * Android shared storage as an on-the-fly local remote.
     *
     * `Environment.getExternalStorageDirectory()` is the real shared root
     * (`/storage/emulated/0`). An earlier version used
     * `getExternalFilesDir(null)?.parentFile`, which is
     * `/storage/emulated/0/Android/data/<pkg>` -- one level too deep and inside
     * the app's own sandbox, so tapping "Shared Storage" opened a directory
     * containing exactly one entry.
     */
    private fun sharedRoot(): String = sharedStorageFs(this)

    /** The current location as rclone would address it, e.g. `name:/a/b`. */
    private fun fullPath(): String =
        if (path.isEmpty()) fs else fs + path.joinToString("/", prefix = "/")

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun render() {
        val needle = query.trim()
        val visible = if (needle.isEmpty()) {
            entries
        } else {
            entries.filter { it.name.contains(needle, ignoreCase = true) }
        }

        adapter.submitList(visible)

        if (visible.isEmpty()) {
            // An empty result is not always an empty folder: Android hides the
            // Android/data and Android/obb subtrees from every app, so they list
            // as empty despite having contents. Saying "This folder is empty"
            // there is a false statement the user would act on.
            when {
                !needle.isEmpty() ->
                    showEmpty(String.format(Locale.getDefault(), NO_MATCH, needle))
                isAndroidPrivateSubtree() -> showBlocked()
                else -> showEmpty(getString(R.string.files_empty))
            }
        } else {
            binding.stateGroup.isVisible = false
        }
    }

    /**
     * True when the current path is inside Android's app-private subtrees.
     *
     * Matched on the path relative to `fs`, so it holds for a remote rooted
     * anywhere rather than only for shared storage.
     */
    private fun isAndroidPrivateSubtree(): Boolean {
        val relative = path.joinToString("/")
        return relative == "Android/data" || relative.startsWith("Android/data/") ||
            relative == "Android/obb" || relative.startsWith("Android/obb/")
    }

    /**
     * States the OS restriction instead of claiming the folder is empty.
     *
     * The icon changes too: a folder glyph beside "not accessible to apps"
     * reads as "nothing here yet", which is the opposite of the truth.
     */
    private fun showBlocked() {
        binding.stateGroup.isVisible = true
        binding.stateTitle.isVisible = true
        binding.stateTitle.setText(R.string.files_blocked_title)
        binding.stateText.setText(R.string.files_blocked_body)
        binding.stateIcon.setImageResource(R.drawable.ic_eq_shield)
        binding.stateIcon.imageTintList =
            ContextCompat.getColorStateList(this, R.color.eq_warning)
        binding.btnRetry.isVisible = false
    }

    private fun showEmpty(message: String) {
        binding.stateGroup.isVisible = true
        // Hide the heading: showBlocked() sets it, and leaving it visible would
        // label the next genuinely-empty folder "Not accessible to apps".
        binding.stateTitle.isVisible = false
        binding.stateIcon.setImageResource(R.drawable.ic_eq_folder)
        binding.stateIcon.imageTintList =
            ContextCompat.getColorStateList(this, R.color.eq_text_muted)
        binding.stateText.setText(message)
        binding.btnRetry.isVisible = false
    }

    private fun showFailure(message: String) {
        binding.stateGroup.isVisible = true
        binding.stateIcon.setImageResource(R.drawable.ic_eq_warning)
        binding.stateIcon.imageTintList =
            ContextCompat.getColorStateList(this, R.color.eq_error)
        binding.stateText.text = message
        binding.btnRetry.isVisible = true
    }

    /**
     * Rebuilds the breadcrumb strip: a chip for the root plus one per segment,
     * separated by chevrons, each chip navigating to its own depth.
     *
     * Rebuilt from scratch rather than reused: the strip is a handful of views
     * and recycling them correctly is more code than making them.
     */
    private fun renderCrumbs() {
        val host = binding.crumbs
        host.removeAllViews()

        // The root chip is also the location switcher.
        //
        // The only other route to a remote was an overflow > submenu > entry,
        // three taps with no visual cue that remotes existed at all -- so
        // remotes created in Storage looked unreachable from the browser. A
        // breadcrumb's first chip being "where am I, go elsewhere" is the
        // obvious affordance, and it matches the reference design's
        // "Google Drive > Documents" row.
        // A plain root chip again. It briefly opened the location menu, which put
        // a second, differently-coloured control in the breadcrumb strip for a
        // second route to the same place; the app-bar overflow is the one place
        // locations are chosen.
        host.addView(crumb(rootLabel(), 0))
        path.forEachIndexed { index, segment ->
            host.addView(separator())
            host.addView(crumb(segment, index + 1))
        }

        binding.appbar.subtitle.text = fullPath()
        // Scroll after layout: fullScroll needs a measured child.
        binding.crumbScroll.post { binding.crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    /**
     * The root chip's label: the remote's name where there is one, and the
     * literal "Internal storage" for the on-the-fly local remote, whose
     * "name" is an empty string between two colons.
     */
    /**
     * Label for the root chip.
     *
     * A named remote shows its own name. An on-the-fly local remote has no name
     * -- `substringBefore(':')` is empty for `:local:` -- so it is distinguished
     * by which directory it points at. Calling every on-the-fly path "Internal
     * Storage" was wrong when the path was shared storage.
     */
    private fun rootLabel(): String = when {
        fs == sharedRoot() -> getString(R.string.storage_shared)
        fs == localRoot() -> getString(R.string.storage_internal)
        else -> fs.substringBefore(':').ifEmpty { getString(R.string.files_title) }
    }

    private fun crumb(label: String, depth: Int): TextView {
        val horizontal = dp(R.dimen.eq_gap_md)
        val vertical = dp(R.dimen.eq_gap_sm)

        return TextView(this).apply {
            TextViewCompat.setTextAppearance(
                this, R.style.TextAppearance_Eq_RowSubtitle
            )
            text = label
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(horizontal, vertical, horizontal, vertical)
            minHeight = dp(R.dimen.eq_touch_min)
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_eq_crumb)
            // The deepest chip is the one you are in. Position already carries
            // that, so the tint is emphasis rather than the only signal.
            if (depth == path.size) {
                setTextColor(ContextCompat.getColor(this@FileBrowserActivity, R.color.eq_primary))
            }
            setOnClickListener {
                val target = depth
                while (path.size > target) path.removeAt(path.lastIndex)
                load()
            }
        }
    }

    private fun separator(): View {
        val side = dp(R.dimen.eq_gap_xs)
        val size = dp(R.dimen.eq_icon_sm)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, 0, side, 0)
            addView(ImageView(this@FileBrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                setImageResource(R.drawable.ic_eq_chevron)
                imageTintList = ContextCompat.getColorStateList(
                    this@FileBrowserActivity, R.color.eq_text_muted
                )
                contentDescription = null
            })
        }
    }

    private fun dp(id: Int): Int = resources.getDimensionPixelSize(id)

    // ── Menus ─────────────────────────────────────────────────────────────────

    /**
     * Per-row overflow.
     *
     * Six actions, and the order is the one a file browser's menu is expected to
     * have: Open first because it is what the row itself does, then the two
     * destructive-ish ones (share, save) a user looks for before deletion, then
     * Copy path, then Delete — which is last and apart because it is the only
     * action here that cannot be undone.
     *
     * Both file actions (Open and Share) are disabled while a transfer runs, so
     * the menu can never offer something the row would refuse.
     */
    private fun showEntryMenu(entry: FileEntry, anchor: View) {
        val menu = PopupMenu(this, anchor)
        val busy = transferring != null

        var order = 0
        menu.menu.add(Menu.NONE, MENU_OPEN, order++, getString(R.string.files_open))
            .isEnabled = !busy
        menu.menu.add(Menu.NONE, MENU_SHARE, order++, getString(R.string.files_share))
            .isEnabled = !busy
        menu.menu.add(Menu.NONE, MENU_SAVE, order++, getString(R.string.files_save_to_device))
            .isEnabled = !busy
        menu.menu.add(Menu.NONE, MENU_COPY_PATH, order++, getString(R.string.files_copy_path))
        menu.menu.add(Menu.NONE, MENU_DELETE, order++, getString(R.string.files_delete))

        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_OPEN -> {
                    if (transferring == null) openFile(entry)
                    true
                }
                MENU_SHARE -> {
                    if (transferring == null) shareFile(entry)
                    true
                }
                MENU_SAVE -> {
                    if (transferring == null) saveFileToDownloads(entry)
                    true
                }
                MENU_COPY_PATH -> {
                    copyToClipboard(fullPath() + "/" + entry.path)
                    true
                }
                MENU_DELETE -> {
                    confirmDelete(entry)
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    /**
     * Share the file with another app, through the system's share sheet.
     *
     * The file is downloaded first if it is not cached: sharing a copy that is
     * only in the cache is the same download-and-open path, so it reuses
     * [openFile]'s transfer and only differs in what happens at the end. That
     * is why [sharePending] is a field rather than a parameter — the decision
     * of what to do with a finished file is made at tap time and honoured when
     * the transfer completes.
     */
    private fun shareFile(entry: FileEntry) {
        sharePending = entry
        openFile(entry)
    }

    /** Saves the file into Downloads using the system document picker. */
    private fun saveFileToDownloads(entry: FileEntry) {
        savePending = entry
        openFile(entry)
    }

    /**
     * Deletes [entry] on the remote, after asking.
     *
     * The confirmation is not decoration: `operations/deletefile` removes the
     * object from the remote, and a remote is often a backup. A single tap that
     * sends a 200 MB document to the trash, with the OS's own back gesture one
     * second away from a completed download, is the wrong default.
     *
     * Directories are refused outright rather than recursed: rclone's
     * `operations/purge` would remove a whole subtree, and the browser screen
     * has no reason to be the place that happens. `operations/rmdir` on a
     * non-empty directory fails, which rclone reports as an error and the user
     * sees as a message.
     */
    private fun confirmDelete(entry: FileEntry) {
        val rpc = engine ?: return

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.files_delete))
            .setMessage(
                getString(
                    if (entry.isDir) R.string.files_delete_confirm_dir
                    else R.string.files_delete_confirm,
                    entry.name
                )
            )
            .setNegativeButton(R.string.action_dismiss, null)
            .setPositiveButton(R.string.files_delete) { _, _ ->
                lifecycleScope.launch {
                    binding.progress.isVisible = true
                    try {
                        withContext(Dispatchers.IO) {
                            rpc.call(
                                METHOD_DELETE,
                                JSONObject().put("fs", fs).put("remote", entry.path)
                            )
                        }
                        // The row leaves the list without a re-list, and the
                        // cached listing goes with it so a deleted file cannot
                        // reappear on the next visit.
                        listingCache.invalidate(fs, path.joinToString("/"))
                        entries = entries.filterNot { it.path == entry.path }
                        render()
                        toast(getString(R.string.files_deleted))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "delete failed: ${e.javaClass.simpleName}")
                        toast(getString(R.string.files_delete_failed, entry.name))
                    } finally {
                        binding.progress.isVisible = false
                    }
                }
            }
            .show()
    }

    /**
     * Writes a downloaded file into the place the user picks.
     *
     * Saved through `ACTION_CREATE_DOCUMENT` rather than into a fixed Downloads
     * folder: writing to the public Downloads collection needs the MediaStore
     * API, and before API 29 that needs a permission this screen has no
     * business asking for. The picker is one tap for the user, zero permissions
     * for us -- and it lets them rename the file, which a silently chosen name
     * never can.
     *
     * The picker is launched here rather than the copy, because the copy needs
     * the URI the picker hands back; [saveLauncher] finishes the job.
     */
    private fun saveToDownloads(entry: FileEntry, localPath: String) {
        pendingSavePath = localPath
        saveLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = opener.mimeType(entry.name, entry.mimeType)
                putExtra(Intent.EXTRA_TITLE, entry.name)
            }
        )
    }

    /**
     * Copies a downloaded file into the picked URI in fixed-size chunks.
     *
     * A stream copy, never a ByteArray: the file may be a 1.6 GB video, which
     * does not fit in memory and which the download path already streamed.
     */
    private fun copyTo(source: File, target: android.net.Uri) {
        contentResolver.openOutputStream(target)?.use { out ->
            source.inputStream().use { input ->
                input.copyTo(out, DEFAULT_COPY_BUFFER)
            }
            out.flush()
        } ?: throw IllegalStateException("no output stream for $target")
    }

    /**
     * Location switcher.
     *
     * A flat list, not a submenu. The earlier shape was
     * "overflow > Open > <submenu> > remote", which buried the only way to
     * reach a configured remote three taps deep with no visual cue that one
     * existed. Every location is now one row, in a menu anchored to whatever
     * opened it.
     *
     * The cache section is here rather than in Settings because it belongs to
     * this screen: it changes what this screen shows, and a preference that can
     * only be reached from a Settings screen three taps away might as well not
     * exist. It is last, so a location -- the reason the menu was opened --
     * is still where the finger lands.
     */
    private fun showLocationMenu(anchor: View) {
        lifecycleScope.launch {
            val remotes = listRemotes()
            val menu = PopupMenu(this@FileBrowserActivity, anchor)

            menu.menu.add(Menu.NONE, MENU_INTERNAL, 0, getString(R.string.storage_internal))
                .isCheckable = true
            menu.menu.add(Menu.NONE, MENU_SHARED, 1, getString(R.string.storage_shared))
                .isCheckable = true

            // Names come from config/listremotes. Nothing is invented: an empty
            // config lists nothing and says where to create one.
            val names = remotes.orEmpty().sortedBy { it.lowercase() }
            if (names.isEmpty()) {
                menu.menu.add(Menu.NONE, MENU_NO_REMOTES, 2, LABEL_NO_REMOTES).isEnabled = false
                menu.menu.add(Menu.NONE, MENU_NO_REMOTES_NOTE, 3, LABEL_NO_REMOTES_NOTE)
                    .isEnabled = false
            } else {
                names.forEachIndexed { index, name ->
                    menu.menu.add(Menu.NONE, MENU_REMOTE_BASE + index, 10 + index, name)
                        .isCheckable = true
                }
            }

            // Mark the current location so the menu states where you already are.
            val currentId = when {
                fs == localRoot() -> MENU_INTERNAL
                fs == sharedRoot() -> MENU_SHARED
                else -> {
                    val remote = fs.substringBefore(':')
                    val idx = names.indexOf(remote)
                    if (idx >= 0) MENU_REMOTE_BASE + idx else null
                }
            }
            currentId?.let { menu.menu.findItem(it)?.isChecked = true }

            // Listed from the same constants the menu that wrote them used, so
            // the two cannot drift apart as options are added.
            val retention = ServerConfig.getCacheRetentionDays(this@FileBrowserActivity)
            ServerConfig.CACHE_RETENTION_CHOICES.forEachIndexed { index, days ->
                menu.menu.add(
                    Menu.NONE,
                    MENU_CACHE_BASE + index,
                    30 + index,
                    if (days == 0) {
                        getString(R.string.files_cache_choice_off)
                    } else if (days == 1) {
                        getString(R.string.files_cache_choice_day)
                    } else {
                        getString(R.string.files_cache_choice_days, days)
                    }
                ).isCheckable = true
            }
            ServerConfig.CACHE_RETENTION_CHOICES
                .indexOfFirst { it == retention }
                .takeIf { it >= 0 }
                ?.let { menu.menu.findItem(MENU_CACHE_BASE + it)?.isChecked = true }

            menu.setOnMenuItemClickListener { item ->
                when {
                    item.itemId == MENU_INTERNAL -> { openLocation(localRoot()); true }
                    item.itemId == MENU_SHARED -> { openLocation(sharedRoot()); true }
                    item.itemId == MENU_NO_REMOTES -> true
                    item.itemId == MENU_NO_REMOTES_NOTE -> true
                    item.itemId >= MENU_CACHE_BASE -> {
                        val idx = item.itemId - MENU_CACHE_BASE
                        val days = ServerConfig.CACHE_RETENTION_CHOICES.getOrNull(idx)
                        if (days != null) {
                            setRetentionDays(days)
                            true
                        } else {
                            false
                        }
                    }
                    item.itemId >= MENU_REMOTE_BASE -> {
                        val idx = item.itemId - MENU_REMOTE_BASE
                        val name = names.getOrNull(idx)
                        if (name != null) {
                            openLocation("$name:")
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            menu.show()
        }
    }

    /**
     * Stores the retention choice and immediately prunes by its new rule.
     *
     * Pruned here rather than at the next load, because "keep listings for 7
     * days" that keeps thirty-day-old entries until something asks for them is
     * not what the setting says. The removal is cheap — one pass over the
     * cache directory on IO — and the count is reported so the user can see the
     * setting did something.
     */
    private fun setRetentionDays(days: Int) {
        ServerConfig.setCacheRetentionDays(this, days)
        lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) {
                listingCache.prune(ServerConfig.getCacheRetentionDays(this@FileBrowserActivity))
            }
            // A changed rule invalidates what is on screen only in the sense
            // that its freshness line now disagrees; relisting keeps the two
            // in step and shows the new rule working on the current directory.
            toast(
                if (removed > 0) {
                    getString(R.string.files_cache_pruned) + " ($removed)"
                } else {
                    getString(R.string.files_cache_pruned)
                }
            )
        }
    }

    /** Switches the whole view to [target], resetting the path. */
    private fun openLocation(target: String) {
        fs = target
        path.clear()
        query = ""
        renderCrumbs()
        load()
    }

    private fun openSearch() {
        binding.searchRow.isVisible = true
        binding.searchField.requestFocus()
        binding.searchField.setSelection(binding.searchField.text?.length ?: 0)
        getSystemService<InputMethodManager>()
            ?.showSoftInput(binding.searchField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        hideKeyboard(binding.searchField)
        binding.searchRow.isVisible = false
        binding.searchField.setText("")
        query = ""
        render()
    }

    private fun hideKeyboard(view: View) {
        getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.files_title), text)
        )
        android.widget.Toast.makeText(
            this, R.string.clipboard_copied, android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        private const val TAG = "FileBrowserActivity"

        private const val METHOD_LIST = "operations/list"

        /** Removes one file from a remote. Also refuses directories, on its own. */
        private const val METHOD_DELETE = "operations/deletefile"

        /** Chunk size for saving a file out of the app's cache. */
        private const val DEFAULT_COPY_BUFFER = 64 * 1024
        private const val KEY_LIST = "list"

        /** rclone's error text is not bounded; a state row is not a log file. */
        private const val MAX_DETAIL_CHARS = 160

        // Menu labels and one format string are literals rather than string
        // resources: strings.xml belongs to a different change and is out of
        // scope here. Everything that does have a resource uses it.
        private const val NO_MATCH = "Nothing here matches \"%1\$s\""
        private const val LABEL_NO_REMOTES = "No remotes configured"
        private const val LABEL_NO_REMOTES_NOTE = "Add one on the Storage screen"

        private const val MENU_OPEN = 1
        private const val MENU_SHARE = 2
        private const val MENU_SAVE = 9
        private const val MENU_COPY_PATH = 3
        private const val MENU_DELETE = 10
        private const val MENU_LOCATION = 4
        private const val MENU_SHARED = 8
        private const val MENU_INTERNAL = 5
        private const val MENU_NO_REMOTES = 6
        private const val MENU_NO_REMOTES_NOTE = 7

        /** Remote names are ids from here up; index is id minus this. */
        /** Remote names are ids from here up; index is id minus this. */
        private const val MENU_REMOTE_BASE = 100

        /** Retention options, menu ids from [MENU_CACHE_BASE] upward. */
        private const val MENU_CACHE_BASE = 200

        const val EXTRA_FS = "fs"

        /**
         * The shared-storage root as an rclone on-the-fly remote spec.
         *
         * Public and static because two screens need it, and they previously
         * each derived it independently -- and one of them used
         * `getExternalFilesDir(null)?.parentFile`, which is
         * `/storage/emulated/0/Android/data/<pkg>` rather than the shared root.
         * A single definition removes the chance of that drifting again.
         */
        fun sharedStorageFs(context: Context): String =
            ":local:" + (android.os.Environment.getExternalStorageDirectory()?.absolutePath
                ?: context.getExternalFilesDir(null)?.absolutePath
                ?: context.filesDir.absolutePath)

        /** Opens the browser at the app's own directory. */
        fun intent(context: Context): Intent =
            Intent(context, FileBrowserActivity::class.java)

        /**
         * Opens the browser at [fs].
         *
         * `fs` is an rclone remote spec: `name:` for a configured remote,
         * `:local:/abs/path` for any directory on the device. This is what makes
         * a remote row in Storage tappable -- without it the browser could only
         * start at a fixed location and the user had to find a remote through
         * the location menu.
         */
        fun intent(context: Context, fs: String): Intent =
            Intent(context, FileBrowserActivity::class.java)
                .putExtra(EXTRA_FS, fs)
    }
}