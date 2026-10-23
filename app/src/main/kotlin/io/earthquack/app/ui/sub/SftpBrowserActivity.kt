package io.earthquack.app.ui.sub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.earthquack.app.R
import io.earthquack.app.databinding.ActivitySftpBrowserBinding
import io.earthquack.app.databinding.ItemSftpEntryBinding
import io.earthquack.app.ssh.ConnectionProfile
import io.earthquack.app.ssh.HostKeyPolicy
import io.earthquack.app.ssh.InteractiveHostKeyTrust
import io.earthquack.app.ssh.SftpEntry
import io.earthquack.app.ssh.SftpSession
import io.earthquack.app.ssh.SshConnectResult
import io.earthquack.app.ssh.SshConnection
import io.earthquack.app.ssh.SshConnectionFactory
import io.earthquack.app.ssh.SshFailure
import io.earthquack.app.ssh.SshServices
import io.earthquack.app.storage.DownloadCache
import io.earthquack.app.storage.RcloneDownloadPlanner
import io.earthquack.app.transfer.SubmitResult
import io.earthquack.app.transfer.TransferBackend
import io.earthquack.app.transfer.TransferController
import io.earthquack.app.transfer.TransferTask
import io.earthquack.app.transfer.TransferType
import io.earthquack.app.ui.SubScreenActivity
import io.earthquack.app.ui.formatSize
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * Path of the synthetic ".." row. Never resolved by the server: the adapter
 * turns it into a parent navigation, and it cannot collide with a real path
 * because no SFTP path may contain a NUL byte.
 */
private const val PARENT_ROW = "\u0000parent"

/**
 * Browses one connection profile's files over SFTP.
 *
 * ## The same profile, the same rules
 *
 * Connects through [SshServices]' factories like the terminal does, and is
 * handed [InteractiveHostKeyTrust] so an unfamiliar host key is *shown*
 * before it is recorded: first contact displays the fingerprint, and only an
 * explicit accept records it. A key that changed is refused outright — there
 * is no branch here that proceeds on it.
 *
 * ## Transfers are not this screen's job
 *
 * A tapped file is handed to the shared transfer manager and this screen
 * returns to browsing. Progress, cancellation and history live in
 * `TransfersActivity`, which survives this one closing — the reason the
 * manager exists. The row menu's "Files" action points here, so one saved
 * profile serves both the terminal and the file browser.
 */
class SftpBrowserActivity : SubScreenActivity() {

    override val titleRes = R.string.sftp_browser_title

    private lateinit var body: ActivitySftpBrowserBinding

    private val profiles by lazy { SshServices.profiles(this) }

    /** The profile being browsed; null when it was deleted since launch. */
    private var profile: ConnectionProfile? = null

    private val trust by lazy { InteractiveHostKeyTrust(SshServices.knownHosts(this)) }

    private var connection: SshConnection? = null
    private var session: SftpSession? = null

    /** Path currently listed. */
    private var currentPath: String = "/"

    /** Guards against a slow listing landing after a newer one started. */
    private var listingGeneration = 0
    private var connectGeneration = 0

    private val adapter = EntryAdapter { entry ->
        when {
            entry.path == PARENT_ROW -> navigateUp()
            entry.isDirectory -> navigate(entry.path)
            else -> download(entry)
        }
    }

    private val uploadLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { stageUpload(it) } }

    override fun inflateBody(inflater: LayoutInflater, container: ViewGroup?) {
        body = ActivitySftpBrowserBinding.inflate(inflater, container ?: return, false)
        container.addView(body.root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_PROFILE_ID)
        val resolved = id?.let { profiles.get(it) }
        if (resolved == null) {
            Toast.makeText(this, R.string.sftp_browser_profile_gone, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        profile = resolved
        shell.appbar.subtitle.text = resolved.endpoint
        shell.appbar.subtitle.visibility = View.VISIBLE

        body.listEntries.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        body.listEntries.adapter = adapter
        body.btnRetry.setOnClickListener { connect() }
        body.btnUpload.setOnClickListener { uploadLauncher.launch(arrayOf("*/*")) }

        showConnecting()
        connect()
    }

    override fun onDestroy() {
        // Stop any in-flight listing from touching a dead session.
        listingGeneration++
        connectGeneration++
        runCatching { session?.close() }
        runCatching { connection?.close() }
        super.onDestroy()
    }

    // ── Connecting, with host-key consent ────────────────────────────────────

    private fun connect() {
        val target = profile ?: return
        showConnecting()
        val generation = ++connectGeneration
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // Strict policy turns "unknown host" into a failure carrying
                // the fingerprint, instead of an auto-record.
                connectionFactory().connect(target.copy(hostKeyPolicy = HostKeyPolicy.STRICT))
            }
            when (result) {
                is SshConnectResult.Failed -> when (result.failure) {
                    is SshFailure.UnknownHost ->
                        askHostKey(target, result.failure.fingerprint)
                    else -> showFailure(result.failure.message)
                }
                is SshConnectResult.Connected -> {
                    if (generation == connectGeneration) onConnected(result.connection)
                    else runCatching { result.connection.close() }
                }
            }
        }
    }

    private fun onConnected(connected: SshConnection) {
        connection = connected
        session = connected.openSftp()
        body.btnUpload.visibility = View.VISIBLE
        lifecycleScope.launch {
            val home = runCatching {
                withContext(Dispatchers.IO) { requireSession().realPath("/") }
            }.getOrDefault("/")
            navigate(home)
        }
    }

    /**
     * Shows the fingerprint of an unfamiliar host and asks.
     *
     * Accepting records the key through the interactive trust and retries;
     * declining abandons the attempt without writing anything. There is no
     * third path that connects anyway.
     */
    private fun askHostKey(target: ConnectionProfile, fingerprint: String) {
        showFailureWithRetry(getString(R.string.sftp_browser_host_key_prompt, target.endpoint))
        body.btnRetry.visibility = View.GONE
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profiles_test_host_key_unknown_title)
            .setMessage(
                getString(
                    R.string.profiles_test_host_key_unknown_body,
                    target.endpoint,
                    fingerprint
                )
            )
            .setNegativeButton(R.string.profiles_test_decline) { _, _ ->
                trust.rejectPending()
                showFailureWithRetry(getString(R.string.sftp_browser_declined))
                body.btnRetry.visibility = View.GONE
            }
            .setPositiveButton(R.string.profiles_test_accept) { _, _ ->
                if (trust.acceptPending(target.id)) connect()
            }
            .setCancelable(false)
            .show()
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    private fun navigate(path: String) {
        val s = session ?: return
        val generation = ++listingGeneration
        body.textPath.text = path
        showConnecting()
        lifecycleScope.launch {
            val listing = runCatching {
                withContext(Dispatchers.IO) { s.list(path) }
            }
            if (generation != listingGeneration) return@launch
            listing.onSuccess { entries ->
                currentPath = path
                val sorted = entries
                    .filter { it.name != "." && it.name != ".." }
                    .sortedWith(compareByDescending<SftpEntry> { it.isDirectory }.thenBy { it.name })
                adapter.submit(parentRow(path) + sorted)
                showListing(empty = sorted.isEmpty())
            }
            listing.onFailure { showFailure(it.message ?: getString(R.string.sftp_browser_failed)) }
        }
    }

    private fun navigateUp() {
        val parent = currentPath.trimEnd('/').substringBeforeLast('/', "")
        navigate(if (parent.isEmpty()) "/" else parent)
    }

    /** The synthetic ".." row; absent at the root. */
    private fun parentRow(path: String): List<SftpEntry> =
        if (path == "/") emptyList()
        else listOf(
            SftpEntry(
                name = "..",
                path = PARENT_ROW,
                isDirectory = true,
                size = 0L,
                lastModifiedMillis = 0L
            )
        )

    // ── Transfer handoff ─────────────────────────────────────────────────────

    private fun download(entry: SftpEntry) {
        val target = profile ?: return
        val destination = downloadDestination(target, entry)
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            type = TransferType.DOWNLOAD,
            backend = TransferBackend.Sftp(
                profileId = target.id,
                localFile = destination,
                remotePath = entry.path
            ),
            displayName = entry.name,
            sourceLabel = target.name,
            destination = destination,
            totalBytes = entry.size,
            createdAt = System.currentTimeMillis()
        )
        when (val result = TransferController.submit(this, task)) {
            is SubmitResult.Accepted ->
                Toast.makeText(this, getString(R.string.sftp_browser_queued, entry.name), Toast.LENGTH_SHORT).show()
            is SubmitResult.Duplicate ->
                Toast.makeText(this, R.string.transfers_duplicate, Toast.LENGTH_SHORT).show()
            is SubmitResult.Rejected ->
                Toast.makeText(this, result.reason, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Copies a picked document into the app cache, then queues the upload.
     *
     * The copy exists because the transfer manager stages through a *file*
     * path, and a SAF content URI is not one. It is deleted when the transfer
     * reaches a terminal state, whether or not it succeeded: the bytes belong
     * to the server afterwards, and the user's original is untouched.
     */
    private fun stageUpload(uri: Uri) {
        val target = profile ?: return
        val name = displayNameOf(uri) ?: "upload"
        val staging = File(File(cacheDir, "sftp-uploads").apply { mkdirs() }, "${UUID.randomUUID()}-$name")
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        staging.outputStream().use { output -> input.copyTo(output) }
                    } != null
                }.getOrDefault(false)
            }
            if (!staged) {
                runCatching { staging.delete() }
                Toast.makeText(this@SftpBrowserActivity, R.string.sftp_browser_staging_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val remotePath = "${currentPath.trimEnd('/')}/$name"
            val task = TransferTask(
                id = UUID.randomUUID().toString(),
                type = TransferType.UPLOAD,
                backend = TransferBackend.Sftp(
                    profileId = target.id,
                    localFile = staging.absolutePath,
                    remotePath = remotePath
                ),
                displayName = name,
                sourceLabel = getString(R.string.sftp_browser_this_device),
                destination = remotePath,
                totalBytes = staging.length(),
                createdAt = System.currentTimeMillis()
            )
            when (TransferController.submit(this@SftpBrowserActivity, task)) {
                is SubmitResult.Accepted ->
                    Toast.makeText(this@SftpBrowserActivity, getString(R.string.sftp_browser_queued, name), Toast.LENGTH_SHORT).show()
                is SubmitResult.Duplicate ->
                    runCatching { staging.delete() }
                is SubmitResult.Rejected -> runCatching { staging.delete() }
            }
            // The staging copy is ours; observe the queue and remove it once
            // the transfer is over, in every terminal direction.
            lifecycleScope.launch {
                TransferController.manager(this@SftpBrowserActivity).tasks.collect { tasks ->
                    val found = tasks.firstOrNull { it.id == task.id } ?: return@collect
                    if (found.status.isTerminal) runCatching { staging.delete() }
                }
            }
        }
    }

    /**
     * Where a downloaded copy of [entry] lands.
     *
     * Under the same `opened/` root the Files screen uses, so the existing
     * cache screen, its retention policy and its `.part`-aware cleaner all
     * cover SFTP downloads without a second policy.
     */
    private fun downloadDestination(target: ConnectionProfile, entry: SftpEntry): String {
        val key = RcloneDownloadPlanner.cacheKey("sftp:${target.id}", entry.path, entry.size)
        val dir = File(DownloadCache.rootFor(cacheDir), "sftp/$key")
        return File(dir, RcloneDownloadPlanner.safeFileName(entry.name)).absolutePath
    }

    private fun displayNameOf(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } else null
        }

    // ── State visibility ─────────────────────────────────────────────────────

    private fun showConnecting() {
        body.textState.text = getString(R.string.sftp_browser_connecting)
        body.textState.visibility = View.VISIBLE
        body.btnRetry.visibility = View.GONE
        body.listEntries.visibility = View.GONE
    }

    private fun showListing(empty: Boolean) {
        body.textState.text = getString(R.string.sftp_browser_empty)
        body.textState.visibility = if (empty) View.VISIBLE else View.GONE
        body.btnRetry.visibility = View.GONE
        body.listEntries.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun showFailure(reason: String) {
        showFailureWithRetry(reason)
        body.btnRetry.visibility = View.VISIBLE
    }

    private fun showFailureWithRetry(reason: String) {
        body.textState.text = reason
        body.textState.visibility = View.VISIBLE
        body.listEntries.visibility = View.GONE
        body.btnUpload.visibility = View.GONE
    }

    private fun connectionFactory(): SshConnectionFactory = SshConnectionFactory(
        identityKeys = SshServices.identityKeys(this),
        secrets = SshServices.secretStore(this),
        knownHosts = trust,
        filesDir = filesDir
    )

    private fun requireSession(): SftpSession = session ?: error("session not open")

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"


        fun intent(context: android.content.Context, profileId: String): Intent =
            Intent(context, SftpBrowserActivity::class.java)
                .putExtra(EXTRA_PROFILE_ID, profileId)
    }
}

/** The remote listing as rows: folders and files, one synthetic parent. */
private class EntryAdapter(
    private val onClick: (SftpEntry) -> Unit
) : RecyclerView.Adapter<EntryAdapter.ViewHolder>() {

    private var items: List<SftpEntry> = emptyList()

    fun submit(entries: List<SftpEntry>) {
        items = entries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sftp_entry, parent, false)
        return ViewHolder(ItemSftpEntryBinding.bind(view))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemSftpEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SftpEntry) {
            binding.entryName.text = if (entry.path == PARENT_ROW) ".." else entry.name
            binding.entryIcon.setImageResource(
                if (entry.isDirectory) R.drawable.ic_eq_folder else R.drawable.ic_eq_doc
            )
            binding.entryMeta.text = metaOf(entry)
            binding.root.setOnClickListener { onClick(entry) }
        }

        private fun metaOf(entry: SftpEntry): String = when {
            entry.path == PARENT_ROW ->
                binding.root.context.getString(R.string.sftp_browser_up)
            entry.isDirectory ->
                binding.root.context.getString(R.string.sftp_browser_dir)
            entry.size > 0 ->
                "${formatSize(entry.size)} · ${ageOf(entry.lastModifiedMillis)}"
            else -> ageOf(entry.lastModifiedMillis)
        }

        private fun ageOf(millis: Long): String {
            if (millis <= 0L) return ""
            return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
        }
    }
}
