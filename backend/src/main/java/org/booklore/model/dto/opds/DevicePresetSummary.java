package org.booklore.model.dto.opds;

/**
 * Read-only view of a configured OPDS device preset, exposed to the settings UI so it can
 * list the presets a client may request via {@code ?preset=<id>}.
 */
public record DevicePresetSummary(
        String id,
        String label,
        int maxWidth,
        int maxHeight,
        int jpegQuality,
        boolean grayscale
) {
}
