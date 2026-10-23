package io.earthquack.app.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.earthquack.app.R
import io.earthquack.app.ssh.SshServices
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the profile editor losing the selected key.
 *
 * The defect: the editor kept the chosen identity-key alias in a plain
 * field, so any activity recreation reset it. Save then failed validation
 * ("public key authentication needs an imported key"), the user re-imported
 * the key, and nothing ever saved — the store ends up holding N identical
 * imports and an empty profile list. This drives the real activity, picks
 * a key the real way, recreates it, and asserts the choice survived.
 */
@RunWith(AndroidJUnit4::class)
class ProfileEditorActivityTest {

    /** Keys the key store held before this class ran, so the run removes
     *  every key it generated: a test must not leave credentials the user
     *  then sees in their picker. */
    private var keysBefore: Set<String> = emptySet()

    @Before
    fun recordExistingKeys() {
        keysBefore = SshServices.identityKeys(appContext()).list().map { it.alias }.toSet()
    }

    @After
    fun removeGeneratedKeys() {
        val store = SshServices.identityKeys(appContext())
        store.list()
            .map { it.alias }
            .filterNot { it in keysBefore }
            .forEach { store.delete(it) }
    }

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    private fun launch() = ActivityScenario.launch<ProfileEditorActivity>(
        android.content.Intent(appContext(), ProfileEditorActivity::class.java)
    )

    /** Waits until the editor's key label names a key rather than the empty case. */
    private fun awaitSelectedKey(scenario: ActivityScenario<ProfileEditorActivity>): String {
        val label = AtomicReference("")
        val deadline = System.currentTimeMillis() + 10_000
        val none = appContext().getString(R.string.profile_key_none)
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity {
                label.set((it.findViewById<TextView>(R.id.textSelectedKey).text ?: "").toString())
            }
            val current = label.get()
            if (current.isNotEmpty() && current != none) return current
            Thread.sleep(100)
        }
        return label.get()
    }

    @Test
    fun theSelectedKeySurvivesARecreation() {
        val scenario = launch()
        try {
            // Generate and select a key through the UI, as the user does.
            scenario.onActivity { it.findViewById<View>(R.id.btnGenerateKey).performClick() }
            val before = awaitSelectedKey(scenario)
            assertTrue(
                "a generated key must be reported as selected, got \"$before\"",
                before.isNotEmpty() && before != appContext().getString(R.string.profile_key_none)
            )

            // The recreation: rotation, or a vendor configuration change.
            scenario.recreate()

            val after = awaitSelectedKey(scenario)
            assertTrue(
                "the selected key must survive the recreation, got \"$after\"",
                after.isNotEmpty() && after != appContext().getString(R.string.profile_key_none)
            )
        } finally {
            scenario.close()
        }
    }
}
