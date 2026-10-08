package com.example.earthquack

import android.content.ClipData
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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.earthquack.databinding.ActivityFileBrowserBinding
import com.example.earthquack.storage.RcloneConfigManager
import com.example.earthquack.storage.RcloneEngine
import com.example.earthquack.storage.RcloneException
import com.example.earthquack.storage.RcloneRemoteManager
import com.example.earthquack.ui.FileEntry
import com.example.earthquack.ui.FileEntryAdapter
import com.example.earthquack.ui.formatModified
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

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
 * It does not write. The per-row "Download" is disabled, because
 * `FileTransferService` downloads by *desktop file id* — the identifier the
 * desktop's HTTP file server assigns — and an rclone path is not one. Guessing
 * a URL from a path would produce a download that fails after the user waits
 * for it.
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
            onOpen = { entry -> if (entry.isDir) openFolder(entry) },
            onOverflow = { entry, anchor -> showEntryMenu(entry, anchor) }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        wireBar()
        wireSearch()
        wireBack()

        if (startEngine()) load()
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

    // ── rclone ────────────────────────────────────────────────────────────────

    /** @return false when rclone could not be initialised at all. */
    private fun startEngine(): Boolean {
        val started = try {
            val config = RcloneConfigManager(this)
            config.ensureReady()
            engine = RcloneEngine().also { it.initialize(config.configPath) }
            true
        } catch (e: Exception) {
            // UnsatisfiedLinkError on a device we do not ship the .so for is the
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
     * The RPC and the JSON parse both run on IO. `engine.call` is already a
     * suspend function on IO, but the parse would otherwise land back on the
     * main thread, and a listing of a few thousand entries is not something to
     * parse while drawing.
     */
    private fun load() {
        val rpc = engine ?: return
        binding.progress.isVisible = true
        binding.stateGroup.isVisible = false

        val root = fs
        val relative = path.joinToString("/")

        // Before the RPC, not after it: the caller has already changed [path],
        // so the trail has to follow the navigation even when the listing then
        // fails. A breadcrumb that disagrees with the directory it describes is
        // worse than an error message on its own.
        renderCrumbs()

        lifecycleScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    rpc.call(
                        METHOD_LIST,
                        JSONObject().put("fs", root).put("remote", relative)
                    )
                }
                val listed = withContext(Dispatchers.IO) { parse(reply) }
                entries = listed
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RcloneException) {
                entries = emptyList()
                adapter.submitList(emptyList())
                showFailure(describe(e))
            } catch (e: Exception) {
                entries = emptyList()
                adapter.submitList(emptyList())
                showFailure(getString(R.string.state_unavailable))
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
        // `Path` is relative to fs and is rclone's own escaping, so it is used
        // verbatim. Breadcrumb labels come from the same segments, which is why
        // a chip can show a separator-looking character only if a name has one.
        path.add(entry.path)
        load()
    }

    private fun switchTo(target: String) {
        fs = target
        path.clear()
        if (binding.searchRow.isVisible) closeSearch()
        load()
    }

    /** The on-the-fly local remote. Leading colon is what makes it on-the-fly. */
    private fun localRoot(): String = ":local:" + filesDir.absolutePath

    /**
     * Android shared storage as an on-the-fly local remote.
     *
     * `Environment.getExternalStorageDirectory()` rather than a literal path,
     * which is not guaranteed to be the same on every device.
     */
    private fun sharedRoot(): String =
        ":local:" + (getExternalFilesDir(null)?.parentFile?.absolutePath
            ?: android.os.Environment.getExternalStorageDirectory()?.absolutePath
            ?: filesDir.absolutePath)

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
            showEmpty(
                if (needle.isEmpty()) {
                    getString(R.string.files_empty)
                } else {
                    String.format(Locale.getDefault(), NO_MATCH, needle)
                }
            )
        } else {
            binding.stateGroup.isVisible = false
        }
    }

    private fun showEmpty(message: String) {
        binding.stateGroup.isVisible = true
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
        host.addView(locationCrumb())
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
     * The root chip, which opens the location menu rather than navigating.
     *
     * Distinguishing it visually from a path chip matters: a user tapping a
     * breadcrumb expects to go up, and this one opens a picker instead. It is
     * tinted with the primary colour to say so.
     */
    private fun locationCrumb(): TextView = crumb(rootLabel(), 0).apply {
        setTextColor(getColor(R.color.eq_primary))
        // Anchored to this chip, not to the app-bar overflow: a popup that opens
        // at the top of the screen when the finger was in the breadcrumb strip
        // reads as an unrelated action.
        setOnClickListener { showLocationMenu(this) }
    }

    private fun rootLabel(): String {
        val named = fs.substringBefore(':')
        return named.ifEmpty { getString(R.string.storage_internal) }
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
     * Download is present and disabled rather than absent, with the reason on
     * screen: [com.example.earthquack.FileTransferService] fetches
     * `{fileBaseUrl}/download/{id}`, where the id belongs to the desktop's own
     * HTTP file server. There is no mapping from an rclone path to that id, so
     * the honest state is "not available here".
     */
    private fun showEntryMenu(entry: FileEntry, anchor: View) {
        val menu = PopupMenu(this, anchor)

        val download = menu.menu.add(Menu.NONE, MENU_DOWNLOAD, 0, LABEL_DOWNLOAD)
        download.isEnabled = false
        val note = menu.menu.add(Menu.NONE, MENU_DOWNLOAD_NOTE, 1, LABEL_DOWNLOAD_NOTE)
        note.isEnabled = false

        menu.menu.add(Menu.NONE, MENU_COPY_PATH, 2, LABEL_COPY_PATH)

        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_COPY_PATH -> {
                    copyToClipboard(fullPath() + "/" + entry.path)
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    /**
     * Location switcher.
     *
     * A flat list, not a submenu. The earlier shape was
     * "overflow > Open > <submenu> > remote", which buried the only way to
     * reach a configured remote three taps deep with no visual cue that one
     * existed. Every location is now one row, in a menu anchored to whatever
     * opened it.
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

            menu.setOnMenuItemClickListener { item ->
                when {
                    item.itemId == MENU_INTERNAL -> { openLocation(localRoot()); true }
                    item.itemId == MENU_SHARED -> { openLocation(sharedRoot()); true }
                    item.itemId == MENU_NO_REMOTES -> true
                    item.itemId == MENU_NO_REMOTES_NOTE -> true
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
        private const val KEY_LIST = "list"

        /** rclone's error text is not bounded; a state row is not a log file. */
        private const val MAX_DETAIL_CHARS = 160

        // Menu labels and one format string are literals rather than string
        // resources: strings.xml belongs to a different change and is out of
        // scope here. Everything that does have a resource uses it.
        private const val NO_MATCH = "Nothing here matches \"%1\$s\""
        private const val LABEL_DOWNLOAD = "Download"
        private const val LABEL_DOWNLOAD_NOTE = "Not available: needs a desktop file id"
        private const val LABEL_COPY_PATH = "Copy path"
        private const val LABEL_OPEN = "Open"
        private const val LABEL_NO_REMOTES = "No remotes configured"
        private const val LABEL_NO_REMOTES_NOTE = "Add one on the Storage screen"

        private const val MENU_DOWNLOAD = 1
        private const val MENU_DOWNLOAD_NOTE = 2
        private const val MENU_COPY_PATH = 3
        private const val MENU_LOCATION = 4
        private const val MENU_SHARED = 8
        private const val MENU_INTERNAL = 5
        private const val MENU_NO_REMOTES = 6
        private const val MENU_NO_REMOTES_NOTE = 7

        /** Remote names are ids from here up; index is id minus this. */
        private const val MENU_REMOTE_BASE = 100

        const val EXTRA_FS = "fs"

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