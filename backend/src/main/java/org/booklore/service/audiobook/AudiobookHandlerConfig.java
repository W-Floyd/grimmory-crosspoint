package org.booklore.service.audiobook;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the external OverDrive audiobook handler tool. Bound from application.yaml under
 * {@code app.audiobook}.
 */
@Component
@ConfigurationProperties(prefix = "app.audiobook")
@Getter
@Setter
public class AudiobookHandlerConfig {
    /** Whether an external audiobook handler is configured. */
    private boolean enabled = false;

    /** Path to the tool binary/script (e.g. "/usr/local/bin/od-audiobook", "myscript.sh"). */
    private String toolPath = "";

    /**
     * Arguments passed to the tool. Placeholders: {@code {input}} (a JSON manifest carrying the chip
     * token + loan/card/format ids), {@code {output}} (an empty directory into which the tool writes the
     * single assembled audiobook file). Default: {@code "--manifest {input} --out-dir {output}"}
     */
    private String toolArgs = "--manifest {input} --out-dir {output}";

    /** Timeout in seconds for the tool process (audiobooks are large — default 1800). */
    private int timeoutSeconds = 1800;
}
