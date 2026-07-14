package org.booklore.service.opds.optimization;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites SVG-wrapped images in EPUB XHTML into plain responsive {@code <img>} tags,
 * mirroring the Crosspoint firmware's {@code fixSvgCover} / {@code fixSvgWrappedImages}.
 * Fixed-viewport SVG wrappers force a single image to fill the viewport at authored
 * dimensions, which breaks once the image has been rescaled for the device; unwrapping
 * to a fluid {@code <img>} lets the reader lay it out correctly.
 */
@Component
public class EpubSvgFixer {

    private static final Pattern XLINK_HREF =
            Pattern.compile("xlink:href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern SVG_WRAPPED_IMAGE = Pattern.compile(
            "<(?:svg:)?svg\\b[^>]*>.*?<(?:svg:)?image\\b[^>]*xlink:href=[\"']([^\"']+)[\"'][^>]*/?>\\s*</(?:svg:)?svg>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Result of a fix pass: the (possibly) rewritten content and whether anything changed. */
    public record FixResult(String content, boolean fixed) {
    }

    /**
     * If the document is an SVG-wrapped cover page, replace it with a minimal cover XHTML
     * whose {@code <img>} points at the wrapped image.
     */
    public FixResult fixSvgCover(String content) {
        if (content == null) return new FixResult(null, false);
        boolean hasSvg = content.contains("<svg") || content.contains("<svg:");
        if (!hasSvg || !content.contains("xlink:href")) {
            return new FixResult(content, false);
        }
        boolean looksLikeCover = content.contains("calibre:cover")
                || content.contains("name=\"cover\"")
                || content.contains("<title>Cover</title>");
        if (!looksLikeCover) {
            return new FixResult(content, false);
        }

        Matcher m = XLINK_HREF.matcher(content);
        if (!m.find()) {
            return new FixResult(content, false);
        }
        String href = m.group(1);
        String rebuilt = """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en" xml:lang="en">
                <head><meta content="text/html; charset=UTF-8" http-equiv="default-style"/><title>Cover</title></head>
                <body><section epub:type="cover"><img style="max-width:100%%;height:auto" alt="Cover" src="%s"/></section></body>
                </html>""".formatted(href);
        return new FixResult(rebuilt, true);
    }

    /** Unwrap each {@code <svg>…<image/>…</svg>} block into a fluid {@code <img>} tag. */
    public FixResult fixSvgWrappedImages(String content) {
        if (content == null) return new FixResult(null, false);
        boolean hasSvg = content.contains("<svg") || content.contains("<svg:");
        if (!hasSvg || !content.contains("xlink:href")) {
            return new FixResult(content, false);
        }

        Matcher m = SVG_WRAPPED_IMAGE.matcher(content);
        StringBuilder out = new StringBuilder();
        int count = 0;
        while (m.find()) {
            String href = Matcher.quoteReplacement(m.group(1));
            m.appendReplacement(out, "<img style=\"max-width:100%;height:auto\" src=\"" + href + "\" alt=\"\" />");
            count++;
        }
        m.appendTail(out);

        if (count == 0) {
            return new FixResult(content, false);
        }
        return new FixResult(out.toString(), true);
    }
}
