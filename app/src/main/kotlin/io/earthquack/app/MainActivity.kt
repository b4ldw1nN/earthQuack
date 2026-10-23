package io.earthquack.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.earthquack.app.databinding.ActivityMainBinding
import io.earthquack.app.state.SyncStateLabel
import io.earthquack.app.state.SystemStatusProvider
import io.earthquack.app.tailscale.TailscaleNodeMonitor
import io.earthquack.app.ssh.ConnectionProfile
import io.earthquack.app.ui.ClipboardFragment
import io.earthquack.app.ui.ConnectionsFragment
import io.earthquack.app.ui.HomeFragment
import io.earthquack.app.ui.ProfilesFragment
import io.earthquack.app.ui.ServicesFragment
import io.earthquack.app.ui.SettingsFragment
import io.earthquack.app.ui.SftpFragment
import io.earthquack.app.ui.StorageFragment
import io.earthquack.app.ui.TerminalFragment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single-Activity host for the four tabs and the Services sub-screens.
 *
 * ## Why manual fragment transactions
 *
 * The project has no navigation library, and the redesign is not a good reason
 * to add one: the graph is four tabs plus three pushed sub-screens, which
 * `FragmentManager` handles directly. Avoiding a new dependency also keeps the
 * offline Gradle build working, and keeps the change to architecture minimal --
 * the brief was explicitly to keep XML/ViewBinding and not introduce libraries
 * for their own sake.
 *
 * Tabs are added once and then shown/hidden, rather than replaced, so each tab
 * keeps its scroll position and its loaded state when the user comes back.
 *
 * ## Status broadcast
 *
 * The service reports state via [ACTION_STATUS_UPDATE]. It is received here and
 * republished as [statusFlow] so any screen can observe it without each one
 * registering its own receiver. That matters because the old design had the
 * status logic living in the Activity, which forced it to be duplicated
 * anywhere else it was needed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var statusProvider: SystemStatusProvider

    private val _syncState = MutableStateFlow(SyncStateLabel.UNKNOWN)

    /** Latest state broadcast by the service. Starts [SyncStateLabel.UNKNOWN]. */
    val syncState: StateFlow<SyncStateLabel> = _syncState.asStateFlow()

    /** Tabs that have been created, so they are not recreated on every switch. */
    private val tabFragments = mutableMapOf<Int, Fragment>()

    private var currentTabId: Int = R.id.nav_home

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STATUS_UPDATE) return
            val name = intent.getStringExtra(EXTRA_STATUS) ?: return
            _syncState.value = SyncStateLabel.fromName(name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Auto-start persistent network core (Tailscale) independently of clipboard.
        PersistentNetworkService.start(this)

        // Clipboard sync is OFF by default; the user activates it on demand.

        // Start rclone in the background: initialising it loads the native
        // library and the config file, which costs about a second. Paying it
        // here, off the main thread, means no screen stalls later.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { io.earthquack.app.storage.RcloneEngines.get(this@MainActivity) }
        }

        // The phone's own clipboard API, so the desktop can talk to the
        // phone directly instead of needing a server on the desktop.
        io.earthquack.app.clipboard.ClipboardApiServer.get(this)

        statusProvider = SystemStatusProvider(this)

        binding.bottomNav.setOnItemSelectedListener { item ->
            // Only switch tabs when nothing is pushed on top. Otherwise tapping
            // a tab while inside Connections should return to that tab, not
            // silently discard the pushed screen.
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                subScreenTitles.clear()
                subScreenSubtitles.clear()
                binding.content.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                binding.appbar.btnBack.setOnClickListener(null)
            }
            showTab(item.itemId)
            true
        }

        // Re-selecting the current tab should not rebuild it; the old code
        // restarted the top-level screen on every tap.
        binding.bottomNav.setOnItemReselectedListener { /* no-op */ }

        // One back path for both the toolbar arrow and the system gesture.
        // The deprecated onBackPressed() override is not reliably invoked by
        // the dispatcher, which left the pushed content popping while the app
        // bar stayed stale (or, from a restored state, exited the app). A
        // registered callback is the supported mechanism and always runs.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popSubScreen()) return
                // Nothing pushed: fall back to the platform's default finish.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        if (savedInstanceState == null) {
            showTab(R.id.nav_home)
        } else {
            currentTabId = savedInstanceState.getInt(KEY_CURRENT_TAB, R.id.nav_home)
            // Fragments are restored by the system; rebuild only the lookup map.
            // findFragment returns null for a tab that was never created, so
            // those ids are simply absent and get lazily created on first use.
            listOf(R.id.nav_home, R.id.nav_storage, R.id.nav_services, R.id.nav_settings)
                .forEach { id -> findFragment(id)?.let { tabFragments[id] = it } }
            binding.bottomNav.selectedItemId = currentTabId
        }

        // The navigation and the app bar must not be focusable.
        //
        // A soft keyboard that navigates between fields (or any focus
        // traversal) lands on a menu item, and BottomNavigationView *selects*
        // the item that has focus: the app then switched to that tab without
        // the user touching anything. TalkBack is unaffected — it uses
        // accessibility focus, not input focus.
        unfocusable(binding.bottomNav)
        unfocusable(binding.appbar.root)
    }

    /** Makes [view] and everything inside it unable to take input focus. */
    private fun unfocusable(view: View) {
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) unfocusable(view.getChildAt(i))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTabId)
    }

    /**
     * Looks up an existing tab fragment by its tag.
     *
     * Tab fragments are added directly to this activity's FragmentManager with
     * a stable tag, so a tag lookup is exact. The id-based lookup is deprecated
     * and ambiguous once two fragments share a container.
     */
    private fun findFragment(id: Int): Fragment? =
        supportFragmentManager.findFragmentByTag(tagFor(id))

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(statusReceiver) }
    }

    // ── Tabs ─────────────────────────────────────────────────────────────────

    private fun showTab(tabId: Int) {
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()

        // Hide whatever is visible, show the target. Created lazily so a tab the
        // user never visits costs nothing.
        tabFragments.values.forEach { transaction.hide(it) }

        val existing = tabFragments[tabId]
        if (existing != null && existing.isAdded) {
            transaction.show(existing)
        } else {
            val fragment = createFragment(tabId)
            tabFragments[tabId] = fragment
            transaction.add(R.id.tab_container, fragment, tagFor(tabId))
        }

        transaction.commit()
        currentTabId = tabId
        renderAppBar(tabId)
    }

    private fun createFragment(tabId: Int): Fragment = when (tabId) {
        R.id.nav_home -> HomeFragment()
        R.id.nav_storage -> StorageFragment()
        R.id.nav_services -> ServicesFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    private fun tagFor(tabId: Int): String = "tab:$tabId"

    private fun renderAppBar(tabId: Int) {
        val (title, subtitle) = when (tabId) {
            R.id.nav_home -> getString(R.string.app_name) to getString(R.string.home_tagline)
            R.id.nav_storage -> getString(R.string.nav_storage) to getString(R.string.storage_subtitle)
            R.id.nav_services -> getString(R.string.nav_services) to getString(R.string.services_subtitle)
            R.id.nav_settings -> getString(R.string.nav_settings) to ""
            else -> getString(R.string.app_name) to ""
        }

        binding.appbar.title.text = title
        binding.appbar.subtitle.text = subtitle
        binding.appbar.subtitle.visibility =
            if (subtitle.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        // Tabs are top level: nothing to go back to.
        binding.appbar.btnBack.visibility = android.view.View.GONE
    }

    // ── Pushing sub-screens ──────────────────────────────────────────────────

    /**
     * Pushes a Services sub-screen over the current tab.
     *
     * The bottom bar stays visible and the Services tab stays selected, which
     * is what the mockup shows for Connections, SFTP and Clipboard: they are
     * deeper within Services, not peers of it.
     */
    private val subScreenTitles = mutableListOf<String>()
    private val subScreenSubtitles = mutableListOf<String>()

    fun pushSubScreen(fragment: Fragment, title: String, subtitle: String) {
        val fm = supportFragmentManager
        // Allow sub-screens to stack (e.g. Connections -> Serve) so Back
        // returns to the previous sub-screen instead of the tab root.
        fm.beginTransaction()
            .replace(R.id.content, fragment)
            .addToBackStack(SUB_SCREEN)
            .commit()

        // The overlay sits above the tab container, so it only becomes opaque
        // while something is pushed. Leaving it opaque unconditionally hid
        // every tab behind a blank panel.
        binding.content.setBackgroundColor(getColor(R.color.eq_background))

        subScreenTitles.add(title)
        subScreenSubtitles.add(subtitle)

        binding.appbar.title.text = title
        binding.appbar.subtitle.text = subtitle
        binding.appbar.subtitle.visibility =
            if (subtitle.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.appbar.btnBack.visibility = android.view.View.VISIBLE
        // The back arrow must never hold the IME: a served view that is not an
        // editor takes the keyboard away from the screen that needs it (the
        // terminal could not raise its keyboard at all because of this).
        binding.appbar.btnBack.isFocusable = false
        binding.appbar.btnBack.isFocusableInTouchMode = false
        binding.appbar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.bottomNav.visibility = android.view.View.GONE

        if (binding.bottomNav.selectedItemId != R.id.nav_services) {
            binding.bottomNav.selectedItemId = R.id.nav_services
        }
    }

    /** Navigates to one of the Services sub-screens. */
    fun openConnections() = pushSubScreen(
        ConnectionsFragment(),
        getString(R.string.connections_title),
        getString(R.string.connections_subtitle)
    )

    fun openSftp() = pushSubScreen(
        SftpFragment(),
        getString(R.string.sftp_title),
        getString(R.string.sftp_subtitle)
    )

    fun openClipboard() = pushSubScreen(
        ClipboardFragment(),
        getString(R.string.clipboard_title),
        getString(R.string.clipboard_subtitle)
    )

    fun openProfiles() = pushSubScreen(
        ProfilesFragment(),
        getString(R.string.services_profiles),
        getString(R.string.profiles_subtitle)
    )

    fun openTerminal(profile: ConnectionProfile) = pushSubScreen(
        TerminalFragment.newInstance(profile.id),
        getString(R.string.terminal_title, profile.name),
        profile.endpoint
    )

    /** Switches to the Storage tab, popping any pushed sub-screen. */
    fun openStorage() {
        binding.bottomNav.selectedItemId = R.id.nav_storage
    }

    /** Switches to the Services tab. */
    fun openServices() {
        binding.bottomNav.selectedItemId = R.id.nav_services
    }

    /**
     * Pops the pushed sub-screen and restores the tab's app bar.
     *
     * Returns true when a sub-screen was pushed and has been popped, so the
     * caller knows the default (finish the activity) must not also run. This is
     * the single back path: the toolbar arrow, the system back gesture, and the
     * tab reselect all funnel through here, so the app bar is always reset in
     * step with the content instead of drifting out of sync.
     */
    private fun popSubScreen(): Boolean {
        if (supportFragmentManager.backStackEntryCount == 0) return false
        supportFragmentManager.popBackStack()
        if (subScreenTitles.isNotEmpty()) subScreenTitles.removeAt(subScreenTitles.lastIndex)
        if (subScreenSubtitles.isNotEmpty()) subScreenSubtitles.removeAt(subScreenSubtitles.lastIndex)
        if (subScreenTitles.isNotEmpty()) {
            binding.appbar.title.text = subScreenTitles.last()
            val sub = subScreenSubtitles.last()
            binding.appbar.subtitle.text = sub
            binding.appbar.subtitle.visibility = if (sub.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            binding.appbar.btnBack.visibility = android.view.View.VISIBLE
            binding.appbar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            binding.content.setBackgroundColor(getColor(R.color.eq_background))
        } else {
            renderAppBar(currentTabId)
            binding.appbar.btnBack.setOnClickListener(null)
            // Transparent again so the tab underneath is visible.
            binding.content.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.bottomNav.visibility = android.view.View.VISIBLE
        }
        return true
    }

    private companion object {
        const val KEY_CURRENT_TAB = "current_tab"
        const val SUB_SCREEN = "sub_screen"

    }
}
