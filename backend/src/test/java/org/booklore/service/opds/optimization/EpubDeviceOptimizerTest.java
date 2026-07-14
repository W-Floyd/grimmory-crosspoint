package org.booklore.service.opds.optimization;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.booklore.model.dto.opds.DevicePreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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

class EpubDeviceOptimizerTest {

    @TempDir
    Path tempDir;

    private EpubDeviceOptimizer optimizer;

    @BeforeEach
    void setUp() {
        optimizer = new EpubDeviceOptimizer(new EpubImageProcessor(), new EpubSvgFixer());
    }

    private DevicePreset preset() {
        DevicePreset p = new DevicePreset();
        p.setMaxWidth(480);
        p.setMaxHeight(800);
        p.setJpegQuality(85);
        p.setGrayscale(true);
        return p;
    }

    @Test
    void optimize_renamesImagesRewritesReferencesAndKeepsValidEpub() throws Exception {
        Path source = buildSampleEpub();
        Path target = tempDir.resolve("out.epub");

        optimizer.optimize(source, preset(), target);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        String firstEntry = null;
        int firstMethod = -1;
        try (ZipFile zip = ZipFile.builder().setPath(target).get()) {
            Enumeration<ZipArchiveEntry> e = zip.getEntriesInPhysicalOrder();
            while (e.hasMoreElements()) {
                ZipArchiveEntry entry = e.nextElement();
                if (firstEntry == null) {
                    firstEntry = entry.getName();
                    firstMethod = entry.getMethod();
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }

        // mimetype must be first and STORED per OCF.
        assertThat(firstEntry).isEqualTo("mimetype");
        assertThat(firstMethod).isEqualTo(ZipEntry.STORED);

        // Image renamed png -> jpg.
        assertThat(entries).containsKey("OEBPS/images/pic.jpg");
        assertThat(entries).doesNotContainKey("OEBPS/images/pic.png");

        // Decodable JPEG within device bounds.
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(entries.get("OEBPS/images/pic.jpg")));
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isLessThanOrEqualTo(480);
        assertThat(img.getHeight()).isLessThanOrEqualTo(800);

        // XHTML: src rewritten, dimensions stripped.
        String xhtml = new String(entries.get("OEBPS/page1.xhtml"), StandardCharsets.UTF_8);
        assertThat(xhtml).contains("images/pic.jpg");
        assertThat(xhtml).doesNotContain("pic.png");
        assertThat(xhtml).doesNotContain("width=");
        assertThat(xhtml).doesNotContain("height=");

        // OPF: manifest href + media-type updated.
        String opf = new String(entries.get("OEBPS/content.opf"), StandardCharsets.UTF_8);
        assertThat(opf).contains("images/pic.jpg");
        assertThat(opf).contains("image/jpeg");
        assertThat(opf).doesNotContain("image/png");
    }

    @Test
    void resolveZipPath_handlesRelativeSegments() {
        assertThat(EpubDeviceOptimizer.resolveZipPath("OEBPS/Text", "../images/p.png"))
                .isEqualTo("OEBPS/images/p.png");
        assertThat(EpubDeviceOptimizer.resolveZipPath("OEBPS", "images/p.png"))
                .isEqualTo("OEBPS/images/p.png");
        assertThat(EpubDeviceOptimizer.resolveZipPath("", "cover.png"))
                .isEqualTo("cover.png");
    }

    private Path buildSampleEpub() throws Exception {
        Path epub = tempDir.resolve("in.epub");
        byte[] png = pngBytes(1200, 1800);

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
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest>
                    <item id="page1" href="page1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="pic" href="images/pic.png" media-type="image/png"/>
                  </manifest>
                  <spine><itemref idref="page1"/></spine>
                </package>""";
        String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Page</title></head>
                <body><img src="images/pic.png" width="600" height="900" alt=""/></body></html>""";

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
            putDeflated(zos, "OEBPS/images/pic.png", png);
        }
        return epub;
    }

    private void putDeflated(ZipOutputStream zos, String name, byte[] data) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private byte[] pngBytes(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(20, 120, 220));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
