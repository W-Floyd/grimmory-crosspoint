package org.booklore.service.opds.optimization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.OpdsVariantHashEntity;
import org.booklore.repository.OpdsVariantHashRepository;
import org.booklore.service.file.FileFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Registers and resolves the partial-MD5 hashes of device-optimized EPUB variants so KOReader
 * progress sync can match a book even when the reader hashed the optimized copy (whose bytes
 * differ from the library original). See {@link OpdsVariantHashEntity}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpdsVariantHashService {

    private final OpdsVariantHashRepository repository;

    /** Resolve the source book for a KOReader document id that belongs to an optimized variant. */
    @Transactional(readOnly = true)
    public Optional<BookEntity> findBookByVariantHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return Optional.empty();
        }
        return repository.findBookByVariantHash(hash);
    }

    /**
     * Ensure the variant hash for {@code (bookFile, preset)} is registered against the book.
     * Idempotent: recomputes the hash only when the row is missing or the source file changed.
     * Failures are swallowed (logged) so they never break the download.
     *
     * @param sourceHash   hash of the original file the variant was generated from
     * @param variantFile  the optimized artifact actually served to the reader
     */
    @Transactional
    public void register(BookFileEntity bookFile, String preset, String sourceHash, Path variantFile) {
        try {
            Optional<OpdsVariantHashEntity> existing =
                    repository.findByBookFile_IdAndPreset(bookFile.getId(), preset);
            if (existing.isPresent() && Objects.equals(existing.get().getSourceHash(), sourceHash)) {
                return; // already up to date
            }

            String variantHash = FileFingerprint.generateHash(variantFile);
            OpdsVariantHashEntity entity = existing.orElseGet(OpdsVariantHashEntity::new);
            entity.setBook(bookFile.getBook());
            entity.setBookFile(bookFile);
            entity.setPreset(preset);
            entity.setVariantHash(variantHash);
            entity.setSourceHash(sourceHash);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            log.debug("Registered OPDS variant hash {} for book {} file {} preset {}",
                    variantHash, bookFile.getBook().getId(), bookFile.getId(), preset);
        } catch (Exception e) {
            log.warn("Failed to register OPDS variant hash for file {} preset {}: {}",
                    bookFile.getId(), preset, e.getMessage());
        }
    }
}
