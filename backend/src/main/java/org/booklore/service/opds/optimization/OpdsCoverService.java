package org.booklore.service.opds.optimization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.opds.DevicePreset;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
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
     * Return a re-encoded cover for the preset, or empty if it cannot be produced (caller
     * should then serve the original untouched).
     */
    public Optional<Resource> reencode(Resource cover, DevicePreset preset) {
        try (InputStream in = cover.getInputStream()) {
            byte[] source = in.readAllBytes();
            // "cover.jpg" path keeps auto-crop off (covers are exempt) — just fit + grayscale + baseline JPEG.
            EpubImageProcessor.ProcessedImage processed = imageProcessor.process(source, "cover.jpg", preset);
            return Optional.of(new ByteArrayResource(processed.data()) {
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
}
