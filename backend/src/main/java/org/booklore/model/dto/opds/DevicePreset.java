package org.booklore.model.dto.opds;

import lombok.Getter;
import lombok.Setter;

/**
 * A device optimization profile for the OPDS server. Presets describe how an EPUB
 * should be re-rendered for a specific low-resource e-reader (screen dimensions,
 * grayscale, JPEG quality). They are populated from configuration
 * ({@code app.opds.device-presets}) so new devices can be added without a recompile.
 * <p>
 * The defaults mirror the Crosspoint firmware's {@code DEVICE_PROFILES} and its
 * automatic EPUB image conversion (see {@code FilesPage.html}).
 */
@Getter
@Setter
public class DevicePreset {

    /** Device brand/manufacturer, e.g. {@code Xteink}. */
    private String brand;

    /** Device model, e.g. {@code X4}. */
    private String model;

    /**
     * Optional explicit display name. When blank, the display name is derived from
     * {@code brand} + {@code model} (see {@link #displayName()}), falling back to the preset id.
     */
    private String label;

    /** Maximum image width in pixels (portrait short edge). */
    private int maxWidth = 480;

    /** Maximum image height in pixels (portrait long edge). */
    private int maxHeight = 800;

    /** JPEG quality applied when re-encoding images, 1-100. */
    private int jpegQuality = 85;

    /** Convert images to true grayscale (BT.601 luminance). */
    private boolean grayscale = true;

    /** Opt-in auto-crop of uniform margins before scaling. */
    private boolean autoCrop = false;

    /**
     * Minimum overlap percentage used when auto-splitting wide images. Reserved for a
     * future auto-split heuristic; unused by the current automatic (STATE-0) pipeline.
     */
    private int overlapPercent = 5;

    /**
     * Rotation handedness ("right" = clockwise) used when splitting/rotating. Reserved
     * for a future auto-split heuristic; unused by the current automatic pipeline.
     */
    private String handedness = "right";

    /**
     * Human-readable name for feeds/UI: an explicit {@link #label} if set, otherwise
     * {@code "<brand> <model>"}, or {@code null} when none of those are configured (callers
     * then fall back to the preset id).
     */
    public String displayName() {
        if (label != null && !label.isBlank()) {
            return label;
        }
        String combined = ((brand == null ? "" : brand) + " " + (model == null ? "" : model)).trim();
        return combined.isBlank() ? null : combined;
    }
}
