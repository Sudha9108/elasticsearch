/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.index.store;

import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.TestUtil;
import org.apache.lucene.util.Version;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.distributor.Distributor;
import org.elasticsearch.index.store.distributor.LeastUsedDistributor;
import org.elasticsearch.index.store.distributor.RandomWeightedDistributor;
import org.elasticsearch.test.ElasticsearchLuceneTestCase;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.zip.Adler32;

import static org.hamcrest.Matchers.*;

public class StoreTest extends ElasticsearchLuceneTestCase {

    @Test
    public void testVerifyingIndexOutput() throws IOException {
        Directory dir = newDirectory();
        IndexOutput output = dir.createOutput("foo.bar", IOContext.DEFAULT);
        int iters = scaledRandomIntBetween(10, 100);
        for (int i = 0; i < iters; i++) {
            BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
            output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
        }
        CodecUtil.writeFooter(output);
        output.close();
        IndexInput indexInput = dir.openInput("foo.bar", IOContext.DEFAULT);
        String checksum = Store.digestToString(CodecUtil.retrieveChecksum(indexInput));
        indexInput.seek(0);
        BytesRef ref = new BytesRef(scaledRandomIntBetween(1, 1024));
        long length = indexInput.length();
        IndexOutput verifyingOutput = new Store.LuceneVerifyingIndexOutput(new StoreFileMetaData("foo1.bar", length, checksum, TEST_VERSION_CURRENT), dir.createOutput("foo1.bar", IOContext.DEFAULT));
        while (length > 0) {
            if (random().nextInt(10) == 0) {
                verifyingOutput.writeByte(indexInput.readByte());
                length--;
            } else {
                int min = (int) Math.min(length, ref.bytes.length);
                indexInput.readBytes(ref.bytes, ref.offset, min);
                verifyingOutput.writeBytes(ref.bytes, ref.offset, min);
                length -= min;
            }
        }
        Store.verify(verifyingOutput);
        verifyingOutput.writeByte((byte) 0x0);
        try {
            Store.verify(verifyingOutput);
            fail("should be a corrupted index");
        } catch (CorruptIndexException ex) {
            // ok
        }
        IOUtils.close(indexInput, verifyingOutput, dir);
    }

    @Test
    public void testVerifyingIndexOutputWithBogusInput() throws IOException {
        Directory dir = newDirectory();
        int length = scaledRandomIntBetween(10, 1024);
        IndexOutput verifyingOutput = new Store.LuceneVerifyingIndexOutput(new StoreFileMetaData("foo1.bar", length, "", TEST_VERSION_CURRENT), dir.createOutput("foo1.bar", IOContext.DEFAULT));
        try {
            while (length > 0) {
                verifyingOutput.writeByte((byte) random().nextInt());
                length--;
            }
            fail("should be a corrupted index");
        } catch (CorruptIndexException ex) {
            // ok
        }
        IOUtils.close(verifyingOutput, dir);
    }

    @Test
    public void testWriteLegacyChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        Store store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(directoryService));
        // set default codec - all segments need checksums
        IndexWriter writer = new IndexWriter(store.directory(), newIndexWriterConfig(random(), TEST_VERSION_CURRENT, new MockAnalyzer(random())).setCodec(actualDefaultCodec()));
        int docs = 1 + random().nextInt(100);

        for (int i = 0; i < docs; i++) {
            Document doc = new Document();
            doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            writer.addDocument(doc);
        }
        if (random().nextBoolean()) {
            for (int i = 0; i < docs; i++) {
                if (random().nextBoolean()) {
                    Document doc = new Document();
                    doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    writer.updateDocument(new Term("id", "" + i), doc);
                }
            }
        }
        if (random().nextBoolean()) {
            DirectoryReader.open(writer, random().nextBoolean()).close(); // flush
        }
        Store.MetadataSnapshot metadata;
        // check before we committed
        try {
            store.getMetadata();
            fail("no index present - expected exception");
        } catch (IndexNotFoundException ex) {
            // expected
        }
        assertThat(store.getMetadataOrEmpty(), is(Store.MetadataSnapshot.EMPTY)); // nothing committed

        writer.close();
        Store.LegacyChecksums checksums = new Store.LegacyChecksums();
        Map<String, StoreFileMetaData> legacyMeta = new HashMap<>();
        for (String file : store.directory().listAll()) {
            if (file.equals("write.lock") || file.equals(IndexFileNames.SEGMENTS_GEN)) {
                continue;
            }
            try (IndexInput input = store.directory().openInput(file, IOContext.READONCE)) {
                String checksum = Store.digestToString(CodecUtil.retrieveChecksum(input));
                StoreFileMetaData storeFileMetaData = new StoreFileMetaData(file, store.directory().fileLength(file), checksum, null);
                legacyMeta.put(file, storeFileMetaData);
                checksums.add(storeFileMetaData);

            }

        }
        checksums.write(store);

        metadata = store.getMetadata();
        Map<String, StoreFileMetaData> stringStoreFileMetaDataMap = metadata.asMap();
        assertThat(legacyMeta.size(), equalTo(stringStoreFileMetaDataMap.size()));
        for (StoreFileMetaData meta : legacyMeta.values()) {
            assertTrue(stringStoreFileMetaDataMap.containsKey(meta.name()));
            assertTrue(stringStoreFileMetaDataMap.get(meta.name()).isSame(meta));
        }
        assertDeleteContent(store, directoryService);
        IOUtils.close(store);

    }

    @Test
    public void testNewChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        Store store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(directoryService));
        // set default codec - all segments need checksums
        IndexWriter writer = new IndexWriter(store.directory(), newIndexWriterConfig(random(), TEST_VERSION_CURRENT, new MockAnalyzer(random())).setCodec(actualDefaultCodec()));
        int docs = 1 + random().nextInt(100);

        for (int i = 0; i < docs; i++) {
            Document doc = new Document();
            doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            writer.addDocument(doc);
        }
        if (random().nextBoolean()) {
            for (int i = 0; i < docs; i++) {
                if (random().nextBoolean()) {
                    Document doc = new Document();
                    doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    writer.updateDocument(new Term("id", "" + i), doc);
                }
            }
        }
        if (random().nextBoolean()) {
            DirectoryReader.open(writer, random().nextBoolean()).close(); // flush
        }
        Store.MetadataSnapshot metadata;
        // check before we committed
        try {
            store.getMetadata();
            fail("no index present - expected exception");
        } catch (IndexNotFoundException ex) {
            // expected
        }
        assertThat(store.getMetadataOrEmpty(), is(Store.MetadataSnapshot.EMPTY)); // nothing committed
        writer.commit();
        writer.close();
        metadata = store.getMetadata();
        assertThat(metadata.asMap().isEmpty(), is(false));
        for (StoreFileMetaData meta : metadata) {
            try (IndexInput input = store.directory().openInput(meta.name(), IOContext.DEFAULT)) {
                String checksum = Store.digestToString(CodecUtil.retrieveChecksum(input));
                assertThat("File: " + meta.name() + " has a different checksum", meta.checksum(), equalTo(checksum));
                assertThat(meta.hasLegacyChecksum(), equalTo(false));
                assertThat(meta.writtenBy(), equalTo(TEST_VERSION_CURRENT));
                if (meta.name().endsWith(".si") || meta.name().startsWith("segments_")) {
                    assertThat(meta.hash().length, greaterThan(0));
                }
            }
        }
        assertConsistent(store, metadata);

        TestUtil.checkIndex(store.directory());
        assertDeleteContent(store, directoryService);
        IOUtils.close(store);
    }

    @Test
    public void testMixedChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        Store store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(directoryService));
        // this time random codec....
        IndexWriter writer = new IndexWriter(store.directory(), newIndexWriterConfig(random(), TEST_VERSION_CURRENT, new MockAnalyzer(random())).setCodec(actualDefaultCodec()));
        int docs = 1 + random().nextInt(100);

        for (int i = 0; i < docs; i++) {
            Document doc = new Document();
            doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            writer.addDocument(doc);
        }
        if (random().nextBoolean()) {
            for (int i = 0; i < docs; i++) {
                if (random().nextBoolean()) {
                    Document doc = new Document();
                    doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    writer.updateDocument(new Term("id", "" + i), doc);
                }
            }
        }
        if (random().nextBoolean()) {
            DirectoryReader.open(writer, random().nextBoolean()).close(); // flush
        }
        Store.MetadataSnapshot metadata;
        // check before we committed
        try {
            store.getMetadata();
            fail("no index present - expected exception");
        } catch (IndexNotFoundException ex) {
            // expected
        }
        assertThat(store.getMetadataOrEmpty(), is(Store.MetadataSnapshot.EMPTY)); // nothing committed
        writer.commit();
        writer.close();
        Store.LegacyChecksums checksums = new Store.LegacyChecksums();
        metadata = store.getMetadata();
        assertThat(metadata.asMap().isEmpty(), is(false));
        for (StoreFileMetaData meta : metadata) {
            try (IndexInput input = store.directory().openInput(meta.name(), IOContext.DEFAULT)) {
                if (meta.checksum() == null) {
                    String checksum = null;
                    try {
                        CodecUtil.retrieveChecksum(input);
                        fail("expected a corrupt index - posting format has not checksums");
                    } catch (CorruptIndexException ex) {
                        try (ChecksumIndexInput checksumIndexInput = store.directory().openChecksumInput(meta.name(), IOContext.DEFAULT)) {
                            checksumIndexInput.seek(meta.length());
                            checksum = Store.digestToString(checksumIndexInput.getChecksum());
                        }
                        // fine - it's a postings format without checksums
                        checksums.add(new StoreFileMetaData(meta.name(), meta.length(), checksum, null));
                    }
                } else {
                    String checksum = Store.digestToString(CodecUtil.retrieveChecksum(input));
                    assertThat("File: " + meta.name() + " has a different checksum", meta.checksum(), equalTo(checksum));
                    assertThat(meta.hasLegacyChecksum(), equalTo(false));
                    assertThat(meta.writtenBy(), equalTo(TEST_VERSION_CURRENT));
                }
            }
        }
        assertConsistent(store, metadata);
        checksums.write(store);
        metadata = store.getMetadata();
        assertThat(metadata.asMap().isEmpty(), is(false));
        for (StoreFileMetaData meta : metadata) {
            assertThat("file: " + meta.name() + " has a null checksum", meta.checksum(), not(nullValue()));
            if (meta.hasLegacyChecksum()) {
                try (ChecksumIndexInput checksumIndexInput = store.directory().openChecksumInput(meta.name(), IOContext.DEFAULT)) {
                    checksumIndexInput.seek(meta.length());
                    assertThat(meta.checksum(), equalTo(Store.digestToString(checksumIndexInput.getChecksum())));
                }
            } else {
                try (IndexInput input = store.directory().openInput(meta.name(), IOContext.DEFAULT)) {
                    String checksum = Store.digestToString(CodecUtil.retrieveChecksum(input));
                    assertThat("File: " + meta.name() + " has a different checksum", meta.checksum(), equalTo(checksum));
                    assertThat(meta.hasLegacyChecksum(), equalTo(false));
                    assertThat(meta.writtenBy(), equalTo(TEST_VERSION_CURRENT));
                }
            }
        }
        assertConsistent(store, metadata);
        TestUtil.checkIndex(store.directory());
        assertDeleteContent(store, directoryService);
        IOUtils.close(store);
    }

    // Test cases with incorrect adler32 in their metadata caused by old versions.

    @Test
    public void testBuggyTIIChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random(), false);
        Store store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(directoryService));

        // .tii: no version specified
        StoreFileMetaData tii = new StoreFileMetaData("foo.tii", 20, "boguschecksum", null);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp", tii, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
        }
        
        // .tii: old version
        tii = new StoreFileMetaData("foo.tii", 20, "boguschecksum", Version.LUCENE_36);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp2", tii, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
        }
        store.close();
    }

    @Test
    public void testBuggyTISChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random(), false);
        Store store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(directoryService));

        // .tis: no version specified
        StoreFileMetaData tis = new StoreFileMetaData("foo.tis", 20, "boguschecksum", null);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp", tis, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
        };

        // .tis: old version
        tis = new StoreFileMetaData("foo.tis", 20, "boguschecksum", Version.LUCENE_36);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp", tis, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
        };

        store.close();
    }

    @Test
    public void testBuggyCFSChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random(), false);
        Store store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(directoryService));

        // .cfs: unspecified version
        StoreFileMetaData cfs = new StoreFileMetaData("foo.cfs", 20, "boguschecksum", null);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp", cfs, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
        }

        // .cfs: ancient affected version
        cfs = new StoreFileMetaData("foo.cfs", 20, "boguschecksum", Version.LUCENE_33);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp2", cfs, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
        }

        // .cfs: should still be checksummed for an ok version
        cfs = new StoreFileMetaData("foo.cfs", 20, "boguschecksum", Version.LUCENE_34);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp3", cfs, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
            fail("should have gotten expected exception");
        } catch (CorruptIndexException expected) {
            assertTrue(expected.getMessage().startsWith("checksum failed"));
        }

        store.close();
    }

    @Test
    public void testSegmentsNChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random(), false);
        Store store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(directoryService));

        // segments_N: unspecified version
        StoreFileMetaData segments = new StoreFileMetaData("segments_1", 20, "boguschecksum", null);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp", segments, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
        }

        // segments_N: specified old version
        segments = new StoreFileMetaData("segments_2", 20, "boguschecksum", Version.LUCENE_33);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp2", segments, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
        }

        // segments_N: should still be checksummed for an ok version (lucene checksum)
        segments = new StoreFileMetaData("segments_3", 20, "boguschecksum", Version.LUCENE_48);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp3", segments, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
            fail("should have gotten expected exception");
        } catch (CorruptIndexException expected) {
            assertTrue(expected.getMessage().startsWith("checksum failed"));
        }

        store.close();
    }

    @Test
    public void testSegmentsGenChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random(), false);
        Store store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(directoryService));

        // segments.gen: unspecified version
        StoreFileMetaData segmentsGen = new StoreFileMetaData("segments.gen", 20, "boguschecksum", null);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp", segmentsGen, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
        }

        // segments.gen: specified old version
        segmentsGen = new StoreFileMetaData("segments.gen", 20, "boguschecksum", Version.LUCENE_33);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp2", segmentsGen, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
        }

        // segments.gen: should still be checksummed for an ok version (lucene checksum)
        segmentsGen = new StoreFileMetaData("segments.gen", 20, "boguschecksum", Version.LUCENE_48);
        try (VerifyingIndexOutput output = (VerifyingIndexOutput) store.createVerifyingOutput("foo.temp3", segmentsGen, IOContext.DEFAULT)) {
            output.writeBytes(new byte[20], 20);
            output.verify();
            fail("should have gotten expected exception");
        } catch (CorruptIndexException expected) {
            assertTrue(expected.getMessage().startsWith("checksum failed"));
        }

        store.close();
    }

    @Test
    public void testRenameFile() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random(), false);
        Store store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(directoryService));
        {
            IndexOutput output = store.directory().createOutput("foo.bar", IOContext.DEFAULT);
            int iters = scaledRandomIntBetween(10, 100);
            for (int i = 0; i < iters; i++) {
                BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
                output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
            }
            CodecUtil.writeFooter(output);
            output.close();
        }
        store.renameFile("foo.bar", "bar.foo");
        assertThat(store.directory().listAll().length, is(1));
        final long lastChecksum;
        try (IndexInput input = store.directory().openInput("bar.foo", IOContext.DEFAULT)) {
            lastChecksum = CodecUtil.checksumEntireFile(input);
        }

        try {
            store.directory().openInput("foo.bar", IOContext.DEFAULT);
            fail("file was renamed");
        } catch (FileNotFoundException | NoSuchFileException ex) {
            // expected
        }
        {
            IndexOutput output = store.directory().createOutput("foo.bar", IOContext.DEFAULT);
            int iters = scaledRandomIntBetween(10, 100);
            for (int i = 0; i < iters; i++) {
                BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
                output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
            }
            CodecUtil.writeFooter(output);
            output.close();
        }
        try {
            store.renameFile("foo.bar", "bar.foo");
            fail("targe file already exists");
        } catch (IOException ex) {
            // expected
        }

        try (IndexInput input = store.directory().openInput("bar.foo", IOContext.DEFAULT)) {
            assertThat(lastChecksum, equalTo(CodecUtil.checksumEntireFile(input)));
        }
        assertThat(store.directory().listAll().length, is(2));
        assertDeleteContent(store, directoryService);
        IOUtils.close(store);
    }

    public void testCheckIntegrity() throws IOException {
        Directory dir = newDirectory();
        long luceneFileLength = 0;

        try (IndexOutput output = dir.createOutput("lucene_checksum.bin", IOContext.DEFAULT)) {
            int iters = scaledRandomIntBetween(10, 100);
            for (int i = 0; i < iters; i++) {
                BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
                output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
                luceneFileLength += bytesRef.length;
            }
            CodecUtil.writeFooter(output);
            luceneFileLength += CodecUtil.footerLength();

        }

        final Adler32 adler32 = new Adler32();
        long legacyFileLength = 0;
        try (IndexOutput output = dir.createOutput("legacy.bin", IOContext.DEFAULT)) {
            int iters = scaledRandomIntBetween(10, 100);
            for (int i = 0; i < iters; i++) {
                BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
                output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
                adler32.update(bytesRef.bytes, bytesRef.offset, bytesRef.length);
                legacyFileLength += bytesRef.length;
            }
        }
        final long luceneChecksum;
        final long adler32LegacyChecksum = adler32.getValue();
        try(IndexInput indexInput = dir.openInput("lucene_checksum.bin", IOContext.DEFAULT)) {
            assertEquals(luceneFileLength, indexInput.length());
            luceneChecksum = CodecUtil.retrieveChecksum(indexInput);
        }

        { // positive check
            StoreFileMetaData lucene = new StoreFileMetaData("lucene_checksum.bin", luceneFileLength, Store.digestToString(luceneChecksum), Version.LUCENE_48);
            StoreFileMetaData legacy = new StoreFileMetaData("legacy.bin", legacyFileLength, Store.digestToString(adler32LegacyChecksum));
            assertTrue(legacy.hasLegacyChecksum());
            assertFalse(lucene.hasLegacyChecksum());
            assertTrue(Store.checkIntegrity(lucene, dir));
            assertTrue(Store.checkIntegrity(legacy, dir));
        }

        { // negative check - wrong checksum
            StoreFileMetaData lucene = new StoreFileMetaData("lucene_checksum.bin", luceneFileLength, Store.digestToString(luceneChecksum+1), Version.LUCENE_48);
            StoreFileMetaData legacy = new StoreFileMetaData("legacy.bin", legacyFileLength, Store.digestToString(adler32LegacyChecksum+1));
            assertTrue(legacy.hasLegacyChecksum());
            assertFalse(lucene.hasLegacyChecksum());
            assertFalse(Store.checkIntegrity(lucene, dir));
            assertFalse(Store.checkIntegrity(legacy, dir));
        }

        { // negative check - wrong length
            StoreFileMetaData lucene = new StoreFileMetaData("lucene_checksum.bin", luceneFileLength+1, Store.digestToString(luceneChecksum), Version.LUCENE_48);
            StoreFileMetaData legacy = new StoreFileMetaData("legacy.bin", legacyFileLength+1, Store.digestToString(adler32LegacyChecksum));
            assertTrue(legacy.hasLegacyChecksum());
            assertFalse(lucene.hasLegacyChecksum());
            assertFalse(Store.checkIntegrity(lucene, dir));
            assertFalse(Store.checkIntegrity(legacy, dir));
        }

        { // negative check - wrong file
            StoreFileMetaData lucene = new StoreFileMetaData("legacy.bin", luceneFileLength, Store.digestToString(luceneChecksum), Version.LUCENE_48);
            StoreFileMetaData legacy = new StoreFileMetaData("lucene_checksum.bin", legacyFileLength, Store.digestToString(adler32LegacyChecksum));
            assertTrue(legacy.hasLegacyChecksum());
            assertFalse(lucene.hasLegacyChecksum());
            assertFalse(Store.checkIntegrity(lucene, dir));
            assertFalse(Store.checkIntegrity(legacy, dir));
        }
        dir.close();

    }

    public void assertDeleteContent(Store store,DirectoryService service) throws IOException {
        store.deleteContent();
        assertThat(Arrays.toString(store.directory().listAll()), store.directory().listAll().length, equalTo(0));
        assertThat(store.stats().sizeInBytes(), equalTo(0l));
        for (Directory dir : service.build()) {
            assertThat(dir.listAll().length, equalTo(0));
        }
    }

    private static final class LuceneManagedDirectoryService implements DirectoryService {
        private final Directory[] dirs;
        private final Random random;

        public LuceneManagedDirectoryService(Random random) {
            this(random, true);
        }
        public LuceneManagedDirectoryService(Random random, boolean preventDoubleWrite) {
            this.dirs = new Directory[1 + random.nextInt(5)];
            for (int i = 0; i < dirs.length; i++) {
                dirs[i]  = newDirectory(random);
                if (dirs[i] instanceof MockDirectoryWrapper) {
                    ((MockDirectoryWrapper)dirs[i]).setPreventDoubleWrite(preventDoubleWrite);
                }
            }
            this.random = random;
        }
        @Override
        public Directory[] build() throws IOException {
            return dirs;
        }

        @Override
        public long throttleTimeInNanos() {
            return random.nextInt(1000);
        }

        @Override
        public void renameFile(Directory dir, String from, String to) throws IOException {
            dir.copy(dir, from, to, IOContext.DEFAULT);
            dir.deleteFile(from);
        }

        @Override
        public void fullDelete(Directory dir) throws IOException {
            for (String file : dir.listAll()) {
                dir.deleteFile(file);
            }
        }
    }

    public static void assertConsistent(Store store, Store.MetadataSnapshot metadata) throws IOException {
        for (String file : store.directory().listAll()) {
            if (!"write.lock".equals(file) && !IndexFileNames.SEGMENTS_GEN.equals(file) && !Store.isChecksum(file)) {
                assertTrue(file + " is not in the map: " + metadata.asMap().size() + " vs. " + store.directory().listAll().length, metadata.asMap().containsKey(file));
            } else {
                assertFalse(file + " is not in the map: " + metadata.asMap().size() + " vs. " + store.directory().listAll().length, metadata.asMap().containsKey(file));
            }
        }
    }
    private Distributor randomDistributor(DirectoryService service) throws IOException {
        return randomDistributor(random(), service);
    }

    private Distributor randomDistributor(Random random, DirectoryService service) throws IOException {
        return random.nextBoolean() ? new LeastUsedDistributor(service) : new RandomWeightedDistributor(service);
    }


    @Test
    public void testRecoveryDiff() throws IOException, InterruptedException {
        int numDocs = 2 + random().nextInt(100);
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < numDocs; i++) {
            Document doc = new Document();
            doc.add(new StringField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            docs.add(doc);
        }
        long seed = random().nextLong();
        Store.MetadataSnapshot first;
        {
            Random random = new Random(seed);
            IndexWriterConfig iwc = new IndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random)).setCodec(actualDefaultCodec());
            iwc.setMergePolicy(NoMergePolicy.INSTANCE);
            iwc.setUseCompoundFile(random.nextBoolean());
            iwc.setMaxThreadStates(1);
            final ShardId shardId = new ShardId(new Index("index"), 1);
            DirectoryService directoryService = new LuceneManagedDirectoryService(random);
            Store store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(random, directoryService));
            IndexWriter writer = new IndexWriter(store.directory(), iwc);
            final boolean lotsOfSegments = rarely(random);
            for (Document d : docs) {
                writer.addDocument(d);
                if (lotsOfSegments && random.nextBoolean()) {
                    writer.commit();
                } else if (rarely(random)) {
                    writer.commit();
                }
            }
            writer.close();
            first = store.getMetadata();
            assertDeleteContent(store, directoryService);
            store.close();
        }
        long time = new Date().getTime();
        while(time == new Date().getTime()) {
            Thread.sleep(10); // bump the time
        }
        Store.MetadataSnapshot second;
        Store store;
        {
            Random random = new Random(seed);
            IndexWriterConfig iwc = new IndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random)).setCodec(actualDefaultCodec());
            iwc.setMergePolicy(NoMergePolicy.INSTANCE);
            iwc.setUseCompoundFile(random.nextBoolean());
            iwc.setMaxThreadStates(1);
            final ShardId shardId = new ShardId(new Index("index"), 1);
            DirectoryService directoryService = new LuceneManagedDirectoryService(random);
            store = new Store(shardId, ImmutableSettings.EMPTY, null, null, directoryService, randomDistributor(random, directoryService));
            IndexWriter writer = new IndexWriter(store.directory(), iwc);
            final boolean lotsOfSegments = rarely(random);
            for (Document d : docs) {
                writer.addDocument(d);
                if (lotsOfSegments && random.nextBoolean()) {
                    writer.commit();
                } else if (rarely(random)) {
                    writer.commit();
                }
            }
            writer.close();
            second = store.getMetadata();
        }
        Store.RecoveryDiff diff = first.recoveryDiff(second);
        assertThat(first.size(), equalTo(second.size()));
        for (StoreFileMetaData md : first) {
            assertThat(second.get(md.name()), notNullValue());
            // si files are different - containing timestamps etc
            assertThat(second.get(md.name()).isSame(md), equalTo(md.name().endsWith(".si") == false));
        }
        assertThat(diff.different.size(), equalTo(first.size()-1));
        assertThat(diff.identical.size(), equalTo(1)); // commit point is identical
        assertThat(diff.missing, empty());

        // check the self diff
        Store.RecoveryDiff selfDiff = first.recoveryDiff(first);
        assertThat(selfDiff.identical.size(), equalTo(first.size()));
        assertThat(selfDiff.different, empty());
        assertThat(selfDiff.missing, empty());


        // lets add some deletes
        Random random = new Random(seed);
        IndexWriterConfig iwc = new IndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random)).setCodec(actualDefaultCodec());
        iwc.setMergePolicy(NoMergePolicy.INSTANCE);
        iwc.setUseCompoundFile(random.nextBoolean());
        iwc.setMaxThreadStates(1);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
        IndexWriter writer = new IndexWriter(store.directory(), iwc);
        writer.deleteDocuments(new Term("id", Integer.toString(random().nextInt(numDocs))));
        writer.close();
        Store.MetadataSnapshot metadata = store.getMetadata();
        StoreFileMetaData delFile = null;
        for (StoreFileMetaData md : metadata) {
            if (md.name().endsWith(".del")) {
                delFile = md;
                break;
            }
        }
        Store.RecoveryDiff afterDeleteDiff = metadata.recoveryDiff(second);
        if (delFile != null) {
            assertThat(afterDeleteDiff.identical.size(), equalTo(metadata.size()-2)); // segments_N + del file
            assertThat(afterDeleteDiff.different.size(), equalTo(0));
            assertThat(afterDeleteDiff.missing.size(), equalTo(2));
        } else {
            // an entire segment must be missing (single doc segment got dropped)
            assertThat(afterDeleteDiff.identical.size(), greaterThan(0));
            assertThat(afterDeleteDiff.different.size(), equalTo(0));
            assertThat(afterDeleteDiff.missing.size(), equalTo(1)); // the commit file is different
        }

        // check the self diff
        selfDiff = metadata.recoveryDiff(metadata);
        assertThat(selfDiff.identical.size(), equalTo(metadata.size()));
        assertThat(selfDiff.different, empty());
        assertThat(selfDiff.missing, empty());

        // add a new commit
        iwc = new IndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random)).setCodec(actualDefaultCodec());
        iwc.setMergePolicy(NoMergePolicy.INSTANCE);
        iwc.setUseCompoundFile(true); // force CFS - easier to test here since we know it will add 3 files
        iwc.setMaxThreadStates(1);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
        writer = new IndexWriter(store.directory(), iwc);
        writer.addDocument(docs.get(0));
        writer.close();

        Store.MetadataSnapshot newCommitMetaData = store.getMetadata();
        Store.RecoveryDiff newCommitDiff = newCommitMetaData.recoveryDiff(metadata);
        if (delFile != null) {
            assertThat(newCommitDiff.identical.size(), equalTo(newCommitMetaData.size()-5)); // segments_N, del file, cfs, cfe, si for the new segment
            assertThat(newCommitDiff.different.size(), equalTo(1)); // the del file must be different
            assertThat(newCommitDiff.different.get(0).name(), endsWith(".del"));
            assertThat(newCommitDiff.missing.size(), equalTo(4)); // segments_N,cfs, cfe, si for the new segment
        } else {
            assertThat(newCommitDiff.identical.size(), equalTo(newCommitMetaData.size() - 4)); // segments_N, cfs, cfe, si for the new segment
            assertThat(newCommitDiff.different.size(), equalTo(0));
            assertThat(newCommitDiff.missing.size(), equalTo(4)); // an entire segment must be missing (single doc segment got dropped)  plus the commit is different
        }

        store.deleteContent();
        IOUtils.close(store);
    }
}
