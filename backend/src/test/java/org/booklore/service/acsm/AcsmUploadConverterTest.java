package org.booklore.service.acsm;

import org.booklore.exception.APIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AcsmUploadConverterTest {

    private final AcsmHandler handler = mock(AcsmHandler.class);
    private final AcsmUploadConverter converter = new AcsmUploadConverter(handler);

    @Test
    void anythingButAnAcsmPassesThroughUntouched() {
        MultipartFile epub = new MockMultipartFile("file", "Dune.epub", null, new byte[]{1});

        assertThat(converter.materializeIfAcsm(epub)).isSameAs(epub);
        verifyNoInteractions(handler);
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @CsvSource({
            "Dune.acsm, 'PK', Dune.epub",
            "../dir/Report.ACSM, '%PDF-1.7', Report.pdf",
    })
    void anAcsmBecomesTheBookItProduces(String uploaded, String produced, String expectedName) throws Exception {
        byte[] book = produced.getBytes(StandardCharsets.US_ASCII);
        when(handler.isConfigured()).thenReturn(true);
        when(handler.handle(any())).thenReturn(book);

        MultipartFile converted = converter.materializeIfAcsm(new MockMultipartFile("file", uploaded, null, new byte[]{9}));

        assertThat(converted.getOriginalFilename()).isEqualTo(expectedName);
        assertThat(converted.getBytes()).isEqualTo(book);
        assertThat(converted.getSize()).isEqualTo(book.length);
    }

    @ParameterizedTest(name = "configured={0}")
    @CsvSource({"false", "true"})
    void anAcsmThatCannotBeConvertedIsRefused(boolean configured) {
        when(handler.isConfigured()).thenReturn(configured);
        when(handler.handle(any())).thenReturn(new byte[0]);

        assertThatThrownBy(() -> converter.materializeIfAcsm(new MockMultipartFile("file", "Dune.acsm", null, new byte[]{9})))
                .isInstanceOf(APIException.class);
    }
}
