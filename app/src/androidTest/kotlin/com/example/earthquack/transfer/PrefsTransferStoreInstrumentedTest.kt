package com.example.earthquack.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the transfer store.
 *
 * SharedPreferences-backed persistence is the whole promise of the transfer
 * manager — history and unfinished work survive the app "restarting" — and a
 * JVM test cannot reach it. What is verified here: a store recreated exactly
 * as the service would recreate it yields the same tasks, a RUNNING row is
 * honestly recovered as INTERRUPTED, and finished work is capped without
 * evicting the live one.
 */
@RunWith(AndroidJUnit4::class)
class PrefsTransferStoreInstrumentedTest {

    private companion object {
        const val PREFS = "earthquack_transfers"
        const val KEY_TASKS = "tasks"
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun task(id: String, status: TransferStatus, finishedAt: Long = 0L) = TransferTask(
        id = id,
        type = TransferType.DOWNLOAD,
        backend = TransferBackend.RcloneDownload(
            srcFs = "drive:",
            srcRemote = "docs/$id",
            dstFs = ":local:/cache",
            dstRemote = "$id",
            localFile = "/cache/opened/$id"
        ),
        displayName = id,
        sourceLabel = "drive:",
        destination = "/cache/opened/$id",
        totalBytes = 1000L,
        status = status,
        createdAt = 0L,
        finishedAt = finishedAt
    )

    @Test
    fun saveAndReloadAcrossInstances() {
        val store = PrefsTransferStore(context)
        store.save(
            listOf(
                task("running", TransferStatus.RUNNING).copy(bytesTransferred = 400L),
                task("done", TransferStatus.COMPLETED, finishedAt = 5000L)
            )
        )

        // A fresh store, as a new process's manager would build.
        val reloaded = PrefsTransferStore(context).load().associateBy { it.id }
        assertEquals(2, reloaded.size)
        // The honest recovery: nothing auto-claims a dead task was running.
        assertEquals(TransferStatus.INTERRUPTED, reloaded["running"]!!.status)
        assertEquals(400L, reloaded["running"]!!.bytesTransferred)
        assertEquals(TransferStatus.COMPLETED, reloaded["done"]!!.status)
    }

    @Test
    fun finishedHistoryIsCappedAndLiveWorkIsNotEvicted() {
        val store = PrefsTransferStore(context)
        store.save(
            listOf(task("live", TransferStatus.QUEUED)) +
                (1..60).map { task("done-$it", TransferStatus.COMPLETED, finishedAt = it.toLong()) }
        )
        val loaded = PrefsTransferStore(context).load()
        assertTrue("the live task must survive trimming", loaded.any { it.id == "live" })
        val terminal = loaded.filter { it.status.isTerminal }
        // The newest 50 survive; the oldest 10 go.
        assertEquals(50, terminal.size)
        assertFalse(terminal.any { it.id == "done-5" })
        assertTrue(terminal.any { it.id == "done-60" })
    }

    @Test
    fun emptyStoreLoadsEmpty() {
        assertTrue(PrefsTransferStore(context).load().isEmpty())
    }

    @Test
    fun aCorruptBlobLoadsAsEmptyRatherThanCrashing() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TASKS, "{{{").commit()
        assertTrue(PrefsTransferStore(context).load().isEmpty())
        // And a save recovers it.
        PrefsTransferStore(context).save(listOf(task("new", TransferStatus.QUEUED)))
        assertEquals(1, PrefsTransferStore(context).load().size)
    }

    @Test
    fun aStoredTaskKeepsItsErrorAndBackend() {
        PrefsTransferStore(context).save(
            listOf(
                task("failed", TransferStatus.FAILED, finishedAt = 9000L)
                    .copy(error = "network dropped", attempts = 2, bytesTransferred = 321L)
            )
        )
        val loaded = PrefsTransferStore(context).load().single()
        assertEquals("network dropped", loaded.error)
        assertEquals(2, loaded.attempts)
        assertEquals(321L, loaded.bytesTransferred)
        assertNotNull(loaded.backend as? TransferBackend.RcloneDownload)
        assertEquals("drive:", (loaded.backend as TransferBackend.RcloneDownload).srcFs)
    }
}
