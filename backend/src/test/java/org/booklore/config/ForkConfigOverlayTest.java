package org.booklore.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/** The fork's settings live in config/application.yaml; this is what proves Spring still reads them. */
@SpringBootTest(classes = ForkConfigOverlayTest.Empty.class)
class ForkConfigOverlayTest {

    @Configuration
    static class Empty {
    }

    @Autowired
    private Environment environment;

    @Test
    void forkSettingsLoadAlongsideUpstreams() {
        assertThat(environment.getProperty("app.overdrive.client-id")).isEqualTo("dewey");
        assertThat(environment.getProperty("app.opds.device-presets.xteink-x4.max-width")).isEqualTo("480");
        assertThat(environment.getProperty("spring.flyway.out-of-order", Boolean.class)).isTrue();
        // Upstream's own file still loads too.
        assertThat(environment.getProperty("app.disk-type")).isNotNull();
    }
}
