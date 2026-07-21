package org.booklore.service.opds.optimization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpubZipReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readEntries_returnsAllEntriesForNormalEpub() throws IOException {
        Path epub = tempDir.resolve("ok.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub))) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zip.write("<package/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        Map<String, byte[]> entries = EpubZipReader.readEntries(epub);

        assertThat(entries).containsKeys("mimetype", "OEBPS/content.opf");
    }

    @Test
    void readEntries_rejectsDecompressionBomb() throws IOException {
        // A highly-compressible 40 MiB entry: tiny on disk, but its expansion exceeds the reader's
        // uncompressed floor (20 MiB), so it must be refused rather than buffered into the heap.
        Path bomb = tempDir.resolve("bomb.epub");
        byte[] zeros = new byte[40 * 1024 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bomb))) {
            zip.putNextEntry(new ZipEntry("big.bin"));
            zip.write(zeros);
            zip.closeEntry();
        }
        assertThat(Files.size(bomb)).isLessThan(1024 * 1024); // compressed well under the floor

        assertThatThrownBy(() -> EpubZipReader.readEntries(bomb))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("decompression size limit");
    }
}
