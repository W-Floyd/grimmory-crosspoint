package org.booklore.util;

import io.airlift.compress.v3.zstd.ZstdInputStream;
import org.brotli.dec.BrotliInputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Decodes a compressed HTTP response body by its Content-Encoding.
 *
 * <p>Neither the JDK HttpClient nor the app's RestClient decodes on its own, so a request that sends
 * Accept-Encoding gets bytes nothing can parse. Covers everything Firefox advertises — gzip, deflate,
 * br and zstd — so a server switching encodings changes nothing for us; anything else fails loudly
 * rather than handing a parser compressed bytes.
 */
public final class ContentDecoding {

    private static final Set<String> SUPPORTED = Set.of("gzip", "x-gzip", "deflate", "br", "zstd");

    private ContentDecoding() {
    }

    public static InputStream decode(InputStream body, String contentEncoding) throws IOException {
        String encoding = contentEncoding == null ? "" : contentEncoding.trim().toLowerCase(Locale.ROOT);
        if (encoding.isEmpty() || encoding.equals("identity")) {
            return body;
        }
        if (!SUPPORTED.contains(encoding)) {
            throw new IOException("Unsupported Content-Encoding: " + contentEncoding);
        }
        // An empty body (204, or a HEAD) still carries the header; the decoders reject it as truncated.
        PushbackInputStream in = body instanceof PushbackInputStream p ? p : new PushbackInputStream(body);
        int first = in.read();
        if (first == -1) {
            return InputStream.nullInputStream();
        }
        in.unread(first);
        return switch (encoding) {
            case "deflate" -> new InflaterInputStream(in);
            case "br" -> new BrotliInputStream(in);
            case "zstd" -> new ZstdInputStream(in);
            default -> new GZIPInputStream(in);
        };
    }

    public static byte[] decode(byte[] body, String contentEncoding) throws IOException {
        if (body == null) {
            return null;
        }
        try (InputStream in = decode(new ByteArrayInputStream(body), contentEncoding)) {
            return in.readAllBytes();
        }
    }

    /** JDK HttpClient body handler: the decoded bytes. */
    public static HttpResponse.BodyHandler<byte[]> decodedBytes() {
        return info -> HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray(), bytes -> {
            try {
                return decode(bytes, info.headers().firstValue("Content-Encoding").orElse(""));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** JDK HttpClient body handler: the decoded body as UTF-8 text. */
    public static HttpResponse.BodyHandler<String> decodedString() {
        return info -> HttpResponse.BodySubscribers.mapping(decodedBytes().apply(info),
                bytes -> bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * RestClient interceptor that decodes a compressed response and drops the headers describing the
     * encoded form. It never asks for compression itself, so traffic that doesn't send Accept-Encoding
     * is unchanged.
     */
    public static ClientHttpRequestInterceptor restClientInterceptor() {
        return (request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            String encoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
            if (encoding == null || encoding.isBlank() || "identity".equalsIgnoreCase(encoding.trim())) {
                return response;
            }
            return new DecodedResponse(response, encoding);
        };
    }

    private static final class DecodedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final String encoding;
        private final HttpHeaders headers;
        private InputStream body;

        DecodedResponse(ClientHttpResponse delegate, String encoding) {
            this.delegate = delegate;
            this.encoding = encoding;
            this.headers = HttpHeaders.copyOf(delegate.getHeaders());
            this.headers.remove(HttpHeaders.CONTENT_ENCODING);
            this.headers.remove(HttpHeaders.CONTENT_LENGTH);
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                body = decode(delegate.getBody(), encoding);
            }
            return body;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
