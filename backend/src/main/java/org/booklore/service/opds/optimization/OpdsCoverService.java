package org.booklore.service.opds.optimization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.opds.DevicePreset;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Re-encodes OPDS cover images into constrained-device-friendly baseline JPEG for a device
 * preset. Some e-ink JPEG decoders (e.g. picojpeg on Xteink X3/X4) silently fail on otherwise
 * valid covers (progressive encoding, embedded ICC profiles); re-encoding via
 * {@link EpubImageProcessor} yields a plain baseline JPEG (no colour profile, standard
 * subsampling), sized and grayscaled per the preset.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpdsCoverService {

    private final EpubImageProcessor imageProcessor;

    /**
     * A device fetches a feed page's covers together, every time it opens the page. Keyed by everything
     * that changes the output, so a new cover or an edited preset is a miss rather than a stale hit.
     */
    private final Cache<CoverKey, byte[]> encoded = Caffeine.newBuilder()
            .maximumWeight(32L * 1024 * 1024)
            .weigher((CoverKey key, byte[] jpeg) -> jpeg.length)
            .build();

    private record CoverKey(String cover, long lastModified, long length,
                            int maxWidth, int maxHeight, int jpegQuality, boolean grayscale) {}

    /**
     * Return a re-encoded cover for the preset, or empty if it cannot be produced (caller
     * should then serve the original untouched).
     */
    public Optional<Resource> reencode(Resource cover, DevicePreset preset) {
        try {
            CoverKey key = keyFor(cover, preset);
            byte[] jpeg = key != null ? encoded.get(key, k -> encode(cover, preset)) : encode(cover, preset);
            return Optional.of(new ByteArrayResource(jpeg) {
                @Override
                public String getFilename() {
                    return "cover.jpg";
                }
            });
        } catch (Exception e) {
            log.debug("Cover re-encode failed for preset {}: {}", preset.getLabel(), e.getMessage());
            return Optional.empty();
        }
    }

    private byte[] encode(Resource cover, DevicePreset preset) {
        try (InputStream in = cover.getInputStream()) {
            // "cover.jpg" path keeps auto-crop off (covers are exempt) — just fit + grayscale + baseline JPEG.
            return imageProcessor.process(in.readAllBytes(), "cover.jpg", preset).data();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Null when the cover's version can't be told, which is then simply not cached. */
    private static CoverKey keyFor(Resource cover, DevicePreset preset) {
        try {
            return new CoverKey(cover.getURI().toString(), cover.lastModified(), cover.contentLength(),
                    preset.getMaxWidth(), preset.getMaxHeight(), preset.getJpegQuality(), preset.isGrayscale());
        } catch (IOException e) {
            return null;
        }
    }
}
