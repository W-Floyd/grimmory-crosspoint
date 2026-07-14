package org.booklore.config;

import lombok.Getter;
import lombok.Setter;
import org.booklore.model.dto.opds.DevicePreset;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for OPDS device-optimization presets, bound from
 * {@code app.opds.*}. Each entry under {@code device-presets} is keyed by a device
 * id (e.g. {@code X3}, {@code X4}) that OPDS clients pass as {@code ?preset=<id>}.
 */
@ConfigurationProperties(prefix = "app.opds")
@Getter
@Setter
public class OpdsDeviceProperties {

    /** Device presets keyed by device id (case-insensitive on lookup). */
    private Map<String, DevicePreset> devicePresets = new LinkedHashMap<>();

    /**
     * EPUBs larger than this (in MB) are served unoptimized to bound memory/CPU.
     * Zero or negative disables the limit.
     */
    private int maxSourceFileSizeMb = 100;
}
