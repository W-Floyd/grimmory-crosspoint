package org.booklore.service.opds.optimization;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
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
public class EpubCoverReplacer {

    private static final String MIMETYPE_ENTRY = "mimetype";
    private static final String CONTAINER_ENTRY = "META-INF/container.xml";
    private static final Pattern IMAGE_EXT = Pattern.compile("\\.(png|gif|webp|bmp|jpg|jpeg)$", Pattern.CASE_INSENSITIVE);

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
            String coverEntry = findCoverEntry(entries, opfPath);
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
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = ZipFile.builder().setPath(sourceEpub).get()) {
            Enumeration<ZipArchiveEntry> e = zip.getEntriesInPhysicalOrder();
            while (e.hasMoreElements()) {
                ZipArchiveEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return entries;
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
     * Resolve the zip path of the cover image entry: manifest {@code properties="cover-image"} →
     * {@code <meta name="cover">} manifest id → guide {@code <reference type="cover">} (only when it
     * points at an image) → an image item whose id/href contains "cover".
     */
    private String findCoverEntry(Map<String, byte[]> entries, String opfPath) {
        Document opf;
        try {
            opf = Jsoup.parse(new String(entries.get(opfPath), StandardCharsets.UTF_8), "", Parser.xmlParser());
        } catch (Exception e) {
            return null;
        }
        String baseDir = parentDir(opfPath);

        String href = null;

        for (Element item : opf.getElementsByTag("item")) {
            if (item.attr("properties").contains("cover-image") && isImage(item)) {
                href = item.attr("href");
                break;
            }
        }
        if (href == null) {
            String coverId = null;
            for (Element meta : opf.getElementsByTag("meta")) {
                if ("cover".equals(meta.attr("name")) && !meta.attr("content").isBlank()) {
                    coverId = meta.attr("content");
                    break;
                }
            }
            if (coverId != null) {
                href = manifestHrefById(opf, coverId);
            }
        }
        if (href == null) {
            for (Element ref : opf.getElementsByTag("reference")) {
                if ("cover".equalsIgnoreCase(ref.attr("type")) && IMAGE_EXT.matcher(ref.attr("href")).find()) {
                    href = ref.attr("href");
                    break;
                }
            }
        }
        if (href == null) {
            for (Element item : opf.getElementsByTag("item")) {
                if (!isImage(item)) continue;
                if (item.attr("id").toLowerCase(Locale.ROOT).contains("cover")
                        || item.attr("href").toLowerCase(Locale.ROOT).contains("cover")) {
                    href = item.attr("href");
                    break;
                }
            }
        }
        if (href == null || href.isBlank()) {
            return null;
        }

        String resolved = resolveZipPath(baseDir, decode(href));
        return IMAGE_EXT.matcher(resolved).find() ? resolved : null;
    }

    private static String manifestHrefById(Document opf, String id) {
        for (Element item : opf.getElementsByTag("item")) {
            if (id.equals(item.attr("id")) && isImage(item)) {
                return item.attr("href");
            }
        }
        return null;
    }

    private static boolean isImage(Element item) {
        String type = item.attr("media-type");
        return type.startsWith("image/") || IMAGE_EXT.matcher(item.attr("href")).find();
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
