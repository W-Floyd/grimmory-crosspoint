package org.booklore.util.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class JpegImageWriterTest {

    @Test
    void transparencyBecomesWhiteNotBlack() throws Exception {
        BufferedImage transparent = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(JpegImageWriter.encode(transparent, 0.9f)));

        int rgb = decoded.getRGB(8, 8);
        // Each channel near white; JPEG is lossy, so not exactly 255.
        assertThat(java.util.List.of((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff)).allMatch(c -> c > 240);
    }
}
