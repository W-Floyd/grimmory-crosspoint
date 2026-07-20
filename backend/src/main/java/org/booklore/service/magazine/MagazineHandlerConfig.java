package org.booklore.service.magazine;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the external OverDrive magazine handler tool. Bound from application.yaml under
 * {@code app.magazine}. Mirrors {@code app.audiobook}: the same manifest contract and (typically) the
 * same tool binary, but a separate config block so magazines can be enabled/pointed independently.
 */
@Component
@ConfigurationProperties(prefix = "app.magazine")
@Getter
@Setter
public class MagazineHandlerConfig {
    /** Whether an external magazine handler is configured. */
    private boolean enabled = false;

    /** Path to the tool binary/script (typically the same go-od binary the audiobook handler uses). */
    private String toolPath = "";

    /**
     * Arguments passed to the tool. Placeholders: {@code {input}} (a JSON manifest carrying the card
     * credentials + loan ids), {@code {output}} (an empty directory into which the tool writes the single
     * produced file). go-od is a subcommand CLI, so the default leads with {@code download}. Pin a stable
     * {@code --account-dir} (see the deploy compose notes).
     */
    private String toolArgs = "download --manifest {input} --out-dir {output}";

    /** Timeout in seconds for the tool process (magazines are smaller than audiobooks — default 600). */
    private int timeoutSeconds = 600;
}
