package org.booklore.model.dto.settings;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OverDrive-specific application (deploy-level) properties, bound from application.yaml under
 * {@code app.overdrive}. This is the operator feature switch for the OverDrive integration;
 * per-user catalog settings (library key) live in the metadata provider settings instead.
 */
@Component
@ConfigurationProperties(prefix = "app.overdrive")
@Getter
@Setter
public class OverdriveProperties {
    /** Whether the OverDrive integration (borrow/import/sync endpoints) is enabled. */
    private boolean enabled = false;
    /** Libby/OverDrive sentry base URL. */
    private String sentryBaseUrl = "https://sentry.libbyapp.com";
    /** OverDrive client ID (defaults to "dewey"). */
    private String clientId = "dewey";
}
