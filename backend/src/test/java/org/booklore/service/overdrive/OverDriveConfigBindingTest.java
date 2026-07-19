package org.booklore.service.overdrive;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that OverDrive env vars are actually wired to the properties the code reads. A missing
 * mapping silently disables the feature (e.g. credential storage / audiobook downloads stayed off
 * because app.overdrive.credential-key was never bound to OVERDRIVE_CREDENTIAL_KEY).
 */
class OverDriveConfigBindingTest {

    private static Properties applicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties props = yaml.getObject();
        assertThat(props).isNotNull();
        return props;
    }

    @Test
    void credentialKeyIsWiredToOverdriveCredentialKeyEnvVar() {
        // OverDriveCredentialCipher reads ${app.overdrive.credential-key}; it must resolve the
        // OVERDRIVE_CREDENTIAL_KEY env var, or credential storage never turns on despite being set.
        assertThat(applicationYaml().getProperty("app.overdrive.credential-key"))
                .isEqualTo("${OVERDRIVE_CREDENTIAL_KEY:}");
    }

    @Test
    void audiobookHandlerIsWiredToEnvVars() {
        Properties p = applicationYaml();
        assertThat(p.getProperty("app.audiobook.enabled")).isEqualTo("${AUDIOBOOK_ENABLED:false}");
        assertThat(p.getProperty("app.audiobook.tool-path")).isEqualTo("${AUDIOBOOK_TOOL_PATH:}");
    }
}
