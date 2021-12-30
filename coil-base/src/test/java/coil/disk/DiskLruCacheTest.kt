/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package coil.disk

import coil.disk.DiskLruCache.Editor
import coil.disk.DiskLruCache.Snapshot
import coil.util.assumeFalse
import coil.util.assumeTrue
import okio.FileNotFoundException
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.fail

/** https://github.com/square/okhttp/blob/master/okhttp/src/jvmTest/java/okhttp3/internal/cache/DiskLruCacheTest.kt */
class DiskLruCacheTest {

    private lateinit var fileSystem: FaultyFileSystem
    private lateinit var dispatcher: SimpleTestDispatcher
    private lateinit var caches: MutableSet<DiskLruCache>
    private lateinit var cache: DiskLruCache

    private val cacheDir = "/cache".toPath()
    private val appVersion = 100
    private val journalFile = cacheDir / DiskLruCache.JOURNAL_FILE
    private val journalFileBackup = cacheDir / DiskLruCache.JOURNAL_FILE_BACKUP
    private val windows = true

    @Before
    fun before() {
        fileSystem = FaultyFileSystem(FakeFileSystem().apply { emulateUnix() })
        if (fileSystem.exists(cacheDir)) {
            fileSystem.deleteRecursively(cacheDir)
        }
        dispatcher = SimpleTestDispatcher()
        caches = mutableSetOf()
        createNewCache()
    }

    @After
    fun after() {
        caches.forEach { it.close() }
        (fileSystem.delegate as FakeFileSystem).checkNoOpenFiles()
    }

    private fun createNewCache(maxSize: Long = Long.MAX_VALUE) {
        cache = DiskLruCache(fileSystem, cacheDir, dispatcher, maxSize, appVersion, 2)
        cache.initialize()
        caches += cache
    }

    @Test
    fun emptyCache() {
        cache.close()
        assertJournalEquals()
    }

    @Test
    fun recoverFromInitializationFailure() {
        // Add an uncommitted entry. This will get detected on initialization, and the cache will
        // attempt to delete the file. Do not explicitly close the cache here so the entry is left
        // as incomplete.
        val creator = cache.edit("k1")!!
        creator.newSink(0).buffer().use {
            it.writeUtf8("Hello")
        }

        // Simulate a severe Filesystem failure on the first initialization.
        fileSystem.setFaultyDelete(cacheDir / "k1.0.tmp", true)
        fileSystem.setFaultyDelete(cacheDir, true)
        cache = DiskLruCache(fileSystem, cacheDir, dispatcher, Long.MAX_VALUE, appVersion, 2)
        caches += cache
        try {
            cache["k1"]
            fail("")
        } catch (_: IOException) {
        }

        // Now let it operate normally.
        fileSystem.setFaultyDelete(cacheDir / "k1.0.tmp", false)
        fileSystem.setFaultyDelete(cacheDir, false)
        val snapshot = cache["k1"]
        assertThat(snapshot).isNull()
    }

    @Test
    fun validateKey() {
        var key: String? = null
        try {
            key = "has_space "
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }
        try {
            key = "has_CR\r"
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }
        try {
            key = "has_LF\n"
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }
        try {
            key = "has_invalid/"
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }
        try {
            key = "has_invalid\u2603"
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }
        try {
            key = ("this_is_way_too_long_this_is_way_too_long_this_is_way_too_long_" +
                "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long")
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was too long.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }

        // Test valid cases.

        // Exactly 120.
        key = ("0123456789012345678901234567890123456789012345678901234567890123456789" +
            "01234567890123456789012345678901234567890123456789")
        cache.edit(key)!!.abort()
        // Contains all valid characters.
        key = "abcdefghijklmnopqrstuvwxyz_0123456789"
        cache.edit(key)!!.abort()
        // Contains dash.
        key = "-20384573948576"
        cache.edit(key)!!.abort()
    }

    @Test
    fun writeAndReadEntry() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "ABC")
        creator.setString(1, "DE")
        assertThat(creator.newSource(0)).isNull()
        assertThat(creator.newSource(1)).isNull()
        creator.commit()
        val snapshot = cache["k1"]!!
        snapshot.assertValue(0, "ABC")
        snapshot.assertValue(1, "DE")
    }

    @Test
    fun readAndWriteEntryAcrossCacheOpenAndClose() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "A")
        creator.setString(1, "B")
        creator.commit()
        cache.close()
        createNewCache()
        val snapshot = cache["k1"]!!
        snapshot.assertValue(0, "A")
        snapshot.assertValue(1, "B")
        snapshot.close()
    }

    @Test
    fun readAndWriteEntryWithoutProperClose() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "A")
        creator.setString(1, "B")
        creator.commit()

        // Simulate a dirty close of 'cache' by opening the cache directory again.
        createNewCache()
        cache["k1"]!!.use {
            it.assertValue(0, "A")
            it.assertValue(1, "B")
        }
    }

    @Test
    fun journalWithEditAndPublish() {
        val creator = cache.edit("k1")!!
        assertJournalEquals("DIRTY k1") // DIRTY must always be flushed.
        creator.setString(0, "AB")
        creator.setString(1, "C")
        creator.commit()
        cache.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 2 1")
    }

    @Test
    fun revertedNewFileIsRemoveInJournal() {
        val creator = cache.edit("k1")!!
        assertJournalEquals("DIRTY k1") // DIRTY must always be flushed.
        creator.setString(0, "AB")
        creator.setString(1, "C")
        creator.abort()
        cache.close()
        assertJournalEquals("DIRTY k1", "REMOVE k1")
    }

    /** On Windows we have to wait until the edit is committed before we can delete its files. */
    @Test
    fun `unterminated edit is reverted on cache close`() {
        val editor = cache.edit("k1")!!
        editor.setString(0, "AB")
        editor.setString(1, "C")
        cache.close()
        val expected = if (windows) arrayOf("DIRTY k1") else arrayOf("DIRTY k1", "REMOVE k1")
        assertJournalEquals(*expected)
        editor.commit()
        assertJournalEquals(*expected) // 'REMOVE k1' not written because journal is closed.
    }

    @Test
    fun journalDoesNotIncludeReadOfYetUnpublishedValue() {
        val creator = cache.edit("k1")!!
        assertThat(cache["k1"]).isNull()
        creator.setString(0, "A")
        creator.setString(1, "BC")
        creator.commit()
        cache.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 1 2")
    }

    @Test
    fun journalWithEditAndPublishAndRead() {
        val k1Creator = cache.edit("k1")!!
        k1Creator.setString(0, "AB")
        k1Creator.setString(1, "C")
        k1Creator.commit()
        val k2Creator = cache.edit("k2")!!
        k2Creator.setString(0, "DEF")
        k2Creator.setString(1, "G")
        k2Creator.commit()
        val k1Snapshot = cache["k1"]!!
        k1Snapshot.close()
        cache.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 2 1", "DIRTY k2", "CLEAN k2 3 1", "READ k1")
    }

    @Test
    fun cannotOperateOnEditAfterPublish() {
        val editor = cache.edit("k1")!!
        editor.setString(0, "A")
        editor.setString(1, "B")
        editor.commit()
        editor.assertInoperable()
    }

    @Test
    fun cannotOperateOnEditAfterRevert() {
        val editor = cache.edit("k1")!!
        editor.setString(0, "A")
        editor.setString(1, "B")
        editor.abort()
        editor.assertInoperable()
    }

    @Test
    fun explicitRemoveAppliedToDiskImmediately() {
        val editor = cache.edit("k1")!!
        editor.setString(0, "ABC")
        editor.setString(1, "B")
        editor.commit()
        val k1 = getCleanFile("k1", 0)
        assertThat(readFile(k1)).isEqualTo("ABC")
        cache.remove("k1")
        assertThat(fileSystem.exists(k1)).isFalse
    }

    @Test
    fun removePreventsActiveEditFromStoringAValue() {
        set("a", "a", "a")
        val a = cache.edit("a")!!
        a.setString(0, "a1")
        assertThat(cache.remove("a")).isTrue
        a.setString(1, "a2")
        a.commit()
        assertAbsent("a")
    }

    @Test
    fun openWithDirtyKeyDeletesAllFilesForThatKey() {
        cache.close()
        val cleanFile0 = getCleanFile("k1", 0)
        val cleanFile1 = getCleanFile("k1", 1)
        val dirtyFile0 = getDirtyFile("k1", 0)
        val dirtyFile1 = getDirtyFile("k1", 1)
        writeFile(cleanFile0, "A")
        writeFile(cleanFile1, "B")
        writeFile(dirtyFile0, "C")
        writeFile(dirtyFile1, "D")
        createJournal("CLEAN k1 1 1", "DIRTY k1")
        createNewCache()
        assertThat(fileSystem.exists(cleanFile0)).isFalse
        assertThat(fileSystem.exists(cleanFile1)).isFalse
        assertThat(fileSystem.exists(dirtyFile0)).isFalse
        assertThat(fileSystem.exists(dirtyFile1)).isFalse
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun openWithInvalidVersionClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "0", "100", "2", "")
        createNewCache()
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidAppVersionClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "101", "2", "")
        createNewCache()
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidValueCountClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "100", "1", "")
        createNewCache()
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidBlankLineClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "100", "2", "x")
        createNewCache()
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidJournalLineClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 1 1", "BOGUS")
        createNewCache()
        assertGarbageFilesAllDeleted()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun openWithInvalidFileSizeClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 0000x001 1")
        createNewCache()
        assertGarbageFilesAllDeleted()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun openWithTruncatedLineDiscardsThatLine() {
        cache.close()
        writeFile(getCleanFile("k1", 0), "A")
        writeFile(getCleanFile("k1", 1), "B")
        fileSystem.write(journalFile) {
            writeUtf8(
                """
                |${DiskLruCache.MAGIC}
                |${DiskLruCache.VERSION}
                |100
                |2
                |
                |CLEAN k1 1 1""".trimMargin() // no trailing newline
            )
        }
        createNewCache()
        assertThat(cache["k1"]).isNull()

        // The journal is not corrupt when editing after a truncated line.
        set("k1", "C", "D")
        cache.close()
        createNewCache()
        assertValue("k1", "C", "D")
    }

    @Test
    fun openWithTooManyFileSizesClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 1 1 1")
        createNewCache()
        assertGarbageFilesAllDeleted()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun keyWithSpaceNotPermitted() {
        try {
            cache.edit("my key")
            fail("")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun keyWithNewlineNotPermitted() {
        try {
            cache.edit("my\nkey")
            fail("")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun keyWithCarriageReturnNotPermitted() {
        try {
            cache.edit("my\rkey")
            fail("")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun createNewEntryWithTooFewValuesFails() {
        val creator = cache.edit("k1")!!
        creator.setString(1, "A")
        try {
            creator.commit()
            fail("")
        } catch (_: IllegalStateException) {
        }
        assertThat(fileSystem.exists(getCleanFile("k1", 0))).isFalse
        assertThat(fileSystem.exists(getCleanFile("k1", 1))).isFalse
        assertThat(fileSystem.exists(getDirtyFile("k1", 0))).isFalse
        assertThat(fileSystem.exists(getDirtyFile("k1", 1))).isFalse
        assertThat(cache["k1"]).isNull()
        val creator2 = cache.edit("k1")!!
        creator2.setString(0, "B")
        creator2.setString(1, "C")
        creator2.commit()
    }

    @Test
    fun revertWithTooFewValues() {
        val creator = cache.edit("k1")!!
        creator.setString(1, "A")
        creator.abort()
        assertThat(fileSystem.exists(getCleanFile("k1", 0))).isFalse
        assertThat(fileSystem.exists(getCleanFile("k1", 1))).isFalse
        assertThat(fileSystem.exists(getDirtyFile("k1", 0))).isFalse
        assertThat(fileSystem.exists(getDirtyFile("k1", 1))).isFalse
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun updateExistingEntryWithTooFewValuesReusesPreviousValues() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "A")
        creator.setString(1, "B")
        creator.commit()
        val updater = cache.edit("k1")!!
        updater.setString(0, "C")
        updater.commit()
        val snapshot = cache["k1"]!!
        snapshot.assertValue(0, "C")
        snapshot.assertValue(1, "B")
        snapshot.close()
    }

    @Test
    fun evictOnInsert() {
        cache.close()
        createNewCache(10)
        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        assertThat(cache.size()).isEqualTo(10)

        // Cause the size to grow to 12 should evict 'A'.
        set("c", "c", "c")
        cache.flush()
        assertThat(cache.size()).isEqualTo(8)
        assertAbsent("a")
        assertValue("b", "bb", "bbbb")
        assertValue("c", "c", "c")

        // Causing the size to grow to 10 should evict nothing.
        set("d", "d", "d")
        cache.flush()
        assertThat(cache.size()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "bb", "bbbb")
        assertValue("c", "c", "c")
        assertValue("d", "d", "d")

        // Causing the size to grow to 18 should evict 'B' and 'C'.
        set("e", "eeee", "eeee")
        cache.flush()
        assertThat(cache.size()).isEqualTo(10)
        assertAbsent("a")
        assertAbsent("b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "eeee", "eeee")
    }

    @Test
    fun evictOnUpdate() {
        cache.close()
        createNewCache(10)
        set("a", "a", "aa") // size 3
        set("b", "b", "bb") // size 3
        set("c", "c", "cc") // size 3
        assertThat(cache.size()).isEqualTo(9)

        // Causing the size to grow to 11 should evict 'A'.
        set("b", "b", "bbbb")
        cache.flush()
        assertThat(cache.size()).isEqualTo(8)
        assertAbsent("a")
        assertValue("b", "b", "bbbb")
        assertValue("c", "c", "cc")
    }

    @Test
    fun evictionHonorsLruFromCurrentSession() {
        cache.close()
        createNewCache(10)
        set("a", "a", "a")
        set("b", "b", "b")
        set("c", "c", "c")
        set("d", "d", "d")
        set("e", "e", "e")
        cache["b"]!!.close() // 'B' is now least recently used.

        // Causing the size to grow to 12 should evict 'A'.
        set("f", "f", "f")
        // Causing the size to grow to 12 should evict 'C'.
        set("g", "g", "g")
        cache.flush()
        assertThat(cache.size()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "e", "e")
        assertValue("f", "f", "f")
    }

    @Test
    fun evictionHonorsLruFromPreviousSession() {
        set("a", "a", "a")
        set("b", "b", "b")
        set("c", "c", "c")
        set("d", "d", "d")
        set("e", "e", "e")
        set("f", "f", "f")
        cache["b"]!!.close() // 'B' is now least recently used.
        assertThat(cache.size()).isEqualTo(12)
        cache.close()
        createNewCache(10)
        set("g", "g", "g")
        cache.flush()
        assertThat(cache.size()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "e", "e")
        assertValue("f", "f", "f")
        assertValue("g", "g", "g")
    }

    @Test
    fun cacheSingleEntryOfSizeGreaterThanMaxSize() {
        cache.close()
        createNewCache(10)
        set("a", "aaaaa", "aaaaaa") // size=11
        cache.flush()
        assertAbsent("a")
    }

    @Test
    fun cacheSingleValueOfSizeGreaterThanMaxSize() {
        cache.close()
        createNewCache(10)
        set("a", "aaaaaaaaaaa", "a") // size=12
        cache.flush()
        assertAbsent("a")
    }

    @Test
    fun constructorDoesNotAllowZeroCacheSize() {
        try {
            caches += DiskLruCache(fileSystem, cacheDir, dispatcher, 0, appVersion, 2)
            fail("")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun constructorDoesNotAllowZeroValuesPerEntry() {
        try {
            caches += DiskLruCache(fileSystem, cacheDir, dispatcher, 10, appVersion, 0)
            fail("")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun removeAbsentElement() {
        assertFalse(cache.remove("a"))
    }

    @Test
    fun readingTheSameStreamMultipleTimes() {
        set("a", "a", "b")
        val snapshot = cache["a"]!!
        assertThat(snapshot.getSource(0)).isSameAs(snapshot.getSource(0))
        snapshot.close()
    }

    @Test
    fun rebuildJournalOnRepeatedReads() {
        set("a", "a", "a")
        set("b", "b", "b")
        while (dispatcher.isIdle()) {
            assertValue("a", "a", "a")
            assertValue("b", "b", "b")
        }
    }

    @Test
    fun rebuildJournalOnRepeatedEdits() {
        while (dispatcher.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
        }
        dispatcher.runNextTask()

        // Sanity check that a rebuilt journal behaves normally.
        assertValue("a", "a", "a")
        assertValue("b", "b", "b")
    }

    /** https://github.com/JakeWharton/DiskLruCache/issues/28 */
    @Test
    fun rebuildJournalOnRepeatedReadsWithOpenAndClose() {
        set("a", "a", "a")
        set("b", "b", "b")
        while (dispatcher.isIdle()) {
            assertValue("a", "a", "a")
            assertValue("b", "b", "b")
            cache.close()
            createNewCache()
        }
    }

    /** https://github.com/JakeWharton/DiskLruCache/issues/28 */
    @Test
    fun rebuildJournalOnRepeatedEditsWithOpenAndClose() {
        while (dispatcher.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
            cache.close()
            createNewCache()
        }
    }

    @Test
    fun rebuildJournalFailurePreventsEditors() {
        while (dispatcher.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
        }

        // Cause the rebuild action to fail.
        fileSystem.setFaultyRename(journalFileBackup, true)
        dispatcher.runNextTask()

        // Don't allow edits under any circumstances.
        assertThat(cache.edit("a")).isNull()
        assertThat(cache.edit("c")).isNull()
        cache["a"]!!.use {
            assertThat(cache.edit(it.entry.key)).isNull()
        }
    }

    @Test
    fun rebuildJournalFailureIsRetried() {
        while (dispatcher.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
        }

        // Cause the rebuild action to fail.
        fileSystem.setFaultyRename(journalFileBackup, true)
        dispatcher.runNextTask()

        // The rebuild is retried on cache hits and on cache edits.
        val snapshot = cache["b"]!!
        snapshot.close()
        assertThat(cache.edit("d")).isNull()
        assertThat(dispatcher.isIdle()).isFalse

        // On cache misses, no retry job is queued.
        assertThat(cache["c"]).isNull()
        assertThat(dispatcher.isIdle()).isFalse

        // Let the rebuild complete successfully.
        fileSystem.setFaultyRename(journalFileBackup, false)
        dispatcher.runNextTask()
        assertJournalEquals("CLEAN a 1 1", "CLEAN b 1 1")
    }

    @Test
    fun rebuildJournalFailureWithInFlightEditors() {
        while (dispatcher.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
        }
        val commitEditor = cache.edit("c")!!
        val abortEditor = cache.edit("d")!!
        cache.edit("e") // Grab an editor, but don't do anything with it.

        // Cause the rebuild action to fail.
        fileSystem.setFaultyRename(journalFileBackup, true)
        dispatcher.runNextTask()

        // In-flight editors can commit and have their values retained.
        commitEditor.setString(0, "c")
        commitEditor.setString(1, "c")
        commitEditor.commit()
        assertValue("c", "c", "c")
        abortEditor.abort()

        // Let the rebuild complete successfully.
        fileSystem.setFaultyRename(journalFileBackup, false)
        dispatcher.runNextTask()
        assertJournalEquals("CLEAN a 1 1", "CLEAN b 1 1", "DIRTY e", "CLEAN c 1 1")
    }

    @Test
    fun rebuildJournalFailureWithEditorsInFlightThenClose() {
        while (dispatcher.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
        }
        val commitEditor = cache.edit("c")!!
        val abortEditor = cache.edit("d")!!
        cache.edit("e") // Grab an editor, but don't do anything with it.

        // Cause the rebuild action to fail.
        fileSystem.setFaultyRename(journalFileBackup, true)
        dispatcher.runNextTask()
        commitEditor.setString(0, "c")
        commitEditor.setString(1, "c")
        commitEditor.commit()
        assertValue("c", "c", "c")
        abortEditor.abort()
        cache.close()
        createNewCache()

        // Although 'c' successfully committed above, the journal wasn't available to issue a CLEAN op.
        // Because the last state of 'c' was DIRTY before the journal failed, it should be removed
        // entirely on a subsequent open.
        assertThat(cache.size()).isEqualTo(4)
        assertAbsent("c")
        assertAbsent("d")
        assertAbsent("e")
    }

    @Test
    fun rebuildJournalFailureAllowsRemovals() {
        while (dispatcher.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
        }

        // Cause the rebuild action to fail.
        fileSystem.setFaultyRename(journalFileBackup, true)
        dispatcher.runNextTask()
        assertThat(cache.remove("a")).isTrue
        assertAbsent("a")

        // Let the rebuild complete successfully.
        fileSystem.setFaultyRename(journalFileBackup, false)
        dispatcher.runNextTask()
        assertJournalEquals("CLEAN b 1 1")
    }

    @Test
    fun rebuildJournalFailureWithRemovalThenClose() {
        while (dispatcher.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
        }

        // Cause the rebuild action to fail.
        fileSystem.setFaultyRename(journalFileBackup, true)
        dispatcher.runNextTask()
        assertThat(cache.remove("a")).isTrue
        assertAbsent("a")
        cache.close()
        createNewCache()

        // The journal will have no record that 'a' was removed. It will have an entry for 'a', but when
        // it tries to read the cache files, it will find they were deleted. Once it encounters an entry
        // with missing cache files, it should remove it from the cache entirely.
        assertThat(cache.size()).isEqualTo(4)
        assertThat(cache["a"]).isNull()
        assertThat(cache.size()).isEqualTo(2)
    }

    @Test
    fun rebuildJournalFailureAllowsEvictAll() {
        while (dispatcher.isIdle()) {
            set("a", "a", "a")
            set("b", "b", "b")
        }

        // Cause the rebuild action to fail.
        fileSystem.setFaultyRename(journalFileBackup, true)
        dispatcher.runNextTask()
        cache.evictAll()
        assertThat(cache.size()).isEqualTo(0)
        assertAbsent("a")
        assertAbsent("b")
        cache.close()
        createNewCache()

        // The journal has no record that 'a' and 'b' were removed. It will have an entry for both, but
        // when it tries to read the cache files for either entry, it will discover the cache files are
        // missing and remove the entries from the cache.
        assertThat(cache.size()).isEqualTo(4)
        assertThat(cache["a"]).isNull()
        assertThat(cache["b"]).isNull()
        assertThat(cache.size()).isEqualTo(0)
    }

    @Test
    fun rebuildJournalFailureWithCacheTrim() {
        while (dispatcher.isIdle()) {
            set("a", "aa", "aa")
            set("b", "bb", "bb")
        }

        // Cause the rebuild action to fail.
        fileSystem.setFaultyRename(journalFileBackup, true)
        dispatcher.runNextTask()

        // Trigger a job to trim the cache.
        cache.maxSize = 4
        dispatcher.runNextTask()
        assertAbsent("a")
        assertValue("b", "bb", "bb")
    }

    @Test
    fun restoreBackupFile() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "ABC")
        creator.setString(1, "DE")
        creator.commit()
        cache.close()
        fileSystem.atomicMove(journalFile, journalFileBackup)
        assertThat(fileSystem.exists(journalFile)).isFalse
        createNewCache()
        val snapshot = cache["k1"]!!
        snapshot.assertValue(0, "ABC")
        snapshot.assertValue(1, "DE")
        assertThat(fileSystem.exists(journalFileBackup)).isFalse
        assertThat(fileSystem.exists(journalFile)).isTrue
    }

    @Test
    fun journalFileIsPreferredOverBackupFile() {
        var creator = cache.edit("k1")!!
        creator.setString(0, "ABC")
        creator.setString(1, "DE")
        creator.commit()
        cache.flush()
        fileSystem.copy(journalFile, journalFileBackup)
        creator = cache.edit("k2")!!
        creator.setString(0, "F")
        creator.setString(1, "GH")
        creator.commit()
        cache.close()
        assertThat(fileSystem.exists(journalFile)).isTrue
        assertThat(fileSystem.exists(journalFileBackup)).isTrue
        createNewCache()
        val snapshotA = cache["k1"]!!
        snapshotA.assertValue(0, "ABC")
        snapshotA.assertValue(1, "DE")
        val snapshotB = cache["k2"]!!
        snapshotB.assertValue(0, "F")
        snapshotB.assertValue(1, "GH")
        assertThat(fileSystem.exists(journalFileBackup)).isFalse
        assertThat(fileSystem.exists(journalFile)).isTrue
    }

    @Test
    fun openCreatesDirectoryIfNecessary() {
        cache.close()
        val dir = (cacheDir / "testOpenCreatesDirectoryIfNecessary").also { fileSystem.createDirectories(it) }
        cache = DiskLruCache(fileSystem, dir, dispatcher, Long.MAX_VALUE, appVersion, 2)
        caches += cache
        set("a", "a", "a")
        assertThat(fileSystem.exists(dir / "a.0")).isTrue
        assertThat(fileSystem.exists(dir / "a.1")).isTrue
        assertThat(fileSystem.exists(dir / "journal")).isTrue
    }

    @Test
    fun fileDeletedExternally() {
        set("a", "a", "a")
        fileSystem.delete(getCleanFile("a", 1))
        assertThat(cache["a"]).isNull()
        assertThat(cache.size()).isEqualTo(0)
    }

    @Test
    fun editSameVersion() {
        set("a", "a", "a")
        val snapshot = cache["a"]!!
        snapshot.close()
        val editor = cache.edit(snapshot.entry.key)!!
        editor.setString(1, "a2")
        editor.commit()
        assertValue("a", "a", "a2")
    }

    @Test
    fun editSnapshotAfterChangeAborted() {
        set("a", "a", "a")
        val snapshot = cache["a"]!!
        snapshot.close()
        val toAbort = cache.edit(snapshot.entry.key)!!
        toAbort.setString(0, "b")
        toAbort.abort()
        val editor = cache.edit(snapshot.entry.key)!!
        editor.setString(1, "a2")
        editor.commit()
        assertValue("a", "a", "a2")
    }

    @Test
    fun editSnapshotAfterChangeCommitted() {
        set("a", "a", "a")
        val snapshot = cache["a"]!!
        snapshot.close()
        val toAbort = cache.edit(snapshot.entry.key)!!
        toAbort.setString(0, "b")
        toAbort.commit()
        assertThat(cache.edit(snapshot.entry.key)).isNull()
    }

    @Test
    fun editSinceEvicted() {
        cache.close()
        createNewCache(10)
        set("a", "aa", "aaa") // size 5
        val snapshot = cache["a"]!!
        set("b", "bb", "bbb") // size 5
        set("c", "cc", "ccc") // size 5; will evict 'A'
        cache.flush()
        assertThat(cache.edit(snapshot.entry.key)).isNull()
    }

    @Test
    fun editSinceEvictedAndRecreated() {
        cache.close()
        createNewCache(10)
        set("a", "aa", "aaa") // size 5
        val snapshot = cache["a"]!!
        snapshot.close()
        set("b", "bb", "bbb") // size 5
        set("c", "cc", "ccc") // size 5; will evict 'A'
        set("a", "a", "aaaa") // size 5; will evict 'B'
        cache.flush()
        assertThat(cache.edit(snapshot.entry.key)).isNull()
    }

    @Test
    fun removeHandlesMissingFile() {
        set("a", "a", "a")
        fileSystem.delete(getCleanFile("a", 0))
        cache.remove("a")
    }

    /** https://github.com/JakeWharton/DiskLruCache/issues/2 */
    @Test
    fun aggressiveClearingHandlesPartialEdit() {
        assumeFalse(windows) // Can't deleteContents while the journal is open.

        set("a", "a", "a")
        set("b", "b", "b")
        val a = cache.edit("a")!!
        a.setString(0, "a1")
        fileSystem.deleteRecursively(cacheDir)
        a.setString(1, "a2")
        a.commit()
        assertThat(cache["a"]).isNull()
    }

    /**
     * We had a long-lived bug where [DiskLruCache.trimToSize] could infinite loop if entries
     * being edited required deletion for the operation to complete.
     */
    @Test
    fun trimToSizeWithActiveEdit() {
        val expectedByteCount = if (windows) 10L else 0L
        val afterRemoveFileContents = if (windows) "a1234" else null

        set("a", "a1234", "a1234")
        val a = cache.edit("a")!!
        a.setString(0, "a123")
        cache.maxSize = 8 // Smaller than the sum of active edits!
        cache.flush() // Force trimToSize().
        assertThat(cache.size()).isEqualTo(expectedByteCount)
        assertThat(readFileOrNull(getCleanFile("a", 0))).isEqualTo(afterRemoveFileContents)
        assertThat(readFileOrNull(getCleanFile("a", 1))).isEqualTo(afterRemoveFileContents)

        // After the edit is completed, its entry is still gone.
        a.setString(1, "a1")
        a.commit()
        assertAbsent("a")
        assertThat(cache.size()).isEqualTo(0)
    }

    @Test
    fun evictAll() {
        set("a", "a", "a")
        set("b", "b", "b")
        cache.evictAll()
        assertThat(cache.size()).isEqualTo(0)
        assertAbsent("a")
        assertAbsent("b")
    }

    @Test
    fun evictAllWithPartialCreate() {
        val a = cache.edit("a")!!
        a.setString(0, "a1")
        a.setString(1, "a2")
        cache.evictAll()
        assertThat(cache.size()).isEqualTo(0)
        a.commit()
        assertAbsent("a")
    }

    @Test
    fun evictAllWithPartialEditDoesNotStoreAValue() {
        val expectedByteCount = if (windows) 2L else 0L

        set("a", "a", "a")
        val a = cache.edit("a")!!
        a.setString(0, "a1")
        a.setString(1, "a2")
        cache.evictAll()
        assertThat(cache.size()).isEqualTo(expectedByteCount)
        a.commit()
        assertAbsent("a")
    }

    @Test
    fun evictAllDoesntInterruptPartialRead() {
        val expectedByteCount = if (windows) 2L else 0L
        val afterRemoveFileContents = if (windows) "a" else null

        set("a", "a", "a")
        cache["a"]!!.use {
            it.assertValue(0, "a")
            cache.evictAll()
            assertThat(cache.size()).isEqualTo(expectedByteCount)
            assertThat(readFileOrNull(getCleanFile("a", 0))).isEqualTo(afterRemoveFileContents)
            assertThat(readFileOrNull(getCleanFile("a", 1))).isEqualTo(afterRemoveFileContents)
            it.assertValue(1, "a")
        }
        assertThat(cache.size()).isEqualTo(0L)
    }

    @Test
    fun editSnapshotAfterEvictAllReturnsNullDueToStaleValue() {
        val expectedByteCount = if (windows) 2L else 0L
        val afterRemoveFileContents = if (windows) "a" else null

        set("a", "a", "a")
        cache["a"]!!.use { snapshot ->
            cache.evictAll()
            assertThat(cache.size()).isEqualTo(expectedByteCount)
            assertThat(readFileOrNull(getCleanFile("a", 0))).isEqualTo(afterRemoveFileContents)
            assertThat(readFileOrNull(getCleanFile("a", 1))).isEqualTo(afterRemoveFileContents)
            assertThat(cache.edit(snapshot.entry.key)).isNull()
        }
        assertThat(cache.size()).isEqualTo(0L)
    }

    @Test
    fun isClosed_uninitializedCache() {
        // Create an uninitialized cache.
        cache = DiskLruCache(fileSystem, cacheDir, dispatcher, Long.MAX_VALUE, appVersion, 2)
        caches += cache
        assertThat(cache.isClosed()).isFalse
        cache.close()
        assertThat(cache.isClosed()).isTrue
    }

    @Test
    fun journalWriteFailsDuringEdit() {
        set("a", "a", "a")
        set("b", "b", "b")

        // We can't begin the edit if writing 'DIRTY' fails.
        fileSystem.setFaultyWrite(journalFile, true)
        assertThat(cache.edit("c")).isNull()

        // Once the journal has a failure, subsequent writes aren't permitted.
        fileSystem.setFaultyWrite(journalFile, false)
        assertThat(cache.edit("d")).isNull()

        // Confirm that the fault didn't corrupt entries stored before the fault was introduced.
        cache.close()
        cache = DiskLruCache(fileSystem, cacheDir, dispatcher, Long.MAX_VALUE, appVersion, 2)
        caches += cache
        assertValue("a", "a", "a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertAbsent("d")
    }

    /**
     * We had a bug where the cache was left in an inconsistent state after a journal write failed.
     * https://github.com/square/okhttp/issues/1211
     */
    @Test
    fun journalWriteFailsDuringEditorCommit() {
        set("a", "a", "a")
        set("b", "b", "b")

        // Create an entry that fails to write to the journal during commit.
        val editor = cache.edit("c")!!
        editor.setString(0, "c")
        editor.setString(1, "c")
        fileSystem.setFaultyWrite(journalFile, true)
        editor.commit()

        // Once the journal has a failure, subsequent writes aren't permitted.
        fileSystem.setFaultyWrite(journalFile, false)
        assertThat(cache.edit("d")).isNull()

        // Confirm that the fault didn't corrupt entries stored before the fault was introduced.
        cache.close()
        cache = DiskLruCache(fileSystem, cacheDir, dispatcher, Long.MAX_VALUE, appVersion, 2)
        caches += cache
        assertValue("a", "a", "a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertAbsent("d")
    }

    @Test
    fun journalWriteFailsDuringEditorAbort() {
        set("a", "a", "a")
        set("b", "b", "b")

        // Create an entry that fails to write to the journal during abort.
        val editor = cache.edit("c")!!
        editor.setString(0, "c")
        editor.setString(1, "c")
        fileSystem.setFaultyWrite(journalFile, true)
        editor.abort()

        // Once the journal has a failure, subsequent writes aren't permitted.
        fileSystem.setFaultyWrite(journalFile, false)
        assertThat(cache.edit("d")).isNull()

        // Confirm that the fault didn't corrupt entries stored before the fault was introduced.
        cache.close()
        cache = DiskLruCache(fileSystem, cacheDir, dispatcher, Long.MAX_VALUE, appVersion, 2)
        caches += cache
        assertValue("a", "a", "a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertAbsent("d")
    }

    @Test
    fun journalWriteFailsDuringRemove() {
        set("a", "a", "a")
        set("b", "b", "b")

        // Remove, but the journal write will fail.
        fileSystem.setFaultyWrite(journalFile, true)
        assertThat(cache.remove("a")).isTrue

        // Confirm that the entry was still removed.
        fileSystem.setFaultyWrite(journalFile, false)
        cache.close()
        cache = DiskLruCache(fileSystem, cacheDir, dispatcher, Long.MAX_VALUE, appVersion, 2)
        caches += cache
        assertAbsent("a")
        assertValue("b", "b", "b")
    }

    @Test
    fun cleanupTrimFailurePreventsNewEditors() {
        cache.maxSize = 8
        dispatcher.runNextTask()
        set("a", "aa", "aa")
        set("b", "bb", "bbb")

        // Cause the cache trim job to fail.
        fileSystem.setFaultyDelete(cacheDir / "a.0", true)
        dispatcher.runNextTask()

        // Confirm that edits are prevented after a cache trim failure.
        assertThat(cache.edit("a")).isNull()
        assertThat(cache.edit("b")).isNull()
        assertThat(cache.edit("c")).isNull()

        // Allow the test to clean up.
        fileSystem.setFaultyDelete(cacheDir / "a.0", false)
    }

    @Test
    fun cleanupTrimFailureRetriedOnEditors() {
        cache.maxSize = 8
        dispatcher.runNextTask()
        set("a", "aa", "aa")
        set("b", "bb", "bbb")

        // Cause the cache trim job to fail.
        fileSystem.setFaultyDelete(cacheDir / "a.0", true)
        dispatcher.runNextTask()

        // An edit should now add a job to clean up if the most recent trim failed.
        assertThat(cache.edit("b")).isNull()
        dispatcher.runNextTask()

        // Confirm a successful cache trim now allows edits.
        fileSystem.setFaultyDelete(cacheDir / "a.0", false)
        assertThat(cache.edit("c")).isNull()
        dispatcher.runNextTask()
        set("c", "cc", "cc")
        assertValue("c", "cc", "cc")
    }

    @Test
    fun cleanupTrimFailureWithInFlightEditor() {
        cache.maxSize = 8
        dispatcher.runNextTask()
        set("a", "aa", "aaa")
        set("b", "bb", "bb")
        val inFlightEditor = cache.edit("c")!!

        // Cause the cache trim job to fail.
        fileSystem.setFaultyDelete(cacheDir / "a.0", true)
        dispatcher.runNextTask()

        // The in-flight editor can still write after a trim failure.
        inFlightEditor.setString(0, "cc")
        inFlightEditor.setString(1, "cc")
        inFlightEditor.commit()

        // Confirm the committed values are present after a successful cache trim.
        fileSystem.setFaultyDelete(cacheDir / "a.0", false)
        dispatcher.runNextTask()
        assertValue("c", "cc", "cc")
    }

    @Test
    fun cleanupTrimFailureAllowsSnapshotReads() {
        cache.maxSize = 8
        dispatcher.runNextTask()
        set("a", "aa", "aa")
        set("b", "bb", "bbb")

        // Cause the cache trim job to fail.
        fileSystem.setFaultyDelete(cacheDir / "a.0", true)
        dispatcher.runNextTask()

        // Confirm we still allow snapshot reads after a trim failure.
        assertValue("a", "aa", "aa")
        assertValue("b", "bb", "bbb")

        // Allow the test to clean up.
        fileSystem.setFaultyDelete(cacheDir / "a.0", false)
    }

    @Test
    fun cleanupTrimFailurePreventsSnapshotWrites() {
        cache.maxSize = 8
        dispatcher.runNextTask()
        set("a", "aa", "aa")
        set("b", "bb", "bbb")

        // Cause the cache trim job to fail.
        fileSystem.setFaultyDelete(cacheDir / "a.0", true)
        dispatcher.runNextTask()

        // Confirm snapshot writes are prevented after a trim failure.
        cache["a"]!!.use {
            assertThat(cache.edit(it.entry.key)).isNull()
        }
        cache["b"]!!.use {
            assertThat(cache.edit(it.entry.key)).isNull()
        }

        // Allow the test to clean up.
        fileSystem.setFaultyDelete(cacheDir / "a.0", false)
    }

    @Test
    fun evictAllAfterCleanupTrimFailure() {
        cache.maxSize = 8
        dispatcher.runNextTask()
        set("a", "aa", "aa")
        set("b", "bb", "bbb")

        // Cause the cache trim job to fail.
        fileSystem.setFaultyDelete(cacheDir / "a.0", true)
        dispatcher.runNextTask()

        // Confirm we prevent edits after a trim failure.
        assertThat(cache.edit("c")).isNull()

        // A successful eviction should allow new writes.
        fileSystem.setFaultyDelete(cacheDir / "a.0", false)
        cache.evictAll()
        set("c", "cc", "cc")
        assertValue("c", "cc", "cc")
    }

    @Test
    fun manualRemovalAfterCleanupTrimFailure() {
        cache.maxSize = 8
        dispatcher.runNextTask()
        set("a", "aa", "aa")
        set("b", "bb", "bbb")

        // Cause the cache trim job to fail.
        fileSystem.setFaultyDelete(cacheDir / "a.0", true)
        dispatcher.runNextTask()

        // Confirm we prevent edits after a trim failure.
        assertThat(cache.edit("c")).isNull()

        // A successful removal which trims the cache should allow new writes.
        fileSystem.setFaultyDelete(cacheDir / "a.0", false)
        cache.remove("a")
        set("c", "cc", "cc")
        assertValue("c", "cc", "cc")
    }

    @Test
    fun flushingAfterCleanupTrimFailure() {
        cache.maxSize = 8
        dispatcher.runNextTask()
        set("a", "aa", "aa")
        set("b", "bb", "bbb")

        // Cause the cache trim job to fail.
        fileSystem.setFaultyDelete(cacheDir / "a.0", true)
        dispatcher.runNextTask()

        // Confirm we prevent edits after a trim failure.
        assertThat(cache.edit("c")).isNull()

        // A successful flush trims the cache and should allow new writes.
        fileSystem.setFaultyDelete(cacheDir / "a.0", false)
        cache.flush()
        set("c", "cc", "cc")
        assertValue("c", "cc", "cc")
    }

    @Test
    fun cleanupTrimFailureWithPartialSnapshot() {
        cache.maxSize = 8
        dispatcher.runNextTask()
        set("a", "aa", "aa")
        set("b", "bb", "bbb")

        // Cause the cache trim to fail on the second value leaving a partial snapshot.
        fileSystem.setFaultyDelete(cacheDir / "a.1", true)
        dispatcher.runNextTask()

        // Confirm the partial snapshot is not returned.
        assertThat(cache["a"]).isNull()

        // Confirm we prevent edits after a trim failure.
        assertThat(cache.edit("a")).isNull()

        // Confirm the partial snapshot is not returned after a successful trim.
        fileSystem.setFaultyDelete(cacheDir / "a.1", false)
        dispatcher.runNextTask()
        assertThat(cache["a"]).isNull()
    }

    @Test
    fun noNewSourceAfterEditorDetached() {
        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        cache.evictAll()
        assertThat(editor.newSource(0)).isNull()
    }

    @Test
    fun `edit discarded after editor detached`() {
        set("k1", "a", "a")

        // Create an editor, then detach it.
        val editor = cache.edit("k1")!!
        editor.newSink(0).buffer().use { sink ->
            cache.evictAll()

            // Complete the original edit. It goes into a black hole.
            sink.writeUtf8("bb")
        }
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun abortAfterDetach() {
        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        cache.evictAll()
        editor.abort()
        assertThat(cache.size()).isEqualTo(0)
        assertAbsent("k1")
    }

    @Test
    fun dontRemoveUnfinishedEntryWhenCreatingSnapshot() {
        val creator = cache.edit("k1")!!
        creator.setString(0, "ABC")
        creator.setString(1, "DE")
        assertThat(creator.newSource(0)).isNull()
        assertThat(creator.newSource(1)).isNull()
        val snapshotWhileEditing = cache["k1"]
        assertThat(snapshotWhileEditing).isNull() // entry still is being created/edited
        creator.commit()
        val snapshotAfterCommit = cache["k1"]
        assertThat(snapshotAfterCommit)
            .withFailMessage("Entry has been removed during creation.").isNull()
    }

    @Test
    fun `Windows cannot read while writing`() {
        assumeTrue(windows)

        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        assertThat(cache["k1"]).isNull()
        editor.commit()
    }

    @Test
    fun `Windows cannot write while reading`() {
        assumeTrue(windows)

        set("k1", "a", "a")
        val snapshot = cache["k1"]!!
        assertThat(cache.edit("k1")).isNull()
        snapshot.close()
    }

    @Test
    fun `can read while reading`() {
        set("k1", "a", "a")
        cache["k1"]!!.use { snapshot1 ->
            snapshot1.assertValue(0, "a")
            cache["k1"]!!.use { snapshot2 ->
                snapshot2.assertValue(0, "a")
                snapshot1.assertValue(1, "a")
                snapshot2.assertValue(1, "a")
            }
        }
    }

    @Test
    fun `remove while reading creates zombie that is removed when read finishes`() {
        val afterRemoveFileContents = if (windows) "a" else null

        set("k1", "a", "a")
        cache["k1"]!!.use { snapshot1 ->
            cache.remove("k1")

            // On Windows files still exist with open with 2 open sources.
            assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveFileContents)
            assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()

            // On Windows files still exist with open with 1 open source.
            snapshot1.assertValue(0, "a")
            assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveFileContents)
            assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()

            // On all platforms files are deleted when all sources are closed.
            snapshot1.assertValue(1, "a")
            assertThat(readFileOrNull(getCleanFile("k1", 0))).isNull()
            assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()
        }
    }

    @Test
    fun `remove while writing creates zombie that is removed when write finishes`() {
        val afterRemoveFileContents = if (windows) "a" else null

        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        cache.remove("k1")
        assertThat(cache["k1"]).isNull()

        // On Windows files still exist while being edited.
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveFileContents)
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()

        // On all platforms files are deleted when the edit completes.
        editor.commit()
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isNull()
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()
    }

    @Test
    fun `Windows cannot read zombie entry`() {
        assumeTrue(windows)

        set("k1", "a", "a")
        cache["k1"]!!.use {
            cache.remove("k1")
            assertThat(cache["k1"]).isNull()
        }
    }

    @Test
    fun `Windows cannot write zombie entry`() {
        assumeTrue(windows)

        set("k1", "a", "a")
        cache["k1"]!!.use {
            cache.remove("k1")
            assertThat(cache.edit("k1")).isNull()
        }
    }

    @Test
    fun `close with zombie read`() {
        val afterRemoveFileContents = if (windows) "a" else null

        set("k1", "a", "a")
        cache["k1"]!!.use {
            cache.remove("k1")

            // After we close the cache the files continue to exist!
            cache.close()
            assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveFileContents)
            assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()

            // But they disappear when the sources are closed.
            it.assertValue(0, "a")
            it.assertValue(1, "a")
            assertThat(readFileOrNull(getCleanFile("k1", 0))).isNull()
            assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()
        }
    }

    @Test
    fun `close with zombie write`() {
        val afterRemoveCleanFileContents = if (windows) "a" else null
        val afterRemoveDirtyFileContents = if (windows) "" else null

        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        val sink0 = editor.newSink(0)
        cache.remove("k1")

        // After we close the cache the files continue to exist!
        cache.close()
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveCleanFileContents)
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isEqualTo(afterRemoveDirtyFileContents)

        // But they disappear when the edit completes.
        sink0.close()
        editor.commit()
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isNull()
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()
    }

    @Test
    fun `close with completed zombie write`() {
        val afterRemoveCleanFileContents = if (windows) "a" else null
        val afterRemoveDirtyFileContents = if (windows) "b" else null

        set("k1", "a", "a")
        val editor = cache.edit("k1")!!
        editor.setString(0, "b")
        cache.remove("k1")

        // After we close the cache the files continue to exist!
        cache.close()
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isEqualTo(afterRemoveCleanFileContents)
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isEqualTo(afterRemoveDirtyFileContents)

        // But they disappear when the edit completes.
        editor.commit()
        assertThat(readFileOrNull(getCleanFile("k1", 0))).isNull()
        assertThat(readFileOrNull(getDirtyFile("k1", 0))).isNull()
    }

    private fun assertJournalEquals(vararg expectedBodyLines: String) {
        assertThat(readJournalLines()).isEqualTo(
            listOf(DiskLruCache.MAGIC, DiskLruCache.VERSION, "100", "2", "") + expectedBodyLines
        )
    }

    private fun createJournal(vararg bodyLines: String) {
        createJournalWithHeader(DiskLruCache.MAGIC, DiskLruCache.VERSION, "100",
            "2", "", *bodyLines)
    }

    @Suppress("SameParameterValue")
    private fun createJournalWithHeader(
        magic: String,
        version: String,
        appVersion: String,
        valueCount: String,
        blank: String,
        vararg bodyLines: String
    ) {
        fileSystem.write(journalFile) {
            writeUtf8(
                """
                |$magic
                |$version
                |$appVersion
                |$valueCount
                |$blank
                |""".trimMargin()
            )
            for (line in bodyLines) {
                writeUtf8(line)
                writeUtf8("\n")
            }
        }
    }

    private fun readJournalLines(): List<String> {
        val result = mutableListOf<String>()
        fileSystem.read(journalFile) {
            while (true) {
                val line = readUtf8Line() ?: break
                result.add(line)
            }
        }
        return result
    }

    private fun getCleanFile(key: String, index: Int) = cacheDir / "$key.$index"

    private fun getDirtyFile(key: String, index: Int) = cacheDir / "$key.$index.tmp"

    private fun readFile(file: Path): String {
        return fileSystem.read(file) {
            readUtf8()
        }
    }

    private fun readFileOrNull(file: Path): String? {
        return try {
            fileSystem.read(file) {
                readUtf8()
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    fun writeFile(file: Path, content: String) {
        file.parent?.let {
            fileSystem.createDirectories(it)
        }
        fileSystem.write(file) {
            writeUtf8(content)
        }
    }

    private fun generateSomeGarbageFiles() {
        val dir1 = cacheDir / "dir1"
        val dir2 = dir1 / "dir2"
        writeFile(getCleanFile("g1", 0), "A")
        writeFile(getCleanFile("g1", 1), "B")
        writeFile(getCleanFile("g2", 0), "C")
        writeFile(getCleanFile("g2", 1), "D")
        writeFile(getCleanFile("g2", 1), "D")
        writeFile(cacheDir / "otherFile0", "E")
        writeFile(dir2 / "otherFile1", "F")
    }

    private fun assertGarbageFilesAllDeleted() {
        assertThat(fileSystem.exists(getCleanFile("g1", 0))).isFalse
        assertThat(fileSystem.exists(getCleanFile("g1", 1))).isFalse
        assertThat(fileSystem.exists(getCleanFile("g2", 0))).isFalse
        assertThat(fileSystem.exists(getCleanFile("g2", 1))).isFalse
        assertThat(fileSystem.exists(cacheDir / "otherFile0")).isFalse
        assertThat(fileSystem.exists(cacheDir / "dir1")).isFalse
    }

    private fun set(key: String, value0: String, value1: String) {
        val editor = cache.edit(key)!!
        editor.setString(0, value0)
        editor.setString(1, value1)
        editor.commit()
    }

    private fun assertAbsent(key: String) {
        val snapshot = cache[key]
        if (snapshot != null) {
            snapshot.close()
            fail("")
        }
        assertThat(fileSystem.exists(getCleanFile(key, 0))).isFalse
        assertThat(fileSystem.exists(getCleanFile(key, 1))).isFalse
        assertThat(fileSystem.exists(getDirtyFile(key, 0))).isFalse
        assertThat(fileSystem.exists(getDirtyFile(key, 1))).isFalse
    }

    private fun assertValue(key: String, value0: String, value1: String) {
        cache[key]!!.use {
            it.assertValue(0, value0)
            it.assertValue(1, value1)
            assertThat(fileSystem.exists(getCleanFile(key, 0))).isTrue
            assertThat(fileSystem.exists(getCleanFile(key, 1))).isTrue
        }
    }

    private fun Snapshot.assertValue(index: Int, value: String) {
        getSource(index).use { source ->
            assertThat(sourceAsString(source)).isEqualTo(value)
            assertThat(entry.lengths[index]).isEqualTo(value.length.toLong())
        }
    }

    private fun sourceAsString(source: Source) = source.buffer().readUtf8()

    private fun Editor.assertInoperable() {
        try {
            setString(0, "A")
            fail("")
        } catch (_: IllegalStateException) {
        }
        try {
            newSource(0)
            fail("")
        } catch (_: IllegalStateException) {
        }
        try {
            newSink(0)
            fail("")
        } catch (_: IllegalStateException) {
        }
        try {
            commit()
            fail("")
        } catch (_: IllegalStateException) {
        }
        try {
            abort()
            fail("")
        } catch (_: IllegalStateException) {
        }
    }

    private fun Editor.setString(index: Int, value: String) {
        newSink(index).buffer().use { writer ->
            writer.writeUtf8(value)
        }
    }

    private fun DiskLruCache.Editor.newSink(index: Int) = fileSystem.sink(file(index))

    private fun DiskLruCache.Editor.newSource(index: Int) = fileSystem.source(file(index))

    private fun DiskLruCache.Snapshot.getSource(index: Int) = fileSystem.source(file(index))

    private fun DiskLruCache.isClosed(): Boolean {
        try {
            flush()
            return false
        } catch (e: IllegalStateException) {
            return e.message == "cache is closed"
        }
    }
}
