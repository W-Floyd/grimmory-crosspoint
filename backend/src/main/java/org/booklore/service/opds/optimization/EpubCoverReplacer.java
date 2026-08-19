package org.booklore.service.opds.optimization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.util.epub.CoverDetectorService;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/**
 * Rebuilds an EPUB with its embedded cover image replaced by BookLore's metadata cover, keeping
 * every other entry verbatim. Used to serve OPDS EPUBs that show the library's (often corrected /
 * higher-quality) cover instead of the publisher's embedded one.
 *
 * <p>The metadata cover (a JPEG) is inserted as-is — never re-encoded. If the cover entry is
 * already a {@code .jpg}/{@code .jpeg} its bytes are simply swapped in place (no OPF/XHTML change);
 * for any other format (png, webp, ...) the entry is renamed to {@code .jpg} and the OPF manifest
 * item plus any XHTML/SVG references to it are rewritten. If the cover entry can't be located or
 * anything else goes wrong, no target is written and the caller serves the original EPUB unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EpubCoverReplacer {

    private final CoverDetectorService coverDetectorService;

    private static final String MIMETYPE_ENTRY = "mimetype";
    private static final String CONTAINER_ENTRY = "META-INF/container.xml";

    /**
     * Write a copy of {@code sourceEpub} to {@code target} with the embedded cover replaced by
     * {@code coverBytes}. The cover is inserted verbatim under an entry whose extension matches
     * {@code coverExtension} (the metadata cover file's own extension, e.g. {@code jpg}); the bytes
     * are trusted to match that extension. Returns {@code true} when a replacement was made (and
     * {@code target} written), {@code false} otherwise (nothing written; serve the original).
     */
    public boolean replaceCover(Path sourceEpub, byte[] coverBytes, String coverExtension, Path target) {
        if (coverBytes == null || coverBytes.length == 0) {
            return false;
        }
        String targetExt = normalizeExtension(coverExtension);
        try {
            Map<String, byte[]> entries = readEntries(sourceEpub);

            String opfPath = findOpfPath(entries);
            if (opfPath == null) {
                return false;
            }
            String coverEntry = findCoverEntry(entries, sourceEpub);
            if (coverEntry == null || !entries.containsKey(coverEntry)) {
                log.debug("No cover entry resolved for EPUB {}; leaving cover unchanged", sourceEpub.getFileName());
                return false;
            }

            String entryExt = extension(coverEntry);
            if (entryExt.equals(targetExt) || (isJpegExt(entryExt) && isJpegExt(targetExt))) {
                // Cover entry already uses the metadata cover's format: drop the bytes in as-is.
                entries.put(coverEntry, coverBytes);
            } else {
                // Different format (e.g. metadata cover is jpg, entry is png/webp): re-point the
                // entry to the cover's extension + media-type and rewrite OPF/XHTML references.
                convertCover(entries, opfPath, coverEntry, entryExt, targetExt, coverBytes);
            }

            writeEpub(entries, target);
            return true;
        } catch (Exception e) {
            log.warn("Failed to replace cover for {}; serving original: {}", sourceEpub.getFileName(), e.getMessage());
            return false;
        }
    }

    private Map<String, byte[]> readEntries(Path sourceEpub) throws IOException {
        // Bounded read (name→bytes, physical order) with a zip-bomb guard; see EpubZipReader.
        return EpubZipReader.readEntries(sourceEpub);
    }

    /** Locate the OPF package document via META-INF/container.xml, falling back to any *.opf entry. */
    private String findOpfPath(Map<String, byte[]> entries) {
        byte[] container = entries.get(CONTAINER_ENTRY);
        if (container != null) {
            try {
                Document doc = Jsoup.parse(new String(container, StandardCharsets.UTF_8), "", Parser.xmlParser());
                Element rootfile = doc.getElementsByTag("rootfile").first();
                if (rootfile != null) {
                    String full = rootfile.attr("full-path");
                    if (!full.isBlank() && entries.containsKey(full)) {
                        return full;
                    }
                }
            } catch (Exception e) {
                log.debug("container.xml parse failed: {}", e.getMessage());
            }
        }
        return entries.keySet().stream().filter(p -> p.toLowerCase(Locale.ROOT).endsWith(".opf")).findFirst().orElse(null);
    }

    /**
     * Resolve the zip path of the cover image entry, delegating to the shared
     * {@link CoverDetectorService}. Its strict variant is used deliberately: it stops once the
     * declared-cover and id/filename conventions are exhausted rather than inferring a cover from
     * content (largest image, first spine image), because guessing wrong here would swap an
     * unrelated illustration for the book's cover in the file the reader downloads. When nothing
     * declares a cover we leave the EPUB alone instead.
     */
    private String findCoverEntry(Map<String, byte[]> entries, Path sourceEpub) {
        String detected = coverDetectorService.detectDeclaredCoverImagePath(sourceEpub);
        if (detected == null || detected.isBlank()) {
            return null;
        }
        if (entries.containsKey(detected)) {
            return detected;
        }
        // epub4j hands back the href as written in the OPF; a percent-encoded one (spaces, accents)
        // will not match the raw zip entry name until it is decoded.
        String decoded = decode(detected);
        return entries.containsKey(decoded) ? decoded : null;
    }

    /**
     * Re-point a cover entry whose format differs from the metadata cover: rename the zip entry to
     * {@code newExt} holding the cover bytes as-is, then rewrite the OPF manifest item (href +
     * media-type) and any XHTML {@code src}/ SVG {@code image} references that resolve to it.
     */
    private void convertCover(Map<String, byte[]> entries, String opfPath, String coverEntry,
                              String oldExt, String newExt, byte[] coverBytes) {
        String newEntry = swapExt(coverEntry, oldExt, newExt);
        entries.remove(coverEntry);
        entries.put(newEntry, coverBytes);

        String mediaType = mediaTypeForExtension(newExt);
        rewriteOpfReferences(entries, opfPath, coverEntry, oldExt, newExt, mediaType);

        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) {
                rewriteXhtmlReferences(entries, entry.getKey(), coverEntry, oldExt, newExt);
            }
        }
    }

    private void rewriteOpfReferences(Map<String, byte[]> entries, String opfPath, String coverEntry,
                                      String oldExt, String newExt, String mediaType) {
        try {
            Document opf = Jsoup.parse(new String(entries.get(opfPath), StandardCharsets.UTF_8), "", Parser.xmlParser());
            opf.outputSettings().prettyPrint(false);
            String baseDir = parentDir(opfPath);
            boolean modified = false;

            for (Element item : opf.getElementsByTag("item")) {
                if (referencesCover(item.attr("href"), baseDir, coverEntry)) {
                    item.attr("href", swapExt(item.attr("href"), oldExt, newExt));
                    item.attr("media-type", mediaType);
                    modified = true;
                }
            }
            for (Element ref : opf.getElementsByTag("reference")) {
                if (referencesCover(ref.attr("href"), baseDir, coverEntry)) {
                    ref.attr("href", swapExt(ref.attr("href"), oldExt, newExt));
                    modified = true;
                }
            }
            if (modified) {
                entries.put(opfPath, opf.outerHtml().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.debug("OPF cover-ref rewrite failed for {}: {}", opfPath, e.getMessage());
        }
    }

    private void rewriteXhtmlReferences(Map<String, byte[]> entries, String xhtmlPath, String coverEntry,
                                        String oldExt, String newExt) {
        try {
            Document doc = Jsoup.parse(new String(entries.get(xhtmlPath), StandardCharsets.UTF_8), "", Parser.xmlParser());
            doc.outputSettings().prettyPrint(false);
            String baseDir = parentDir(xhtmlPath);
            boolean modified = false;

            for (Element img : doc.getElementsByTag("img")) {
                if (referencesCover(img.attr("src"), baseDir, coverEntry)) {
                    img.attr("src", swapExt(img.attr("src"), oldExt, newExt));
                    modified = true;
                }
            }
            for (Element image : doc.getElementsByTag("image")) {
                for (String attr : new String[]{"xlink:href", "href"}) {
                    if (referencesCover(image.attr(attr), baseDir, coverEntry)) {
                        image.attr(attr, swapExt(image.attr(attr), oldExt, newExt));
                        modified = true;
                    }
                }
            }
            if (modified) {
                entries.put(xhtmlPath, doc.outerHtml().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.debug("XHTML cover-ref rewrite failed for {}: {}", xhtmlPath, e.getMessage());
        }
    }

    private static boolean referencesCover(String ref, String baseDir, String coverEntry) {
        return ref != null && !ref.isBlank() && coverEntry.equals(resolveZipPath(baseDir, decode(ref)));
    }

    /** Replace a trailing {@code .<oldExt>} (case-insensitive) with {@code .<newExt>}. */
    private static String swapExt(String path, String oldExt, String newExt) {
        return path.replaceAll("(?i)\\." + Pattern.quote(oldExt) + "$", "." + newExt);
    }

    private static boolean isJpegExt(String ext) {
        return ext.equals("jpg") || ext.equals("jpeg");
    }

    private static String normalizeExtension(String ext) {
        if (ext == null || ext.isBlank()) {
            return "jpg";
        }
        String e = ext.trim().toLowerCase(Locale.ROOT);
        return e.startsWith(".") ? e.substring(1) : e;
    }

    private static String mediaTypeForExtension(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> "image/jpeg"; // jpg / jpeg / unknown
        };
    }

    private void writeEpub(Map<String, byte[]> entries, Path target) throws IOException {
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(Files.newOutputStream(target))) {
            byte[] mimetype = entries.get(MIMETYPE_ENTRY);
            if (mimetype != null) {
                writeStored(out, MIMETYPE_ENTRY, mimetype);
            }
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (entry.getKey().equals(MIMETYPE_ENTRY)) continue;
                writeDeflated(out, entry.getKey(), entry.getValue());
            }
        }
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String parentDir(String zipPath) {
        int slash = zipPath.lastIndexOf('/');
        return slash < 0 ? "" : zipPath.substring(0, slash);
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** Resolve a possibly-relative reference (with {@code ../}, {@code ./}) to a zip path. */
    private static String resolveZipPath(String baseDir, String ref) {
        Deque<String> stack = new ArrayDeque<>();
        if (!baseDir.isEmpty()) {
            for (String part : baseDir.split("/")) {
                if (!part.isEmpty()) stack.addLast(part);
            }
        }
        for (String part : ref.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.addLast(part);
            }
        }
        return String.join("/", stack);
    }

    private static void writeStored(ZipArchiveOutputStream out, String name, byte[] data) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        out.putArchiveEntry(entry);
        out.write(data);
        out.closeArchiveEntry();
    }

    private static void writeDeflated(ZipArchiveOutputStream out, String name, byte[] data) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        out.putArchiveEntry(entry);
        out.write(data);
        out.closeArchiveEntry();
    }
}
