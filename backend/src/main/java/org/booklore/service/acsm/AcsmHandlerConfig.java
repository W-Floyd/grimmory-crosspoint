package org.booklore.service.acsm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the external ACSM handler tool.
 * Bound from application.yaml under app.acsm.
 */
@Component
@ConfigurationProperties(prefix = "app.acsm")
@Getter
@Setter
public class AcsmHandlerConfig {
    /** Whether an external ACSM handler is configured. */
    private boolean enabled = false;

    /** Path to the tool binary/script (e.g. "/usr/local/bin/acsm-tool", "myscript.sh"). */
    private String toolPath = "";

    /** Arguments passed to the tool. Placeholders: {acsm} (input ACSM), {output} (output book file).
     *  Default: "--acsm {acsm} --output {output}" */
    private String toolArgs = "--acsm {acsm} --output {output}";

    /** Timeout in seconds for the tool process. Default: 120. */
    private int timeoutSeconds = 120;
}
