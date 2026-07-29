package org.booklore.service.reader;

import org.booklore.service.ByteRangeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipEntryLocatorTest {

    /** Incompressible, like real audio: forces the STORED path to be worth taking. */
    private static final String STORED_ENTRY = "OEBPS/audio/chapter1.mp3";
    /** Highly compressible text, stored deflated: exercises the inflate-and-skip fallback. */
    private static final String DEFLATED_ENTRY = "OEBPS/chapter1.xhtml";

    private ZipEntryLocator locator;
    private Path archive;
    private byte[] storedContent;
    private byte[] deflatedContent;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        locator = new ZipEntryLocator();
        archive = tempDir.resolve("test.epub");

        storedContent = new byte[64 * 1024];
        new Random(42).nextBytes(storedContent);
        deflatedContent = ("<html><body>" + "chapter text ".repeat(4000) + "</body></html>")
                .getBytes(StandardCharsets.UTF_8);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            // Padding entry first, so the entries under test do not start at offset 0 and a
            // wrong data offset cannot accidentally produce correct bytes.
            zip.setMethod(ZipOutputStream.DEFLATED);
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            ZipEntry stored = new ZipEntry(STORED_ENTRY);
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(storedContent.length);
            stored.setCompressedSize(storedContent.length);
            CRC32 crc = new CRC32();
            crc.update(storedContent);
            stored.setCrc(crc.getValue());
            zip.putNextEntry(stored);
            zip.write(storedContent);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(DEFLATED_ENTRY));
            zip.write(deflatedContent);
            zip.closeEntry();
        }
    }

    @Test
    void openEntry_storedEntry_readsFullContent() throws IOException {
        ByteRangeSource source = locator.openEntry(archive, STORED_ENTRY);

        assertEquals(storedContent.length, source.size());
        assertArrayEquals(storedContent, readRange(source, 0, source.size()));
    }

    @Test
    void openEntry_deflatedEntry_readsFullContent() throws IOException {
        ByteRangeSource source = locator.openEntry(archive, DEFLATED_ENTRY);

        assertEquals(deflatedContent.length, source.size());
        assertArrayEquals(deflatedContent, readRange(source, 0, source.size()));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 1",
            "0, 1024",
            "1, 4095",
            "1024, 512",
            "32768, 16384",
            "65535, 1"
    })
    void openEntry_storedEntry_subRangesMatchSlice(int start, int length) throws IOException {
        ByteRangeSource source = locator.openEntry(archive, STORED_ENTRY);

        assertArrayEquals(
                Arrays.copyOfRange(storedContent, start, start + length),
                readRange(source, start, length));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 16",
            "1, 4095",
            "4096, 2048",
            "30000, 1000"
    })
    void openEntry_deflatedEntry_subRangesMatchSlice(int start, int length) throws IOException {
        ByteRangeSource source = locator.openEntry(archive, DEFLATED_ENTRY);

        assertArrayEquals(
                Arrays.copyOfRange(deflatedContent, start, start + length),
                readRange(source, start, length));
    }

    @Test
    void openEntry_trailingRange_readsToEndOfEntry() throws IOException {
        ByteRangeSource source = locator.openEntry(archive, STORED_ENTRY);

        long start = storedContent.length - 128;
        assertArrayEquals(
                Arrays.copyOfRange(storedContent, (int) start, storedContent.length),
                readRange(source, start, 128));
    }

    @Test
    void openEntry_unknownEntry_throwsFileNotFound() {
        assertThrows(FileNotFoundException.class, () -> locator.openEntry(archive, "OEBPS/missing.mp3"));
    }

    @Test
    void openEntry_lastModifiedTracksArchive() throws IOException {
        ByteRangeSource source = locator.openEntry(archive, STORED_ENTRY);

        assertEquals(
                Files.getLastModifiedTime(archive).toInstant().toEpochMilli(),
                source.lastModified().toEpochMilli());
    }

    @Test
    void openEntry_rebuiltArchive_reindexesInsteadOfServingStaleOffsets() throws IOException {
        // Prime the offset cache against the original layout
        assertArrayEquals(storedContent, readRange(locator.openEntry(archive, STORED_ENTRY), 0, storedContent.length));

        byte[] replacement = new byte[8 * 1024];
        new Random(7).nextBytes(replacement);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            // Different preceding content shifts every subsequent entry's data offset
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/epub+zip padded out further".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            ZipEntry stored = new ZipEntry(STORED_ENTRY);
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(replacement.length);
            stored.setCompressedSize(replacement.length);
            CRC32 crc = new CRC32();
            crc.update(replacement);
            stored.setCrc(crc.getValue());
            zip.putNextEntry(stored);
            zip.write(replacement);
            zip.closeEntry();
        }
        // Cache invalidation keys on mtime; ensure it differs from the original write
        Files.setLastModifiedTime(archive, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(archive).toMillis() + 5000));

        ByteRangeSource source = locator.openEntry(archive, STORED_ENTRY);

        assertEquals(replacement.length, source.size());
        assertArrayEquals(replacement, readRange(source, 0, replacement.length));
    }

    private byte[] readRange(ByteRangeSource source, long start, long length) throws IOException {
        ByteArrayOutputStream out = new NonClosingByteArrayOutputStream();
        source.transferTo(start, length, out);
        return out.toByteArray();
    }

    /**
     * The stored path wraps the output in an NIO channel that propagates close; a plain
     * ByteArrayOutputStream tolerates that, but this makes the intent explicit for readers.
     */
    private static class NonClosingByteArrayOutputStream extends ByteArrayOutputStream {
        @Override
        public void close() {
            // no-op: keep the buffer readable after transfer
        }
    }
}
