package com.raaru.orekingdom.channel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** File-backed outbox persistence pins (DeepSeek full-spend programme Q-19). */
class FileOutboxPersistenceTest {

    private fun tempFile(): File =
        File(java.nio.file.Files.createTempDirectory("outbox-test").toFile(), "outbox.json")

    @Test
    fun readWithNoFileReturnsNull() {
        val persistence = FileOutboxPersistence(tempFile())
        assertNull(persistence.read())
    }

    @Test
    fun writeThenReadReturnsExactString() {
        val persistence = FileOutboxPersistence(tempFile())
        persistence.write("{\"queue\":[]}")
        assertEquals("{\"queue\":[]}", persistence.read())
    }

    @Test
    fun secondWriteReplacesFirst() {
        val persistence = FileOutboxPersistence(tempFile())
        persistence.write("one")
        persistence.write("two")
        assertEquals("two", persistence.read())
    }

    @Test
    fun successfulWriteLeavesNoTmpSibling() {
        val file = tempFile()
        val persistence = FileOutboxPersistence(file)
        persistence.write("x")
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun garbageContentsReadBackVerbatim() {
        val file = tempFile()
        file.writeText("not json {{{")
        assertEquals("not json {{{", FileOutboxPersistence(file).read())
    }
}
