package org.booklore.service.opds.optimization;

import lombok.RequiredArgsConstructor;
import org.booklore.config.OpdsDeviceProperties;
import org.booklore.model.dto.opds.DevicePreset;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Single source of truth for resolving OPDS device presets configured under
 * {@code app.opds.device-presets}. Lookups are case-insensitive on the device id.
 */
@Service
@RequiredArgsConstructor
public class DevicePresetService {

    private final OpdsDeviceProperties properties;

    /**
     * Resolve a preset by device id (case-insensitive). Returns empty for a blank or
     * unknown id.
     */
    public Optional<DevicePreset> resolve(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return properties.getDevicePresets().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(id.trim()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** Canonical id (matching configuration key) for a case-insensitive lookup. */
    public Optional<String> resolveId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return properties.getDevicePresets().keySet().stream()
                .filter(k -> k.equalsIgnoreCase(id.trim()))
                .findFirst();
    }

    /** All configured presets keyed by device id, in declaration order. */
    public Map<String, DevicePreset> all() {
        return properties.getDevicePresets();
    }

    public boolean hasPresets() {
        return properties.getDevicePresets() != null && !properties.getDevicePresets().isEmpty();
    }

    public long maxSourceFileSizeBytes() {
        int mb = properties.getMaxSourceFileSizeMb();
        return mb <= 0 ? Long.MAX_VALUE : (long) mb * 1024 * 1024;
    }
}
