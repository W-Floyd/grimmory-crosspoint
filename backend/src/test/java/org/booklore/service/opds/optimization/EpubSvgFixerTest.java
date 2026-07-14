package org.booklore.service.opds.optimization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpubSvgFixerTest {

    private final EpubSvgFixer fixer = new EpubSvgFixer();

    @Test
    void fixSvgCover_rewritesSvgCoverToImg() {
        String content = """
                <?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Cover</title></head>
                <body><svg xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 600 800">
                <image width="600" height="800" xlink:href="images/cover.jpg"/></svg></body>
                </html>""";

        EpubSvgFixer.FixResult result = fixer.fixSvgCover(content);

        assertThat(result.fixed()).isTrue();
        assertThat(result.content()).contains("<img");
        assertThat(result.content()).contains("src=\"images/cover.jpg\"");
        assertThat(result.content()).doesNotContain("<svg");
    }

    @Test
    void fixSvgCover_ignoresNonCoverDocuments() {
        String content = "<html><body><p>No svg here</p></body></html>";
        EpubSvgFixer.FixResult result = fixer.fixSvgCover(content);
        assertThat(result.fixed()).isFalse();
        assertThat(result.content()).isEqualTo(content);
    }

    @Test
    void fixSvgWrappedImages_unwrapsEachSvg() {
        String content = """
                <html xmlns:xlink="http://www.w3.org/1999/xlink"><body>
                <div><svg viewBox="0 0 100 100"><image xlink:href="a.png"/></svg></div>
                <div><svg viewBox="0 0 100 100"><image xlink:href="b.png"/></svg></div>
                </body></html>""";

        EpubSvgFixer.FixResult result = fixer.fixSvgWrappedImages(content);

        assertThat(result.fixed()).isTrue();
        assertThat(result.content()).contains("src=\"a.png\"");
        assertThat(result.content()).contains("src=\"b.png\"");
        assertThat(result.content()).doesNotContain("<svg");
    }

    @Test
    void fixSvgWrappedImages_noSvgIsUnchanged() {
        String content = "<html><body><img src=\"a.jpg\"/></body></html>";
        EpubSvgFixer.FixResult result = fixer.fixSvgWrappedImages(content);
        assertThat(result.fixed()).isFalse();
    }
}
