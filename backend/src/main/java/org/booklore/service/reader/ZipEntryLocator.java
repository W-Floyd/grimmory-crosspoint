package org.booklore.service.reader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.booklore.service.ByteRangeSource;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;

/**
 * Resolves entries inside a ZIP container (an EPUB) to positions in the underlying file, so
 * arbitrary byte ranges can be served without decompressing everything that precedes them.
 *
 * <p>The native archive reader used elsewhere in {@link EpubReaderService} can only stream a whole
 * entry from its start, which makes seeking within embedded audio O(offset) at best. Here the
 * central directory is parsed once per EPUB and only the resulting offsets are cached; each request
 * opens its own {@link FileChannel}, so no mutable archive state is shared between threads.
 */
@Slf4j
@Component
public class ZipEntryLocator {

    private static final int MAX_CACHE_ENTRIES = 50;

    private final Cache<String, ArchiveIndex> indexCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /**
     * Where an entry's data begins in the archive and how it is stored.
     *
     * @param dataOffset      byte offset of the entry payload, past its local file header
     * @param compressedSize  stored length of the payload
     * @param size            uncompressed length; the range space the client addresses
     * @param method          {@link ZipEntry#STORED} or {@link ZipEntry#DEFLATED}
     */
    public record ZipEntryLocation(long dataOffset, long compressedSize, long size, int method) {
        boolean isStored() {
            return method == ZipEntry.STORED && dataOffset >= 0;
        }
    }

    private record ArchiveIndex(long lastModified, Map<String, ZipEntryLocation> entries) {}

    /**
     * Builds a range source for one entry of an archive.
     *
     * @throws FileNotFoundException if the archive has no such entry
     */
    public ByteRangeSource openEntry(Path archivePath, String entryName) throws IOException {
        Instant lastModified = Files.getLastModifiedTime(archivePath).toInstant();
        ZipEntryLocation location = getIndex(archivePath, lastModified.toEpochMilli()).entries().get(entryName);
        if (location == null) {
            throw new FileNotFoundException("Entry not found in archive: " + entryName);
        }
        return new ZipEntryByteRangeSource(archivePath, entryName, location, lastModified);
    }

    private ArchiveIndex getIndex(Path archivePath, long currentModified) throws IOException {
        String cacheKey = archivePath.toString();
        ArchiveIndex cached = indexCache.getIfPresent(cacheKey);
        if (cached != null && cached.lastModified() == currentModified) {
            return cached;
        }

        ArchiveIndex index = new ArchiveIndex(currentModified, readCentralDirectory(archivePath));
        indexCache.put(cacheKey, index);
        return index;
    }

    private Map<String, ZipEntryLocation> readCentralDirectory(Path archivePath) throws IOException {
        Map<String, ZipEntryLocation> locations = new HashMap<>();
        try (ZipFile zip = ZipFile.builder().setPath(archivePath).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            for (ZipArchiveEntry entry : Collections.list(entries)) {
                if (entry.isDirectory()) {
                    continue;
                }
                locations.put(entry.getName(), new ZipEntryLocation(
                        entry.getDataOffset(),
                        entry.getCompressedSize(),
                        entry.getSize(),
                        entry.getMethod()));
            }
        }
        return Collections.unmodifiableMap(locations);
    }

    /**
     * Reads a range out of a single archive entry.
     *
     * <p>Stored entries — the usual case for embedded audio, since MP3/M4A are already compressed
     * and gain nothing from deflate — are served by seeking straight to {@code dataOffset + position}
     * in the archive and using the same zero-copy transfer as a standalone file. Deflated entries
     * fall back to inflating and discarding the leading {@code position} bytes, which is correct but
     * linear in the offset.
     */
    private record ZipEntryByteRangeSource(
            Path archivePath,
            String entryName,
            ZipEntryLocation location,
            Instant lastModified
    ) implements ByteRangeSource {

        private static final int COPY_BUFFER_SIZE = 64 * 1024;

        @Override
        public long size() {
            return location.size();
        }

        @Override
        public void transferTo(long position, long count, OutputStream out) throws IOException {
            if (location.isStored()) {
                try (FileChannel channel = FileChannel.open(archivePath, StandardOpenOption.READ)) {
                    ByteRangeSource.transferFromChannel(channel, location.dataOffset() + position, count, out);
                }
                return;
            }
            inflateRange(position, count, out);
        }

        private void inflateRange(long position, long count, OutputStream out) throws IOException {
            log.debug("Serving deflated entry {} from {} via inflate-and-skip ({} bytes at offset {})",
                    entryName, archivePath, count, position);
            try (ZipFile zip = ZipFile.builder().setPath(archivePath).get()) {
                ZipArchiveEntry entry = zip.getEntry(entryName);
                if (entry == null) {
                    throw new FileNotFoundException("Entry not found in archive: " + entryName);
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    in.skipNBytes(position);
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    long remaining = count;
                    while (remaining > 0) {
                        int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) {
                            break;
                        }
                        out.write(buffer, 0, read);
                        remaining -= read;
                    }
                    out.flush();
                }
            }
        }
    }
}
