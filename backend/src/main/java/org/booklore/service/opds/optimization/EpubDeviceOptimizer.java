package org.booklore.service.opds.optimization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.booklore.model.dto.opds.DevicePreset;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/**
 * Produces a device-optimized copy of an EPUB, reproducing the automatic parts of the
 * Crosspoint firmware's browser-side converter ({@code convertEpubFile}): re-render every
 * image for the target device ({@link EpubImageProcessor}), rename non-JPEG images to
 * {@code .jpg} and rewrite their references, unwrap SVG-wrapped images
 * ({@link EpubSvgFixer}), strip stale {@code <img>} dimensions, and fix the OPF manifest
 * media-types / cover metadata.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EpubDeviceOptimizer {

    private static final String MIMETYPE_ENTRY = "mimetype";
    private static final Pattern IMAGE_EXT = Pattern.compile("\\.(png|gif|webp|bmp|jpg|jpeg)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAMABLE_EXT = Pattern.compile("\\.(png|gif|webp|bmp|jpeg)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern XHTML_EXT = Pattern.compile("\\.(xhtml|html|htm)$", Pattern.CASE_INSENSITIVE);

    private final EpubImageProcessor imageProcessor;
    private final EpubSvgFixer svgFixer;

    /**
     * Write a device-optimized EPUB derived from {@code sourceEpub} to {@code target}.
     */
    public void optimize(Path sourceEpub, DevicePreset preset, Path target) throws IOException {
        // Read every entry up-front (physical order) so the rename map is complete before
        // XHTML/OPF references are rewritten. EpubZipReader bounds the total uncompressed size
        // (zip-bomb guard); the caller only bounds the compressed source size.
        Map<String, byte[]> entries = EpubZipReader.readEntries(sourceEpub);

        Map<String, String> renamed = new LinkedHashMap<>();     // old full path -> new full path
        List<String> deferredXhtml = new ArrayList<>();
        List<String> deferredOpf = new ArrayList<>();

        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(Files.newOutputStream(target))) {
            // mimetype must be first and STORED per the EPUB OCF spec.
            byte[] mimetype = entries.get(MIMETYPE_ENTRY);
            if (mimetype != null) {
                writeStored(out, MIMETYPE_ENTRY, mimetype);
            }

            // First pass: images (renaming successes) + verbatim copies; defer text.
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                String path = entry.getKey();
                if (path.equals(MIMETYPE_ENTRY)) continue;
                byte[] data = entry.getValue();
                String lower = path.toLowerCase(Locale.ROOT);

                if (IMAGE_EXT.matcher(lower).find()) {
                    writeImage(out, path, data, preset, renamed);
                } else if (XHTML_EXT.matcher(lower).find()) {
                    deferredXhtml.add(path);
                } else if (lower.endsWith(".opf")) {
                    deferredOpf.add(path);
                } else {
                    writeDeflated(out, path, data);
                }
            }

            // Second pass: rewrite and write text now that the rename map is complete.
            for (String path : deferredXhtml) {
                String content = new String(entries.get(path), StandardCharsets.UTF_8);
                writeDeflated(out, path, rewriteXhtml(content, path, renamed).getBytes(StandardCharsets.UTF_8));
            }
            for (String path : deferredOpf) {
                String content = new String(entries.get(path), StandardCharsets.UTF_8);
                writeDeflated(out, path, rewriteOpf(content, path, renamed).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void writeImage(ZipArchiveOutputStream out, String path, byte[] data, DevicePreset preset,
                            Map<String, String> renamed) throws IOException {
        String targetPath = renameToJpg(path);
        try {
            EpubImageProcessor.ProcessedImage processed = imageProcessor.process(data, path, preset);
            if (!targetPath.equals(path)) {
                renamed.put(path, targetPath);
            }
            writeDeflated(out, targetPath, processed.data());
        } catch (Exception ex) {
            // Match the firmware's resilience: on failure keep the original image untouched
            // (original name + bytes) so its references remain valid.
            log.warn("Failed to optimize image {}, using original: {}", path, ex.getMessage());
            writeDeflated(out, path, data);
        }
    }

    // ---- XHTML rewriting ----

    private String rewriteXhtml(String content, String xhtmlPath, Map<String, String> renamed) {
        EpubSvgFixer.FixResult cover = svgFixer.fixSvgCover(content);
        String working = cover.content();
        if (!cover.fixed()) {
            working = svgFixer.fixSvgWrappedImages(working).content();
        }

        try {
            Document doc = Jsoup.parse(working, "", Parser.xmlParser());
            doc.outputSettings().prettyPrint(false);
            String baseDir = parentDir(xhtmlPath);
            boolean modified = false;

            for (Element img : doc.getElementsByTag("img")) {
                if (img.hasAttr("width")) {
                    img.removeAttr("width");
                    modified = true;
                }
                if (img.hasAttr("height")) {
                    img.removeAttr("height");
                    modified = true;
                }
                String src = img.attr("src");
                if (!src.isEmpty()) {
                    String newSrc = rewriteRef(src, baseDir, renamed);
                    if (newSrc != null) {
                        img.attr("src", newSrc);
                        modified = true;
                    }
                }
            }
            return modified ? doc.outerHtml() : working;
        } catch (Exception e) {
            log.debug("XHTML parse failed for {}, keeping SVG-fixed content: {}", xhtmlPath, e.getMessage());
            return working;
        }
    }

    // ---- OPF rewriting ----

    private String rewriteOpf(String content, String opfPath, Map<String, String> renamed) {
        try {
            Document doc = Jsoup.parse(content, "", Parser.xmlParser());
            doc.outputSettings().prettyPrint(false);
            String baseDir = parentDir(opfPath);

            for (Element item : doc.getElementsByTag("item")) {
                String href = item.attr("href");
                if (!href.isEmpty()) {
                    String newHref = rewriteRef(href, baseDir, renamed);
                    if (newHref != null) {
                        item.attr("href", newHref);
                        item.attr("media-type", "image/jpeg");
                    }
                }
                // Drop the "svg" rendition property now that covers are plain <img>.
                String props = item.attr("properties");
                if (props.contains("svg")) {
                    String newProps = String.join(" ",
                            java.util.Arrays.stream(props.split("\\s+"))
                                    .filter(p -> !p.equals("svg") && !p.isBlank())
                                    .toList());
                    if (newProps.isBlank()) {
                        item.removeAttr("properties");
                    } else {
                        item.attr("properties", newProps);
                    }
                }
            }

            ensureCoverMeta(doc);
            return doc.outerHtml();
        } catch (Exception e) {
            log.debug("OPF parse failed for {}, leaving unchanged: {}", opfPath, e.getMessage());
            return content;
        }
    }

    private void ensureCoverMeta(Document doc) {
        String coverId = null;
        for (Element item : doc.getElementsByTag("item")) {
            String type = item.attr("media-type");
            if (!type.startsWith("image/")) continue;
            if (item.attr("properties").contains("cover-image")) {
                coverId = item.attr("id");
                break;
            }
        }
        if (coverId == null) {
            for (Element item : doc.getElementsByTag("item")) {
                String type = item.attr("media-type");
                if (!type.startsWith("image/")) continue;
                if (item.attr("id").toLowerCase(Locale.ROOT).contains("cover")
                        || item.attr("href").toLowerCase(Locale.ROOT).contains("cover")) {
                    coverId = item.attr("id");
                    break;
                }
            }
        }
        if (coverId == null || coverId.isBlank()) return;

        for (Element meta : doc.getElementsByTag("meta")) {
            if ("cover".equals(meta.attr("name"))) {
                meta.attr("content", coverId);
                return;
            }
        }
        Element metadata = doc.getElementsByTag("metadata").first();
        if (metadata != null) {
            metadata.appendElement("meta").attr("name", "cover").attr("content", coverId);
        }
    }

    // ---- Reference resolution ----

    /**
     * If {@code ref} (relative to {@code baseDir}) resolves to a renamed image, return the
     * rewritten reference (extension swapped to {@code .jpg}); otherwise {@code null}.
     */
    private String rewriteRef(String ref, String baseDir, Map<String, String> renamed) {
        if (!RENAMABLE_EXT.matcher(ref).find()) return null;
        String resolved = resolveZipPath(baseDir, decode(ref));
        if (resolved == null || !renamed.containsKey(resolved)) return null;
        return RENAMABLE_EXT.matcher(ref).replaceAll(".jpg");
    }

    private static String renameToJpg(String path) {
        return RENAMABLE_EXT.matcher(path).replaceAll(".jpg");
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String parentDir(String zipPath) {
        int slash = zipPath.lastIndexOf('/');
        return slash < 0 ? "" : zipPath.substring(0, slash);
    }

    /** Resolve a possibly-relative reference (with {@code ../}, {@code ./}) to a zip path. */
    static String resolveZipPath(String baseDir, String ref) {
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

    // ---- Zip writing ----

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
