package com.keihix.app.data.drive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingReceiptStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `saveLocalPending persists offline and cleans up on delete`() = runBlocking {
        val store = PendingReceiptStore(tempFolder.newFolder("pending_analysis"))
        val sampleBytes = byteArrayOf(1, 2, 3, 4, 5)

        val saved = store.saveLocalPending(
            year = 2026,
            bytes = sampleBytes,
            mimeType = "image/jpeg",
            extension = "jpg"
        )

        assertNull(saved.driveFileId)
        val listed = store.listLocalPending()
        assertEquals(1, listed.size)
        assertEquals(saved.localId, listed.first().localId)
        assertArrayEquals(sampleBytes, store.readBytes(saved))

        val synced = store.markSyncedToDrive(saved.localId, "drive_file_123")
        assertNotNull(synced)
        assertEquals("drive_file_123", store.getById(saved.localId)?.driveFileId)

        store.deleteLocalPending(saved.localId)
        assertTrue(store.listLocalPending().isEmpty())
    }
}
