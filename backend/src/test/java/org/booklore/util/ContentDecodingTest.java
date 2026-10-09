package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentDecodingTest {

    private static final byte[] JSON = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

    private static byte[] gzip(byte[] in) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(in);
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] in) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var d = new DeflaterOutputStream(out)) {
            d.write(in);
        }
        return out.toByteArray();
    }

    @Test
    void decodesGzipAndDeflate() throws IOException {
        assertThat(ContentDecoding.decode(gzip(JSON), "gzip")).isEqualTo(JSON);
        assertThat(ContentDecoding.decode(gzip(JSON), " GZIP ")).isEqualTo(JSON);
        assertThat(ContentDecoding.decode(deflate(JSON), "deflate")).isEqualTo(JSON);
    }

    @Test
    void decodesBrotliAndZstdFromTheReferenceEncoders() throws IOException {
        // Produced by the brotli and zstd command-line tools, so this is interop, not a round trip.
        byte[] br = Base64.getDecoder().decode("ITgABHsicmVzdWx0Ijoib2sifQM=");
        byte[] zstd = Base64.getDecoder().decode("KLUv/SQPeQAAeyJyZXN1bHQiOiJvayJ9Gy8qww==");

        assertThat(ContentDecoding.decode(br, "br")).isEqualTo(JSON);
        assertThat(ContentDecoding.decode(zstd, "zstd")).isEqualTo(JSON);
    }

    @Test
    void passesUnencodedBodiesThrough() throws IOException {
        assertThat(ContentDecoding.decode(JSON, "")).isEqualTo(JSON);
        assertThat(ContentDecoding.decode(JSON, null)).isEqualTo(JSON);
        assertThat(ContentDecoding.decode(JSON, "identity")).isEqualTo(JSON);
    }

    @Test
    void anEmptyCompressedBodyIsEmptyNotTruncated() throws IOException {
        // A 204 can still carry Content-Encoding: gzip; GZIPInputStream would call it an EOF.
        assertThat(ContentDecoding.decode(new byte[0], "gzip")).isEmpty();
    }

    @Test
    void anEncodingWeCannotDecodeFailsLoudly() {
        assertThatThrownBy(() -> ContentDecoding.decode(JSON, "compress"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("compress");
    }

    @Test
    void theInterceptorDecodesAndDropsTheEncodedFormsHeaders() throws IOException {
        byte[] compressed = gzip(JSON);
        var upstream = new MockClientHttpResponse(compressed, HttpStatus.OK);
        upstream.getHeaders().set(HttpHeaders.CONTENT_ENCODING, "gzip");
        upstream.getHeaders().setContentLength(compressed.length);

        ClientHttpResponse response = ContentDecoding.restClientInterceptor().intercept(
                new MockClientHttpRequest(HttpMethod.GET, URI.create("https://example.test/")), new byte[0],
                (req, body) -> upstream);

        assertThat(response.getBody().readAllBytes()).isEqualTo(JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isNull();
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)).isNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theInterceptorLeavesAnUnencodedResponseAlone() throws IOException {
        var upstream = new MockClientHttpResponse(JSON, HttpStatus.OK);

        ClientHttpResponse response = ContentDecoding.restClientInterceptor().intercept(
                new MockClientHttpRequest(HttpMethod.GET, URI.create("https://example.test/")), new byte[0],
                (req, body) -> upstream);

        assertThat(response).isSameAs(upstream);
    }
}
