package org.booklore.service.opds.optimization;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads every entry of an EPUB (zip) fully into memory, keyed by entry name in physical order — the
 * form the device optimizer and cover replacer need so their rename maps are complete before OPF/XHTML
 * references are rewritten.
 *
 * <p>Guards against decompression ("zip") bombs: the callers only bound the <em>compressed</em> source
 * size, which a bomb passes trivially (a few KB expanding to gigabytes). This reader caps the total
 * <em>uncompressed</em> bytes it will hold — a ratio of the compressed size (so legitimately large
 * books scale up), floored for tiny archives and hard-capped in absolute terms — and the number of
 * entries, throwing {@link IOException} once either is exceeded rather than exhausting the heap. The
 * declared per-entry size is not trusted (a bomb lies about it): the limit is enforced on bytes
 * actually read.
 */
final class EpubZipReader {

    /** Max ratio of total uncompressed size to the compressed source size. Legit EPUBs sit well under this. */
    private static final long MAX_EXPANSION_RATIO = 100;
    /** Floor so tiny archives with modest legitimate expansion aren't rejected. */
    private static final long MIN_TOTAL_BYTES = 20L * 1024 * 1024;   // 20 MiB
    /** Absolute ceiling on in-memory uncompressed bytes regardless of source size. */
    private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024; // 1 GiB
    /** Cap on entry count to bound many-tiny-entries bombs. */
    private static final int MAX_ENTRIES = 50_000;

    private EpubZipReader() {
    }

    /** Read all (non-directory) entries into a name→bytes map, enforcing the decompression bounds. */
    static Map<String, byte[]> readEntries(Path sourceEpub) throws IOException {
        long compressed = Files.size(sourceEpub);
        long budget = Math.min(MAX_TOTAL_BYTES, Math.max(MIN_TOTAL_BYTES, compressed * MAX_EXPANSION_RATIO));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipFile zip = ZipFile.builder().setPath(sourceEpub).get()) {
            Enumeration<ZipArchiveEntry> e = zip.getEntriesInPhysicalOrder();
            while (e.hasMoreElements()) {
                ZipArchiveEntry entry = e.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw new IOException("EPUB has too many entries (> " + MAX_ENTRIES + "); refusing to optimize");
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = readBounded(in, budget - total);
                    total += bytes.length;
                    entries.put(entry.getName(), bytes);
                }
            }
        }
        return entries;
    }

    /**
     * Read a stream fully but refuse to buffer more than {@code limit} bytes, throwing rather than
     * growing the buffer unbounded. Uses {@code limit + 1} as the read ceiling so overflow is detected.
     */
    private static byte[] readBounded(InputStream in, long limit) throws IOException {
        if (limit < 0) {
            throw new IOException("EPUB exceeds the decompression size limit; refusing to optimize");
        }
        long ceiling = Math.min(limit + 1, Integer.MAX_VALUE);
        byte[] bytes = in.readNBytes((int) ceiling);
        if (bytes.length > limit) {
            throw new IOException("EPUB exceeds the decompression size limit; refusing to optimize");
        }
        return bytes;
    }
}
