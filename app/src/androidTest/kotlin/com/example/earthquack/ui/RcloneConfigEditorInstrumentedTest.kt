package com.example.earthquack.ui

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.earthquack.R
import com.example.earthquack.storage.RcloneConfigManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The raw config editor's load path, on a device.
 *
 * What is verified here is the part the UI automation cannot see: that the
 * editor actually puts the config's bytes into the field. An empty editor
 * that silently saves nothing is the failure this guards against, and it is
 * invisible to uiautomator (an unfilled EditText and a filled one dump
 * alike when the dump races the load).
 */
@RunWith(AndroidJUnit4::class)
class RcloneConfigEditorInstrumentedTest {

    /** The user's real config, preserved like the rclone suite does. */
    private var savedConfig: ByteArray? = null
    private var existed = false

    private fun configFile(): File = RcloneConfigManager(appContext()).configFile

    private fun appContext() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun theEditorLoadsTheRealConfigIntoTheField() {
        val file = configFile()
        existed = file.exists()
        savedConfig = if (existed) file.readBytes() else null
        try {
            val expected = savedConfig?.toString(Charsets.UTF_8).orEmpty()
            val scenario = ActivityScenario.launch<RcloneConfigEditorActivity>(
                android.content.Intent(appContext(), RcloneConfigEditorActivity::class.java)
            )
            try {
                val loaded = StringBuilder()
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    scenario.onActivity {
                        loaded.setLength(0)
                        loaded.append(it.findViewById<EditText>(R.id.editConfig).text?.toString().orEmpty())
                    }
                    if (loaded.toString() == expected) break
                    Thread.sleep(50)
                }
                assertEquals("the editor must show the config exactly as stored", expected, loaded.toString())
                assertTrue("a real config must be non-empty", loaded.isNotEmpty())
            } finally {
                scenario.close()
            }
        } finally {
            // Put the user's config back exactly as it was.
            if (savedConfig != null) {
                file.writeBytes(savedConfig!!)
                RcloneConfigManager(appContext()).repairPermissions(file)
            } else if (existed) {
                file.delete()
            }
        }
    }
}
