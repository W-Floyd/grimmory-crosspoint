package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.opds.DevicePreset;
import org.booklore.model.dto.opds.DevicePresetSummary;
import org.booklore.service.opds.optimization.DevicePresetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Exposes the configured OPDS device-optimization presets to the (app-authenticated)
 * settings UI. Lives outside the {@code /api/v1/opds/**} space so it uses the standard
 * app auth chain rather than OPDS basic auth.
 */
@Tag(name = "OPDS Device Presets", description = "Configured OPDS device-optimization presets")
@RestController
@RequestMapping("/api/v1/opds-device-presets")
@RequiredArgsConstructor
public class DevicePresetController {

    private final DevicePresetService devicePresetService;

    @Operation(summary = "List configured OPDS device presets")
    @ApiResponse(responseCode = "200", description = "Device presets returned successfully")
    @GetMapping
    public ResponseEntity<List<DevicePresetSummary>> getDevicePresets() {
        List<DevicePresetSummary> presets = devicePresetService.all().entrySet().stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(presets);
    }

    private DevicePresetSummary toSummary(Map.Entry<String, DevicePreset> entry) {
        DevicePreset p = entry.getValue();
        String label = (p.getLabel() != null && !p.getLabel().isBlank()) ? p.getLabel() : entry.getKey();
        return new DevicePresetSummary(entry.getKey(), label, p.getMaxWidth(), p.getMaxHeight(),
                p.getJpegQuality(), p.isGrayscale());
    }
}
