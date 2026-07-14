package org.booklore.service.opds.optimization;

import org.booklore.model.dto.opds.DevicePreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class EpubImageProcessorTest {

    private EpubImageProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new EpubImageProcessor();
    }

    private DevicePreset preset(boolean grayscale) {
        DevicePreset p = new DevicePreset();
        p.setMaxWidth(480);
        p.setMaxHeight(800);
        p.setJpegQuality(85);
        p.setGrayscale(grayscale);
        p.setAutoCrop(false);
        return p;
    }

    private byte[] png(int width, int height, Color color) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void downscalesOversizedImageWithinBounds() throws Exception {
        byte[] source = png(1600, 2400, new Color(30, 90, 200));

        EpubImageProcessor.ProcessedImage result = processor.process(source, "OEBPS/images/p1.png", preset(true));

        assertThat(result.width()).isLessThanOrEqualTo(480);
        assertThat(result.height()).isLessThanOrEqualTo(800);
        // 1600x2400 (2:3) scaled by min(480/1600, 800/2400)=0.3 -> 480x720; width is the limiter.
        assertThat(result.width()).isEqualTo(480);
        assertThat(result.height()).isEqualTo(720);
    }

    @Test
    void grayscaleProducesEqualChannels() throws Exception {
        byte[] source = png(600, 900, new Color(200, 40, 40));

        EpubImageProcessor.ProcessedImage result = processor.process(source, "OEBPS/images/p1.png", preset(true));

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result.data()));
        int rgb = out.getRGB(out.getWidth() / 2, out.getHeight() / 2);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        // JPEG is lossy; channels should be within a tight tolerance of each other.
        assertThat(Math.abs(r - g)).isLessThanOrEqualTo(4);
        assertThat(Math.abs(g - b)).isLessThanOrEqualTo(4);
    }

    @Test
    void doesNotUpscaleSmallImage() throws Exception {
        byte[] source = png(200, 300, Color.WHITE);

        EpubImageProcessor.ProcessedImage result = processor.process(source, "OEBPS/images/small.png", preset(false));

        assertThat(result.width()).isEqualTo(200);
        assertThat(result.height()).isEqualTo(300);
    }
}
