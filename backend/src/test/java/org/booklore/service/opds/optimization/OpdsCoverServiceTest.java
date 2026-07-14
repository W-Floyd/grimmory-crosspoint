package org.booklore.service.opds.optimization;

import org.booklore.model.dto.opds.DevicePreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpdsCoverServiceTest {

    private OpdsCoverService service;

    @BeforeEach
    void setUp() {
        service = new OpdsCoverService(new EpubImageProcessor());
    }

    private DevicePreset preset() {
        DevicePreset p = new DevicePreset();
        p.setLabel("X3");
        p.setMaxWidth(528);
        p.setMaxHeight(792);
        p.setJpegQuality(85);
        p.setGrayscale(true);
        return p;
    }

    private Resource coverPng(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return new ByteArrayResource(baos.toByteArray());
    }

    @Test
    void reencode_producesDecodableGrayscaleJpeg() throws Exception {
        Optional<Resource> out = service.reencode(coverPng(250, 350, new Color(180, 40, 40)), preset());

        assertThat(out).isPresent();
        byte[] data = out.get().getInputStream().readAllBytes();
        // JPEG magic bytes (baseline JPEG via ImageIO — no ICC/progressive).
        assertThat(data[0] & 0xFF).isEqualTo(0xFF);
        assertThat(data[1] & 0xFF).isEqualTo(0xD8);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
        assertThat(decoded).isNotNull();
        // Small cover is not upscaled.
        assertThat(decoded.getWidth()).isEqualTo(250);
        assertThat(decoded.getHeight()).isEqualTo(350);
        // Grayscale preset -> true single-component JPEG (components 1).
        assertThat(decoded.getRaster().getNumBands()).isEqualTo(1);
        assertThat(out.get().getFilename()).isEqualTo("cover.jpg");
    }

    @Test
    void reencode_returnsEmptyOnUndecodableInput() {
        Resource garbage = new ByteArrayResource("not an image".getBytes());
        assertThat(service.reencode(garbage, preset())).isEmpty();
    }
}
