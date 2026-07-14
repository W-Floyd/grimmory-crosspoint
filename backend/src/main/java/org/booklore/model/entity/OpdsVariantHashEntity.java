package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Maps the partial-MD5 (KOReader document id) of a device-optimized EPUB variant back to
 * its source book. When the OPDS server serves an optimized file via {@code ?preset=}, the
 * bytes differ from the library original, so its hash differs too. Registering the variant
 * hash here lets KOReader progress sync resolve the book even though the reader computed the
 * hash over the optimized copy.
 */
@Entity
@Table(name = "opds_variant_hash",
        uniqueConstraints = @UniqueConstraint(name = "uk_opds_variant_hash_file_preset", columnNames = {"book_file_id", "preset"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpdsVariantHashEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private BookEntity book;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_file_id", nullable = false)
    private BookFileEntity bookFile;

    @Column(name = "preset", nullable = false, length = 64)
    private String preset;

    /** Partial-MD5 of the optimized variant (matches the reader's KOReader document id). */
    @Column(name = "variant_hash", nullable = false, length = 128)
    private String variantHash;

    /** Source file hash the variant was generated from; used to detect staleness. */
    @Column(name = "source_hash", length = 128)
    private String sourceHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
