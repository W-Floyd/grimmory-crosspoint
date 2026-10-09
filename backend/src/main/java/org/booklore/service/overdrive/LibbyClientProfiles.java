package org.booklore.service.overdrive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.booklore.service.overdrive.LibbyClientProfile.ShibbolethScheme.REVERSED_ALPHA_AT_PATH_LENGTH;

/**
 * Every Libby build this deployment knows how to speak as.
 *
 * <p>Adding a version is a deliberate act: archive it with scripts/libby-client, diff it against the
 * last supported build, and add it here only once nothing it changed affects us. Until then
 * {@link LibbyClientVersionGuard} stops all Libby traffic, because announcing a build OverDrive no
 * longer serves is a signature in its own right.
 *
 * <p>scripts/libby-client/bin/libby-current reads the versions out of this file.
 */
public final class LibbyClientProfiles {

    private static final Map<String, LibbyClientProfile> SUPPORTED = new LinkedHashMap<>();

    static {
        // Chip query shape confirmed from a browser capture (anonymous mint, 2026-07-16).
        add(new LibbyClientProfile("22.0.2", REVERSED_ALPHA_AT_PATH_LENGTH));
        // Re-mint confirmed from a browser capture, 2026-09-22. Shibboleth constants rotated, shape intact.
        add(new LibbyClientProfile("22.1.1", REVERSED_ALPHA_AT_PATH_LENGTH));
        // Bundle only, no capture: nothing on the wire changed from 22.1.1 (2026-10-09).
        add(new LibbyClientProfile("22.1.2", REVERSED_ALPHA_AT_PATH_LENGTH));
    }

    private LibbyClientProfiles() {
    }

    private static void add(LibbyClientProfile profile) {
        SUPPORTED.put(profile.version(), profile);
    }

    public static Optional<LibbyClientProfile> forVersion(String version) {
        return Optional.ofNullable(version).map(SUPPORTED::get);
    }

    public static List<String> supportedVersions() {
        return List.copyOf(SUPPORTED.keySet());
    }
}
