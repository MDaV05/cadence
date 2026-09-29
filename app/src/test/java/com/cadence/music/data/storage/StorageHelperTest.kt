package com.cadence.music.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun calculateDirSizeReturnsZeroForEmptyOrMissingDir() {
        val emptyDir = tempFolder.newFolder("empty")
        assertEquals(0L, StorageHelper.calculateDirSize(emptyDir))

        val missingDir = File(tempFolder.root, "missing")
        assertEquals(0L, StorageHelper.calculateDirSize(missingDir))
    }

    @Test
    fun calculateDirSizeSumsNestedFiles() {
        val root = tempFolder.newFolder("storage")
        val file1 = File(root, "file1.bin").apply { writeBytes(ByteArray(1024)) }
        val subDir = File(root, "sub").apply { mkdir() }
        val file2 = File(subDir, "file2.bin").apply { writeBytes(ByteArray(2048)) }

        assertEquals(3072L, StorageHelper.calculateDirSize(root))
    }

    @Test
    fun cleanCacheOlderThanRemovesOldFilesAndKeepsNewFiles() {
        val root = tempFolder.newFolder("cache")
        val now = System.currentTimeMillis()
        val oldFile = File(root, "old.mp3").apply {
            writeBytes(ByteArray(1000))
            setLastModified(now - 100_000)
        }
        val newFile = File(root, "new.mp3").apply {
            writeBytes(ByteArray(500))
            setLastModified(now)
        }

        val freed = StorageHelper.cleanCacheOlderThan(root, cutoffMs = now - 50_000)

        assertEquals(1000L, freed)
        assertFalse("Old file should be deleted", oldFile.exists())
        assertTrue("New file should still exist", newFile.exists())
    }

    @Test
    fun formatStorageSizeFractions() {
        assertEquals("0 B", StorageHelper.formatStorageSize(0L))
        assertEquals("1023 B", StorageHelper.formatStorageSize(1023L))
        assertEquals("1.0 KB", StorageHelper.formatStorageSize(1024L))
        assertEquals("1.5 MB", StorageHelper.formatStorageSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("8.0 GB", StorageHelper.formatStorageSize(8L * 1024 * 1024 * 1024))
    }

    @Test
    fun storageBreakdownSumsTotalCorrectly() {
        val breakdown = StorageHelper.StorageBreakdown(
            downloadsBytes = 1000L,
            streamCacheBytes = 2000L,
            telegramBytes = 3000L,
            imageCacheBytes = 4000L,
        )
        assertEquals(10000L, breakdown.totalBytes)
    }
}
