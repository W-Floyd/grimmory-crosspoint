package org.booklore.service.audiobook;

import org.booklore.exception.APIException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudiobookHandlerTest {

    @Test
    void notConfigured_isNotConfiguredAndHandleThrows() {
        AudiobookHandlerConfig config = new AudiobookHandlerConfig(); // enabled=false, no tool path
        AudiobookHandler handler = new AudiobookHandler(config);

        assertThat(handler.isConfigured()).isFalse();
        assertThatThrownBy(() -> handler.handle(new AudiobookHandler.Request(
                "https://sentry.libbyapp.com", "1234567890", "9999", "mcpl", "websiteId-1", "ilsName-1",
                "card-1", "title-1", "audiobook-mp3")))
                .isInstanceOf(APIException.class);
    }

    @Test
    void enabledWithToolPath_isConfigured() {
        AudiobookHandlerConfig config = new AudiobookHandlerConfig();
        config.setEnabled(true);
        config.setToolPath("/usr/local/bin/od-audiobook");

        assertThat(new AudiobookHandler(config).isConfigured()).isTrue();
    }
}
