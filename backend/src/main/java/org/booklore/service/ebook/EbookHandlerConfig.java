package org.booklore.service.ebook;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the external OverDrive ebook handler tool, used for titles offered only in
 * Libby's read-in-browser {@code ebook-overdrive} format. Bound from application.yaml under
 * {@code app.ebook}.
 */
@Component
@ConfigurationProperties(prefix = "app.ebook")
@Getter
@Setter
public class EbookHandlerConfig {
    /** Whether an external ebook handler is configured. */
    private boolean enabled = false;

    /** Path to the tool binary/script (usually the same binary as the audiobook/magazine handler). */
    private String toolPath = "";

    /**
     * Arguments passed to the tool. Placeholders: {@code {input}} (a JSON manifest carrying the card
     * credentials + loan/card/format ids), {@code {output}} (an empty directory into which the tool
     * writes the single produced book file).
     */
    private String toolArgs = "download --manifest {input} --out-dir {output}";

    /** Timeout in seconds for the tool process (an ebook is small next to an audiobook — default 600). */
    private int timeoutSeconds = 600;
}
