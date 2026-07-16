package org.booklore.service.opds.optimization;

import org.booklore.config.OpdsDeviceProperties;
import org.booklore.model.dto.opds.DevicePreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DevicePresetServiceTest {

    private DevicePresetService service;

    @BeforeEach
    void setUp() {
        OpdsDeviceProperties props = new OpdsDeviceProperties();
        Map<String, DevicePreset> presets = new LinkedHashMap<>();
        presets.put("X4", preset("X4", 480, 800));
        presets.put("X3", preset("X3", 528, 792));
        props.setDevicePresets(presets);
        props.setMaxSourceFileSizeMb(50);
        service = new DevicePresetService(props);
    }

    private DevicePreset preset(String label, int w, int h) {
        DevicePreset p = new DevicePreset();
        p.setLabel(label);
        p.setMaxWidth(w);
        p.setMaxHeight(h);
        return p;
    }

    @Test
    void resolve_isCaseInsensitive() {
        assertThat(service.resolve("x3")).isPresent();
        assertThat(service.resolve("X3")).isPresent();
        assertThat(service.resolve("x3").get().getMaxWidth()).isEqualTo(528);
    }

    @Test
    void resolve_unknownOrBlankIsEmpty() {
        assertThat(service.resolve("nope")).isEmpty();
        assertThat(service.resolve("")).isEmpty();
        assertThat(service.resolve(null)).isEmpty();
    }

    @Test
    void resolveId_returnsCanonicalKey() {
        assertThat(service.resolveId("x4")).contains("X4");
        assertThat(service.resolveId("unknown")).isEmpty();
    }

    @Test
    void hasPresets_and_all() {
        assertThat(service.hasPresets()).isTrue();
        assertThat(service.all()).containsKeys("X3", "X4");
    }

    @Test
    void displayName_prefersLabelThenBrandModel() {
        DevicePreset explicit = new DevicePreset();
        explicit.setLabel("Custom Name");
        explicit.setBrand("Xteink");
        explicit.setModel("X4");
        assertThat(explicit.displayName()).isEqualTo("Custom Name");

        DevicePreset brandModel = new DevicePreset();
        brandModel.setBrand("Xteink");
        brandModel.setModel("X4");
        assertThat(brandModel.displayName()).isEqualTo("Xteink X4");

        assertThat(new DevicePreset().displayName()).isNull();
    }

    @Test
    void maxSourceFileSizeBytes_convertsMb_andDisablesWhenNonPositive() {
        assertThat(service.maxSourceFileSizeBytes()).isEqualTo(50L * 1024 * 1024);

        OpdsDeviceProperties unlimited = new OpdsDeviceProperties();
        unlimited.setMaxSourceFileSizeMb(0);
        assertThat(new DevicePresetService(unlimited).maxSourceFileSizeBytes()).isEqualTo(Long.MAX_VALUE);
    }
}
