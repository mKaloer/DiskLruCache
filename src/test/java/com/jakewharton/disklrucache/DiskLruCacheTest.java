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

package com.jakewharton.disklrucache;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import static org.fest.assertions.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import sun.misc.LRUCache;

public final class DiskLruCacheTest {
    private File cacheDir;
    private DiskLruCache cache;

    @Rule public TemporaryFolder tempDir = new TemporaryFolder();

    @Before public void setUp() throws Exception {
        cacheDir = tempDir.newFolder("DiskLruCacheTest");
        for (File file : cacheDir.listFiles()) {
            file.delete();
        }
        cache = DiskLruCache.open(cacheDir, 2, Integer.MAX_VALUE);
    }

    @After public void tearDown() throws Exception {
        cache.close();
    }

    @Test public void writeAndReadEntry() throws Exception {
        DiskLruCache.Editor creator = cache.edit("k1");
        creator.set(0, "ABC");
        creator.set(1, "DE");
        assertThat(creator.getString(0)).isNull();
        assertThat(creator.newInputStream(0)).isNull();
        assertThat(creator.getString(1)).isNull();
        assertThat(creator.newInputStream(1)).isNull();
        creator.commit();

        DiskLruCache.Snapshot snapshot = cache.get("k1");
        assertThat(snapshot.getString(0)).isEqualTo("ABC");
        assertThat(snapshot.getLength(0)).isEqualTo(3);
        assertThat(snapshot.getString(1)).isEqualTo("DE");
        assertThat(snapshot.getLength(1)).isEqualTo(2);
    }

    @Test public void cannotOperateOnEditAfterPublish() throws Exception {
        DiskLruCache.Editor editor = cache.edit("k1");
        editor.set(0, "A");
        editor.set(1, "B");
        editor.commit();
        assertInoperable(editor);
    }

    @Test public void cannotOperateOnEditAfterRevert() throws Exception {
        DiskLruCache.Editor editor = cache.edit("k1");
        editor.set(0, "A");
        editor.set(1, "B");
        editor.abort();
        assertInoperable(editor);
    }

    @Test public void explicitRemoveAppliedToDiskImmediately() throws Exception {
        DiskLruCache.Editor editor = cache.edit("k1");
        editor.set(0, "ABC");
        editor.set(1, "B");
        editor.commit();
        File k1 = getCleanFile("k1", 0);
        assertThat(readFile(k1)).isEqualTo("ABC");
        cache.remove("k1");
        assertThat(k1.exists()).isFalse();
    }

    /**
     * Each read sees a snapshot of the file at the time read was called.
     * This means that two reads of the same key can see different data.
     */
    @Test public void readAndWriteOverlapsMaintainConsistency() throws Exception {
        DiskLruCache.Editor v1Creator = cache.edit("k1");
        v1Creator.set(0, "AAaa");
        v1Creator.set(1, "BBbb");
        v1Creator.commit();

        DiskLruCache.Snapshot snapshot1 = cache.get("k1");
        InputStream inV1 = snapshot1.getInputStream(0);
        assertThat(inV1.read()).isEqualTo('A');
        assertThat(inV1.read()).isEqualTo('A');

        DiskLruCache.Editor v1Updater = cache.edit("k1");
        v1Updater.set(0, "CCcc");
        v1Updater.set(1, "DDdd");
        v1Updater.commit();

        DiskLruCache.Snapshot snapshot2 = cache.get("k1");
        assertThat(snapshot2.getString(0)).isEqualTo("CCcc");
        assertThat(snapshot2.getLength(0)).isEqualTo(4);
        assertThat(snapshot2.getString(1)).isEqualTo("DDdd");
        assertThat(snapshot2.getLength(1)).isEqualTo(4);
        snapshot2.close();

        assertThat(inV1.read()).isEqualTo('a');
        assertThat(inV1.read()).isEqualTo('a');
        assertThat(snapshot1.getString(1)).isEqualTo("BBbb");
        assertThat(snapshot1.getLength(1)).isEqualTo(4);
        snapshot1.close();
    }

    @Test public void createNewEntryWithTooFewValuesFails() throws Exception {
        DiskLruCache.Editor creator = cache.edit("k1");
        creator.set(1, "A");
        try {
            creator.commit();
            fail();
        } catch (IllegalStateException expected) {
        }

        assertThat(getCleanFile("k1", 0).exists()).isFalse();
        assertThat(getCleanFile("k1", 1).exists()).isFalse();
        assertThat(getDirtyFile("k1", 0).exists()).isFalse();
        assertThat(getDirtyFile("k1", 1).exists()).isFalse();
        assertThat(cache.get("k1")).isNull();

        DiskLruCache.Editor creator2 = cache.edit("k1");
        creator2.set(0, "B");
        creator2.set(1, "C");
        creator2.commit();
    }

    @Test public void revertWithTooFewValues() throws Exception {
        DiskLruCache.Editor creator = cache.edit("k1");
        creator.set(1, "A");
        creator.abort();
        assertThat(getCleanFile("k1", 0).exists()).isFalse();
        assertThat(getCleanFile("k1", 1).exists()).isFalse();
        assertThat(getDirtyFile("k1", 0).exists()).isFalse();
        assertThat(getDirtyFile("k1", 1).exists()).isFalse();
        assertThat(cache.get("k1")).isNull();
    }

    @Test public void updateExistingEntryWithTooFewValuesReusesPreviousValues() throws Exception {
        DiskLruCache.Editor creator = cache.edit("k1");
        creator.set(0, "A");
        creator.set(1, "B");
        creator.commit();

        DiskLruCache.Editor updater = cache.edit("k1");
        updater.set(0, "C");
        updater.commit();

        DiskLruCache.Snapshot snapshot = cache.get("k1");
        assertThat(snapshot.getString(0)).isEqualTo("C");
        assertThat(snapshot.getLength(0)).isEqualTo(1);
        assertThat(snapshot.getString(1)).isEqualTo("B");
        assertThat(snapshot.getLength(1)).isEqualTo(1);
        snapshot.close();
    }

    @Test public void growMaxSize() throws Exception {
        cache.close();
        cache = DiskLruCache.open(cacheDir, 2, 10);
        set("a", "a", "aaa"); // size 4
        set("b", "bb", "bbbb"); // size 6
        cache.setMaxSize(20);
        set("c", "c", "c"); // size 12
        assertThat(cache.size()).isEqualTo(12);
    }

    @Test public void shrinkMaxSizeEvicts() throws Exception {
        cache.close();
        cache = DiskLruCache.open(cacheDir, 2, 20);
        set("a", "a", "aaa"); // size 4
        set("b", "bb", "bbbb"); // size 6
        set("c", "c", "c"); // size 12
        cache.setMaxSize(10);
        assertThat(cache.executorService.getQueue().size()).isEqualTo(1);
        cache.executorService.purge();
    }

    @Test public void evictOnInsert() throws Exception {
        cache.close();
        cache = DiskLruCache.open(cacheDir, 2, 10);

        set("a", "a", "aaa"); // size 4
        set("b", "bb", "bbbb"); // size 6
        assertThat(cache.size()).isEqualTo(10);

        // Cause the size to grow to 12 should evict 'A'.
        set("c", "c", "c");
        cache.flush();
        assertThat(cache.size()).isEqualTo(8);
        assertAbsent("a");
        assertValue("b", "bb", "bbbb");
        assertValue("c", "c", "c");

        // Causing the size to grow to 10 should evict nothing.
        set("d", "d", "d");
        cache.flush();
        assertThat(cache.size()).isEqualTo(10);
        assertAbsent("a");
        assertValue("b", "bb", "bbbb");
        assertValue("c", "c", "c");
        assertValue("d", "d", "d");

        // Causing the size to grow to 18 should evict 'B' and 'C'.
        set("e", "eeee", "eeee");
        cache.flush();
        assertThat(cache.size()).isEqualTo(10);
        assertAbsent("a");
        assertAbsent("b");
        assertAbsent("c");
        assertValue("d", "d", "d");
        assertValue("e", "eeee", "eeee");
    }

    @Test public void evictOnUpdate() throws Exception {
        cache.close();
        cache = DiskLruCache.open(cacheDir, 2, 10);

        set("a", "a", "aa"); // size 3
        set("b", "b", "bb"); // size 3
        set("c", "c", "cc"); // size 3
        assertThat(cache.size()).isEqualTo(9);

        // Causing the size to grow to 11 should evict 'A'.
        set("b", "b", "bbbb");
        cache.flush();
        assertThat(cache.size()).isEqualTo(8);
        assertAbsent("a");
        assertValue("b", "b", "bbbb");
        assertValue("c", "c", "cc");
    }

    @Test public void evictionHonorsLruFromCurrentSession() throws Exception {
        cache.close();
        cache = DiskLruCache.open(cacheDir, 2, 10);
        set("a", "a", "a");
        set("b", "b", "b");
        set("c", "c", "c");
        set("d", "d", "d");
        set("e", "e", "e");
        cache.get("b").close(); // 'B' is now least recently used.

        // Causing the size to grow to 12 should evict 'A'.
        set("f", "f", "f");
        // Causing the size to grow to 12 should evict 'C'.
        set("g", "g", "g");
        cache.flush();
        assertThat(cache.size()).isEqualTo(10);
        assertAbsent("a");
        assertValue("b", "b", "b");
        assertAbsent("c");
        assertValue("d", "d", "d");
        assertValue("e", "e", "e");
        assertValue("f", "f", "f");
    }

    @Test public void cacheSingleEntryOfSizeGreaterThanMaxSize() throws Exception {
        cache.close();
        cache = DiskLruCache.open(cacheDir, 2, 10);
        set("a", "aaaaa", "aaaaaa"); // size=11
        cache.flush();
        assertAbsent("a");
    }

    @Test public void cacheSingleValueOfSizeGreaterThanMaxSize() throws Exception {
        cache.close();
        cache = DiskLruCache.open(cacheDir, 2, 10);
        set("a", "aaaaaaaaaaa", "a"); // size=12
        cache.flush();
        assertAbsent("a");
    }

    @Test public void constructorDoesNotAllowZeroCacheSize() throws Exception {
        try {
            DiskLruCache.open(cacheDir, 2, 0);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test public void constructorDoesNotAllowZeroValuesPerEntry() throws Exception {
        try {
            DiskLruCache.open(cacheDir, 0, 10);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test public void removeAbsentElement() throws Exception {
        cache.remove("a");
    }

    @Test public void readingTheSameStreamMultipleTimes() throws Exception {
        set("a", "a", "b");
        DiskLruCache.Snapshot snapshot = cache.get("a");
        assertThat(snapshot.getInputStream(0)).isSameAs(snapshot.getInputStream(0));
        snapshot.close();
    }

    @Test public void openCreatesDirectoryIfNecessary() throws Exception {
        cache.close();
        File dir = tempDir.newFolder("testOpenCreatesDirectoryIfNecessary");
        cache = DiskLruCache.open(dir, 2, Integer.MAX_VALUE);
        set("a", "a", "a");
        String hashedKey = DigestUtils.sha256Hex("a");
        assertThat(new File(dir + File.separator + hashedKey.substring(0, 2), hashedKey + ".0").exists()).isTrue();
        assertThat(new File(dir + File.separator + hashedKey.substring(0, 2), hashedKey + ".1").exists()).isTrue();
    }

    @Test public void fileDeletedExternally() throws Exception {
        set("a", "a", "a");
        getCleanFile("a", 1).delete();
        assertThat(cache.get("a")).isNull();
    }

    @Test public void editSameVersion() throws Exception {
        set("a", "a", "a");
        DiskLruCache.Snapshot snapshot = cache.get("a");
        DiskLruCache.Editor editor = snapshot.edit();
        editor.set(1, "a2");
        editor.commit();
        assertValue("a", "a", "a2");
    }

    @Test public void editSnapshotAfterChangeAborted() throws Exception {
        set("a", "a", "a");
        DiskLruCache.Snapshot snapshot = cache.get("a");
        DiskLruCache.Editor toAbort = snapshot.edit();
        toAbort.set(0, "b");
        toAbort.abort();
        DiskLruCache.Editor editor = snapshot.edit();
        editor.set(1, "a2");
        editor.commit();
        assertValue("a", "a", "a2");
    }

    @Test public void editSnapshotAfterChangeCommitted() throws Exception {
        set("a", "a", "a");
        DiskLruCache.Snapshot snapshot = cache.get("a");
        DiskLruCache.Editor toAbort = snapshot.edit();
        toAbort.set(0, "b");
        toAbort.commit();
        assertThat(snapshot.edit()).isNull();
    }

    @Test public void editSinceEvicted() throws Exception {
        cache.close();
        cache = DiskLruCache.open(cacheDir, 2, 10);
        set("a", "aa", "aaa"); // size 5
        DiskLruCache.Snapshot snapshot = cache.get("a");
        set("b", "bb", "bbb"); // size 5
        set("c", "cc", "ccc"); // size 5; will evict 'A'
        cache.flush();
        assertThat(snapshot.edit()).isNull();
    }

    @Test public void editSinceEvictedAndRecreated() throws Exception {
        cache.close();
        cache = DiskLruCache.open(cacheDir, 2, 10);
        set("a", "aa", "aaa"); // size 5
        DiskLruCache.Snapshot snapshot = cache.get("a");
        set("b", "bb", "bbb"); // size 5
        set("c", "cc", "ccc"); // size 5; will evict 'A'
        set("a", "a", "aaaa"); // size 5; will evict 'B'
        cache.flush();
        assertThat(snapshot.edit()).isNull();
    }

    /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
    @Test public void aggressiveClearingHandlesWrite() throws Exception {
        FileUtils.deleteDirectory(cacheDir);
        set("a", "a", "a");
        assertValue("a", "a", "a");
    }

    /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
    @Test public void aggressiveClearingHandlesEdit() throws Exception {
        set("a", "a", "a");
        DiskLruCache.Editor a = cache.get("a").edit();
        FileUtils.deleteDirectory(cacheDir);
        a.set(1, "a2");
        a.commit();
    }

    @Test public void removeHandlesMissingFile() throws Exception {
        set("a", "a", "a");
        getCleanFile("a", 0).delete();
        cache.remove("a");
    }

    /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
    @Test public void aggressiveClearingHandlesPartialEdit() throws Exception {
        set("a", "a", "a");
        set("b", "b", "b");
        DiskLruCache.Editor a = cache.get("a").edit();
        a.set(0, "a1");
        FileUtils.deleteDirectory(cacheDir);
        a.set(1, "a2");
        a.commit();
        assertThat(cache.get("a")).isNull();
    }

    /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
    @Test public void aggressiveClearingHandlesRead() throws Exception {
        FileUtils.deleteDirectory(cacheDir);
        assertThat(cache.get("a")).isNull();
    }

    @Test public void readsExistingSingleFileCorrectly() throws Exception {
        File f = new File(cacheDir, "58/58a7b0785038663a4f0cdd38628bba57ecf86ffa37f692d9493d87a61aa3c9ae.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        cache = DiskLruCache.open(cacheDir, 1, 99999);
        assertThat(cache.get("cls1.cfc76a55-d434-4f0e-8bea-3a015e9ee6f0.training")).isNotNull();
    }

    @Test public void readsExistingMultipleFilesWithSamePrefixCorrectly() throws Exception {
        File f = new File(cacheDir, "58/58a7b0785038663a4f0cdd38628bba57ecf86ffa37f692d9493d87a61aa3c9ae.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "58/585bbc0122a3173e70cdc2b08ce6d9b9dc3d64629fa6c46057ba6c61df443f21.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "58/58aa33116c3ba982e806cf28208f97cfc981144a271d479a466ed08fc1dc6cf6.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        cache = DiskLruCache.open(cacheDir, 1, 99999);
        assertThat(cache.get("cls1.cfc76a55-d434-4f0e-8bea-3a015e9ee6f0.training")).isNotNull();
        assertThat(cache.get("RgNIf9vHXHGckbBjYOAgbTUqONUaiDGk")).isNotNull();
        assertThat(cache.get("cBJOKiwVrekMmqV4LVVUNMPonIx65bdz")).isNotNull();
        assertThat(cache.get("shouldNotBeFound")).isNull();
    }

    @Test public void readsExistingMultipleFilesWithDifferentPrefixesCorrectly() throws Exception {
        File f = new File(cacheDir, "58/58a7b0785038663a4f0cdd38628bba57ecf86ffa37f692d9493d87a61aa3c9ae.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "50/5039c90292bc3e3328b82eee165546ee30175a2cc8c8b94dcc6eecf9126041cf.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "aa/aacec3e213d7e7df8bcecd92fe825976803e7d116c4bf45ad15224633744e0b8.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        cache = DiskLruCache.open(cacheDir, 1, 99999);
        assertThat(cache.get("cls1.cfc76a55-d434-4f0e-8bea-3a015e9ee6f0.training")).isNotNull();
        assertThat(cache.get("JJq3EFKcK4fdbZyBKBDt5OF4qxqP2hTI")).isNotNull();
        assertThat(cache.get("aW04z5gcnXtRWPXXFLtfxJs1EMKzPHiM")).isNotNull();
        assertThat(cache.get("shouldNotBeFound")).isNull();
    }

    @Test public void readsExistingEmptyDir() throws Exception {
        cache = DiskLruCache.open(cacheDir, 1, 99999);
        assertThat(cache.get("cls1.cfc76a55-d434-4f0e-8bea-3a015e9ee6f0.training")).isNull();
        assertThat(cache.get("JJq3EFKcK4fdbZyBKBDt5OF4qxqP2hTI")).isNull();
        assertThat(cache.get("aW04z5gcnXtRWPXXFLtfxJs1EMKzPHiM")).isNull();
    }

    @Test public void readsExistingDirWithInvalidFileNames() throws Exception {
        File f = new File(cacheDir, "hello/hello58a7b0785038663a4f0cdd38628bba57ecf86ffa37f692d9493d87a61aa3c9ae.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "hello/hello5039c90292bc3e3328b82eee165546ee30175a2cc8c8b94dcc6eecf9126041cf.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "hello/helloaacec3e213d7e7df8bcecd92fe825976803e7d116c4bf45ad15224633744e0b8.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        cache = DiskLruCache.open(cacheDir, 1, 99999);
        assertThat(cache.get("cls1.cfc76a55-d434-4f0e-8bea-3a015e9ee6f0.training")).isNull();
        assertThat(cache.get("JJq3EFKcK4fdbZyBKBDt5OF4qxqP2hTI")).isNull();
        assertThat(cache.get("aW04z5gcnXtRWPXXFLtfxJs1EMKzPHiM")).isNull();
    }

    @Test public void readsExistingDirWithLargeValue() throws Exception {
        File f = new File(cacheDir, "58/58a7b0785038663a4f0cdd38628bba57ecf86ffa37f692d9493d87a61aa3c9ae.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "58/58a7b0785038663a4f0cdd38628bba57ecf86ffa37f692d9493d87a61aa3c9ae.1");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "50/5039c90292bc3e3328b82eee165546ee30175a2cc8c8b94dcc6eecf9126041cf.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "50/5039c90292bc3e3328b82eee165546ee30175a2cc8c8b94dcc6eecf9126041cf.1");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "aa/aacec3e213d7e7df8bcecd92fe825976803e7d116c4bf45ad15224633744e0b8.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        cache = DiskLruCache.open(cacheDir, 2, 99999);
        assertThat(cache.get("cls1.cfc76a55-d434-4f0e-8bea-3a015e9ee6f0.training")).isNotNull();
        assertThat(cache.get("cls1.cfc76a55-d434-4f0e-8bea-3a015e9ee6f0.training").getInputStream(0)).isNotNull();
        assertThat(cache.get("cls1.cfc76a55-d434-4f0e-8bea-3a015e9ee6f0.training").getInputStream(1)).isNotNull();
        assertThat(cache.get("JJq3EFKcK4fdbZyBKBDt5OF4qxqP2hTI")).isNotNull();
        assertThat(cache.get("JJq3EFKcK4fdbZyBKBDt5OF4qxqP2hTI").getInputStream(0)).isNotNull();
        assertThat(cache.get("JJq3EFKcK4fdbZyBKBDt5OF4qxqP2hTI").getInputStream(1)).isNotNull();
    }

    @Test public void readsExistingDirWithNoZeroValueFails() throws Exception {
        File f = new File(cacheDir, "58/58a7b0785038663a4f0cdd38628bba57ecf86ffa37f692d9493d87a61aa3c9ae.1");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "50/5039c90292bc3e3328b82eee165546ee30175a2cc8c8b94dcc6eecf9126041cf.0");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "50/5039c90292bc3e3328b82eee165546ee30175a2cc8c8b94dcc6eecf9126041cf.1");
        f.getParentFile().mkdir();
        f.createNewFile();
        cache = DiskLruCache.open(cacheDir, 2, 99999);
        assertThat(cache.get("cls1.cfc76a55-d434-4f0e-8bea-3a015e9ee6f0.training")).isNull();
        assertThat(cache.get("JJq3EFKcK4fdbZyBKBDt5OF4qxqP2hTI")).isNotNull();
        assertThat(cache.get("JJq3EFKcK4fdbZyBKBDt5OF4qxqP2hTI").getInputStream(0)).isNotNull();
        assertThat(cache.get("JJq3EFKcK4fdbZyBKBDt5OF4qxqP2hTI").getInputStream(1)).isNotNull();
    }

    @Test public void readsExistingDirWithTooLargeValues() throws Exception {
        File f = new File(cacheDir, "hello/hello58a7b0785038663a4f0cdd38628bba57ecf86ffa37f692d9493d87a61aa3c9ae.6");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "hello/hello5039c90292bc3e3328b82eee165546ee30175a2cc8c8b94dcc6eecf9126041cf.5");
        f.getParentFile().mkdir();
        f.createNewFile();
        f = new File(cacheDir, "hello/helloaacec3e213d7e7df8bcecd92fe825976803e7d116c4bf45ad15224633744e0b8.5");
        f.getParentFile().mkdir();
        f.createNewFile();
        cache = DiskLruCache.open(cacheDir, 1, 99999);
        assertThat(cache.get("cls1.cfc76a55-d434-4f0e-8bea-3a015e9ee6f0.training")).isNull();
        assertThat(cache.get("JJq3EFKcK4fdbZyBKBDt5OF4qxqP2hTI")).isNull();
        assertThat(cache.get("aW04z5gcnXtRWPXXFLtfxJs1EMKzPHiM")).isNull();
    }

    private File getCleanFile(String key, int index) {
        File f = new File(cacheDir, DigestUtils.sha256Hex(key).substring(0, 2) +
                File.separator +
                DigestUtils.sha256Hex(key) +
                "." + index);
        f.getParentFile().mkdirs();
        return f;
    }

    private File getDirtyFile(String key, int index) {
        File f = new File(cacheDir, DigestUtils.sha256Hex(key).substring(0, 2) +
                File.separator +
                DigestUtils.sha256Hex(key) +
                "." + index + ".tmp");
        f.getParentFile().mkdirs();
        return f;
    }

    private static String readFile(File file) throws Exception {
        Reader reader = new FileReader(file);
        StringWriter writer = new StringWriter();
        char[] buffer = new char[1024];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            writer.write(buffer, 0, count);
        }
        reader.close();
        return writer.toString();
    }

    public static void writeFile(File file, String content) throws Exception {
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.close();
    }

    private static void assertInoperable(DiskLruCache.Editor editor) throws Exception {
        try {
            editor.getString(0);
            fail();
        } catch (IllegalStateException expected) {
        }
        try {
            editor.set(0, "A");
            fail();
        } catch (IllegalStateException expected) {
        }
        try {
            editor.newInputStream(0);
            fail();
        } catch (IllegalStateException expected) {
        }
        try {
            editor.newOutputStream(0);
            fail();
        } catch (IllegalStateException expected) {
        }
        try {
            editor.commit();
            fail();
        } catch (IllegalStateException expected) {
        }
        try {
            editor.abort();
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    private void generateSomeGarbageFiles() throws Exception {
        File dir1 = new File(cacheDir, "dir1");
        File dir2 = new File(dir1, "dir2");
        writeFile(getCleanFile("g1", 0), "A");
        writeFile(getCleanFile("g1", 1), "B");
        writeFile(getCleanFile("g2", 0), "C");
        writeFile(getCleanFile("g2", 1), "D");
        writeFile(getCleanFile("g2", 1), "D");
        writeFile(new File(cacheDir, "otherFile0"), "E");
        dir1.mkdir();
        dir2.mkdir();
        writeFile(new File(dir2, "otherFile1"), "F");
    }

    private void assertGarbageFilesAllDeleted() throws Exception {
        assertThat(getCleanFile("g1", 0)).doesNotExist();
        assertThat(getCleanFile("g1", 1)).doesNotExist();
        assertThat(getCleanFile("g2", 0)).doesNotExist();
        assertThat(getCleanFile("g2", 1)).doesNotExist();
        assertThat(new File(cacheDir, "otherFile0")).doesNotExist();
        assertThat(new File(cacheDir, "dir1")).doesNotExist();
    }

    private void set(String key, String value0, String value1) throws Exception {
        DiskLruCache.Editor editor = cache.edit(key);
        editor.set(0, value0);
        editor.set(1, value1);
        editor.commit();
    }

    private void assertAbsent(String key) throws Exception {
        DiskLruCache.Snapshot snapshot = cache.get(key);
        if (snapshot != null) {
            snapshot.close();
            fail();
        }
        assertThat(getCleanFile(key, 0)).doesNotExist();
        assertThat(getCleanFile(key, 1)).doesNotExist();
        assertThat(getDirtyFile(key, 0)).doesNotExist();
        assertThat(getDirtyFile(key, 1)).doesNotExist();
    }

    private void assertValue(String key, String value0, String value1) throws Exception {
        DiskLruCache.Snapshot snapshot = cache.get(key);
        assertThat(snapshot.getString(0)).isEqualTo(value0);
        assertThat(snapshot.getLength(0)).isEqualTo(value0.length());
        assertThat(snapshot.getString(1)).isEqualTo(value1);
        assertThat(snapshot.getLength(1)).isEqualTo(value1.length());
        assertThat(getCleanFile(key, 0)).exists();
        assertThat(getCleanFile(key, 1)).exists();
        snapshot.close();
    }
}
