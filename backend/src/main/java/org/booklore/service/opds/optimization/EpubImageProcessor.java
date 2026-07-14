package org.booklore.service.opds.optimization;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.opds.DevicePreset;
import org.booklore.util.FileService;
import org.booklore.util.image.JpegImageWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Re-renders a single EPUB image for a device preset, faithfully reproducing the
 * automatic (STATE-0) path of the Crosspoint firmware's browser-side converter:
 * optional auto-crop of uniform margins, downscale-to-fit within the device screen,
 * true grayscale (BT.601), and baseline-JPEG re-encode.
 * <p>
 * The interactive H-Split / V-Split / Rotate&amp;Fit states from the firmware's image
 * picker have no automatic equivalent and are intentionally not reproduced here.
 */
@Slf4j
@Component
public class EpubImageProcessor {

    // Auto-crop tuning constants, mirrored from FilesPage.html.
    private static final int CROP_WHITE_THRESHOLD = 245;
    private static final int CROP_BACKGROUND_TOLERANCE = 28;
    private static final int CROP_BACKGROUND_MAX_SPREAD = 24;
    private static final int CROP_EDGE_SAMPLE_SIZE = 12;
    private static final int CROP_PADDING_PX = 8;
    private static final double MIN_CROP_SAVINGS_RATIO = 0.08;
    private static final double MIN_COLOR_CROP_SAVINGS_RATIO = 0.20;
    private static final int MIN_CROP_DIMENSION = 240;

    private static final Pattern COVER_ICON_PATTERN =
            Pattern.compile("(^|/)(cover|thumbnail|thumb|icon)[^/]*\\.(jpe?g|png|gif|webp|bmp)$", Pattern.CASE_INSENSITIVE);

    /** Result of processing: the encoded JPEG bytes plus the final pixel dimensions. */
    public record ProcessedImage(byte[] data, int width, int height) {
    }

    /**
     * Decode, optimize and JPEG-encode an image for the given preset.
     *
     * @throws IOException if the image cannot be decoded or encoded
     */
    public ProcessedImage process(byte[] imageData, String entryPath, DevicePreset preset) throws IOException {
        BufferedImage decoded = FileService.readImage(imageData);

        Source source = autoCrop(decoded, entryPath, preset);
        int srcW = source.width;
        int srcH = source.height;
        int maxW = preset.getMaxWidth();
        int maxH = preset.getMaxHeight();

        boolean fits = srcW <= maxW && srcH <= maxH;

        BufferedImage canvas;
        if (fits && !source.cropped) {
            canvas = whiteCanvas(srcW, srcH);
            drawScaled(canvas, source.image, srcW, srcH);
        } else {
            double scale = Math.min((double) maxW / srcW, (double) maxH / srcH);
            int newW = Math.max(1, (int) Math.round(srcW * scale));
            int newH = Math.max(1, (int) Math.round(srcH * scale));
            canvas = whiteCanvas(newW, newH);
            drawScaled(canvas, source.image, newW, newH);
        }

        if (preset.isGrayscale()) {
            applyGrayscale(canvas);
        }

        float quality = clampQuality(preset.getJpegQuality()) / 100f;
        byte[] jpeg = JpegImageWriter.encode(canvas, quality);
        return new ProcessedImage(jpeg, canvas.getWidth(), canvas.getHeight());
    }

    private static int clampQuality(int quality) {
        return Math.max(1, Math.min(100, quality));
    }

    private static BufferedImage whiteCanvas(int width, int height) {
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return canvas;
    }

    private static void drawScaled(BufferedImage target, BufferedImage source, int width, int height) {
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
    }

    /** True grayscale in-place (BT.601 luminance). Canvas is already white-composited. */
    private static void applyGrayscale(BufferedImage canvas) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            canvas.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int rgb = row[x];
                int r = (rgb >> 16) & 0xFF;
                int gr = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int gray = (int) Math.round(r * 0.299 + gr * 0.587 + b * 0.114);
                row[x] = (gray << 16) | (gray << 8) | gray;
            }
            canvas.setRGB(0, y, w, 1, row, 0, w);
        }
    }

    // ---- Auto-crop (mirrors createAutoCroppedCanvas / findNonWhiteBounds) ----

    private record Source(BufferedImage image, int width, int height, boolean cropped) {
    }

    private Source autoCrop(BufferedImage decoded, String entryPath, DevicePreset preset) {
        int width = decoded.getWidth();
        int height = decoded.getHeight();

        // Build a white-backed source (composites away transparency), like the firmware.
        BufferedImage sourceCanvas = whiteCanvas(width, height);
        Graphics2D g = sourceCanvas.createGraphics();
        g.drawImage(decoded, 0, 0, null);
        g.dispose();

        if (shouldSkipAutoCrop(entryPath, width, height, preset)) {
            return new Source(sourceCanvas, width, height, false);
        }

        int[] pixels = sourceCanvas.getRGB(0, 0, width, height, null, 0, width);
        Rect crop = findNonWhiteBounds(pixels, width, height);
        if (crop == null) {
            return new Source(sourceCanvas, width, height, false);
        }

        BufferedImage cropped = whiteCanvas(crop.width, crop.height);
        Graphics2D cg = cropped.createGraphics();
        cg.drawImage(sourceCanvas.getSubimage(crop.x, crop.y, crop.width, crop.height), 0, 0, null);
        cg.dispose();
        return new Source(cropped, crop.width, crop.height, true);
    }

    private boolean shouldSkipAutoCrop(String entryPath, int width, int height, DevicePreset preset) {
        if (!preset.isAutoCrop()) return true;
        if (width < MIN_CROP_DIMENSION || height < MIN_CROP_DIMENSION) return true;
        return entryPath != null && COVER_ICON_PATTERN.matcher(entryPath).find();
    }

    private record Rect(int x, int y, int width, int height) {
    }

    private record Rgb(double r, double g, double b) {
    }

    private static Rgb pixel(int[] data, int idx) {
        int rgb = data[idx];
        return new Rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private Rgb estimateBackground(int[] data, int width, int height) {
        int sampleSize = Math.min(CROP_EDGE_SAMPLE_SIZE, Math.min(width / 8, height / 8));
        if (sampleSize < 2) return null;

        int[][] points = {
                {0, 0},
                {width - sampleSize, 0},
                {0, height - sampleSize},
                {width - sampleSize, height - sampleSize},
                {(width - sampleSize) / 2, 0},
                {(width - sampleSize) / 2, height - sampleSize},
                {0, (height - sampleSize) / 2},
                {width - sampleSize, (height - sampleSize) / 2}
        };

        double[] sampleR = new double[points.length];
        double[] sampleG = new double[points.length];
        double[] sampleB = new double[points.length];
        for (int p = 0; p < points.length; p++) {
            int startX = points[p][0];
            int startY = points[p][1];
            double r = 0, gr = 0, b = 0;
            int count = 0;
            for (int y = startY; y < startY + sampleSize; y++) {
                for (int x = startX; x < startX + sampleSize; x++) {
                    Rgb px = pixel(data, y * width + x);
                    r += px.r;
                    gr += px.g;
                    b += px.b;
                    count++;
                }
            }
            sampleR[p] = r / count;
            sampleG[p] = gr / count;
            sampleB[p] = b / count;
        }

        double avgR = 0, avgG = 0, avgB = 0;
        for (int p = 0; p < points.length; p++) {
            avgR += sampleR[p];
            avgG += sampleG[p];
            avgB += sampleB[p];
        }
        avgR /= points.length;
        avgG /= points.length;
        avgB /= points.length;

        double maxSpread = 0;
        for (int p = 0; p < points.length; p++) {
            maxSpread = Math.max(maxSpread, Math.abs(sampleR[p] - avgR));
            maxSpread = Math.max(maxSpread, Math.abs(sampleG[p] - avgG));
            maxSpread = Math.max(maxSpread, Math.abs(sampleB[p] - avgB));
        }

        if (maxSpread > CROP_BACKGROUND_MAX_SPREAD) return null;
        return new Rgb(avgR, avgG, avgB);
    }

    private static boolean isContentPixel(Rgb px, Rgb background) {
        if (background != null) {
            return Math.abs(px.r - background.r) > CROP_BACKGROUND_TOLERANCE
                    || Math.abs(px.g - background.g) > CROP_BACKGROUND_TOLERANCE
                    || Math.abs(px.b - background.b) > CROP_BACKGROUND_TOLERANCE;
        }
        return px.r < CROP_WHITE_THRESHOLD || px.g < CROP_WHITE_THRESHOLD || px.b < CROP_WHITE_THRESHOLD;
    }

    private static boolean isNearWhite(Rgb background) {
        return background != null
                && background.r >= CROP_WHITE_THRESHOLD
                && background.g >= CROP_WHITE_THRESHOLD
                && background.b >= CROP_WHITE_THRESHOLD;
    }

    private Rect findNonWhiteBounds(int[] data, int width, int height) {
        Rgb background = estimateBackground(data, width, height);
        int left = width, top = height, right = -1, bottom = -1;

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                Rgb px = pixel(data, rowOffset + x);
                if (isContentPixel(px, background)) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }

        if (right < left || bottom < top) return null;

        left = Math.max(0, left - CROP_PADDING_PX);
        top = Math.max(0, top - CROP_PADDING_PX);
        right = Math.min(width - 1, right + CROP_PADDING_PX);
        bottom = Math.min(height - 1, bottom + CROP_PADDING_PX);

        int cropW = right - left + 1;
        int cropH = bottom - top + 1;
        double savedRatio = 1.0 - ((double) (cropW * cropH) / (width * height));
        double minSavedRatio = (background != null && !isNearWhite(background))
                ? MIN_COLOR_CROP_SAVINGS_RATIO
                : MIN_CROP_SAVINGS_RATIO;
        if (savedRatio < minSavedRatio) return null;

        return new Rect(left, top, cropW, cropH);
    }
}
