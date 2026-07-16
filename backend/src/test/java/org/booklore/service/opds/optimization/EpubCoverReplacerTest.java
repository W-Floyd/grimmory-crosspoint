package org.booklore.service.opds.optimization;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class EpubCoverReplacerTest {

    @TempDir
    Path tempDir;

    private final EpubCoverReplacer replacer = new EpubCoverReplacer();

    @Test
    void replacesJpegCoverViaCoverImageProperty_keepingBytesExactAndOtherEntriesIntact() throws Exception {
        byte[] originalCover = jpeg(new Color(10, 10, 10), 600, 900);
        Path epub = buildEpub("images/cover.jpg", "image/jpeg", originalCover, CoverDecl.PROPERTIES);
        byte[] newCover = jpeg(new Color(220, 30, 30), 1000, 1500);
        Path out = tempDir.resolve("out.epub");

        boolean replaced = replacer.replaceCover(epub, newCover, "jpg", out);

        assertThat(replaced).isTrue();
        Map<String, byte[]> entries = readEntries(out);
        // JPEG target: metadata cover bytes copied verbatim.
        assertThat(entries.get("OEBPS/images/cover.jpg")).isEqualTo(newCover);
        // Other entries untouched.
        assertThat(entries).containsKeys("mimetype", "META-INF/container.xml", "OEBPS/content.opf", "OEBPS/page1.xhtml");
        assertThat(firstEntryStored(out)).isTrue();
    }

    @Test
    void convertsPngCoverViaMetaCoverToJpegInsertedAsIs() throws Exception {
        Path epub = buildEpub("images/cover.png", "image/png", png(200, 300), CoverDecl.META);
        byte[] newCover = jpeg(new Color(30, 200, 60), 1000, 1500);
        Path out = tempDir.resolve("out.epub");

        boolean replaced = replacer.replaceCover(epub, newCover, "jpg", out);

        assertThat(replaced).isTrue();
        Map<String, byte[]> entries = readEntries(out);
        // png entry renamed to jpg, holding the metadata cover bytes verbatim (no re-encode).
        assertThat(entries).containsKey("OEBPS/images/cover.jpg");
        assertThat(entries).doesNotContainKey("OEBPS/images/cover.png");
        assertThat(entries.get("OEBPS/images/cover.jpg")).isEqualTo(newCover);
        String opf = new String(entries.get("OEBPS/content.opf"), StandardCharsets.UTF_8);
        assertThat(opf).contains("images/cover.jpg").contains("image/jpeg");
        assertThat(opf).doesNotContain("cover.png").doesNotContain("image/png");
    }

    @Test
    void convertsWebpCoverToJpegAndRewritesReferences() throws Exception {
        Path epub = buildWebpCoverEpub();
        byte[] newCover = jpeg(new Color(200, 60, 30), 1000, 1500);
        Path out = tempDir.resolve("out.epub");

        boolean replaced = replacer.replaceCover(epub, newCover, "jpg", out);

        assertThat(replaced).isTrue();
        Map<String, byte[]> entries = readEntries(out);
        // Entry renamed webp -> jpg, holding the metadata cover bytes.
        assertThat(entries).containsKey("OEBPS/images/cover.jpg");
        assertThat(entries).doesNotContainKey("OEBPS/images/cover.webp");
        assertThat(entries.get("OEBPS/images/cover.jpg")).isEqualTo(newCover);
        // OPF manifest href + media-type rewritten.
        String opf = new String(entries.get("OEBPS/content.opf"), StandardCharsets.UTF_8);
        assertThat(opf).contains("images/cover.jpg").contains("image/jpeg");
        assertThat(opf).doesNotContain("cover.webp").doesNotContain("image/webp");
        // XHTML reference rewritten.
        String xhtml = new String(entries.get("OEBPS/cover.xhtml"), StandardCharsets.UTF_8);
        assertThat(xhtml).contains("images/cover.jpg");
        assertThat(xhtml).doesNotContain("cover.webp");
    }

    @Test
    void returnsFalseWhenNoCoverCanBeDetected() throws Exception {
        // Image entry named "pic", no cover-image property, no meta cover, no guide reference.
        Path epub = buildEpub("images/pic.jpg", "image/jpeg", jpeg(Color.GRAY, 100, 100), CoverDecl.NONE);
        Path out = tempDir.resolve("out.epub");

        assertThat(replacer.replaceCover(epub, jpeg(Color.RED, 100, 100), "jpg", out)).isFalse();
        assertThat(Files.exists(out)).isFalse();
    }

    @Test
    void returnsFalseForEmptyCoverBytes() throws Exception {
        Path epub = buildEpub("images/cover.jpg", "image/jpeg", jpeg(Color.GRAY, 100, 100), CoverDecl.PROPERTIES);
        Path out = tempDir.resolve("out.epub");

        assertThat(replacer.replaceCover(epub, new byte[0], "jpg", out)).isFalse();
        assertThat(Files.exists(out)).isFalse();
    }

    private enum CoverDecl { PROPERTIES, META, NONE }

    private Path buildEpub(String coverHref, String coverMediaType, byte[] coverBytes, CoverDecl decl) throws Exception {
        Path epub = tempDir.resolve("in-" + coverHref.hashCode() + ".epub");
        // Use a cover-flavoured id only when we actually declare a cover; NONE must not leak "cover".
        String coverId = decl == CoverDecl.NONE ? "img1" : "coverimg";
        String props = decl == CoverDecl.PROPERTIES ? " properties=\"cover-image\"" : "";
        String metaCover = decl == CoverDecl.META ? "<meta name=\"cover\" content=\"" + coverId + "\"/>" : "";

        String container = """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""";
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="bookid">urn:uuid:test</dc:identifier>
                    <dc:title>Test</dc:title>
                    %s
                  </metadata>
                  <manifest>
                    <item id="page1" href="page1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="%s" href="%s" media-type="%s"%s/>
                  </manifest>
                  <spine><itemref idref="page1"/></spine>
                </package>""".formatted(metaCover, coverId, coverHref, coverMediaType, props);
        String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Page</title></head>
                <body><p>Hi</p></body></html>""";

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(epub))) {
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.UTF_8);
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            mime.setCrc(crc.getValue());
            zos.putNextEntry(mime);
            zos.write(mimetype);
            zos.closeEntry();

            putDeflated(zos, "META-INF/container.xml", container.getBytes(StandardCharsets.UTF_8));
            putDeflated(zos, "OEBPS/content.opf", opf.getBytes(StandardCharsets.UTF_8));
            putDeflated(zos, "OEBPS/page1.xhtml", xhtml.getBytes(StandardCharsets.UTF_8));
            putDeflated(zos, "OEBPS/" + coverHref, coverBytes);
        }
        return epub;
    }

    /** EPUB whose cover is a WebP referenced from the manifest (cover-image) and a cover XHTML page. */
    private Path buildWebpCoverEpub() throws Exception {
        Path epub = tempDir.resolve("webp.epub");
        String container = """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""";
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="bookid">urn:uuid:test</dc:identifier>
                    <dc:title>Test</dc:title>
                  </metadata>
                  <manifest>
                    <item id="coverpage" href="cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="coverimg" href="images/cover.webp" media-type="image/webp" properties="cover-image"/>
                  </manifest>
                  <spine><itemref idref="coverpage"/></spine>
                  <guide><reference type="cover" href="images/cover.webp"/></guide>
                </package>""";
        String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Cover</title></head>
                <body><img src="images/cover.webp" alt="cover"/></body></html>""";

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(epub))) {
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.UTF_8);
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            mime.setCrc(crc.getValue());
            zos.putNextEntry(mime);
            zos.write(mimetype);
            zos.closeEntry();

            putDeflated(zos, "META-INF/container.xml", container.getBytes(StandardCharsets.UTF_8));
            putDeflated(zos, "OEBPS/content.opf", opf.getBytes(StandardCharsets.UTF_8));
            putDeflated(zos, "OEBPS/cover.xhtml", xhtml.getBytes(StandardCharsets.UTF_8));
            // Dummy bytes: the convert path never decodes the original cover.
            putDeflated(zos, "OEBPS/images/cover.webp", new byte[]{0x52, 0x49, 0x46, 0x46});
        }
        return epub;
    }

    private void putDeflated(ZipOutputStream zos, String name, byte[] data) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private Map<String, byte[]> readEntries(Path zipPath) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = ZipFile.builder().setPath(zipPath).get()) {
            Enumeration<ZipArchiveEntry> e = zip.getEntriesInPhysicalOrder();
            while (e.hasMoreElements()) {
                ZipArchiveEntry entry = e.nextElement();
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return entries;
    }

    private boolean firstEntryStored(Path zipPath) throws Exception {
        try (ZipFile zip = ZipFile.builder().setPath(zipPath).get()) {
            Enumeration<ZipArchiveEntry> e = zip.getEntriesInPhysicalOrder();
            ZipArchiveEntry first = e.nextElement();
            return first.getName().equals("mimetype") && first.getMethod() == ZipEntry.STORED;
        }
    }

    private byte[] jpeg(Color color, int w, int h) throws Exception {
        return image(color, w, h, "jpeg");
    }

    private byte[] png(int w, int h) throws Exception {
        return image(new Color(15, 90, 160), w, h, "png");
    }

    private byte[] image(Color color, int w, int h, String format) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }
}
