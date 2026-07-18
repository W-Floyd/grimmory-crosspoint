package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cover images for an OverDrive book. The set of sizes varies per title, so we capture every rendition
 * the API returns (keyed by its OverDrive name, e.g. {@code cover510Wide}) instead of hardcoding a
 * fixed list; the largest is chosen at read time.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveCover {
    private final Map<String, OverDriveCoverDetail> variants = new LinkedHashMap<>();

    @JsonAnySetter
    void putVariant(String name, OverDriveCoverDetail detail) {
        if (detail != null) {
            variants.put(name, detail);
        }
    }
}