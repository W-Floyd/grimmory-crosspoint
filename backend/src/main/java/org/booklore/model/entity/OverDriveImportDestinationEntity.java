package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * A user's OverDrive borrow-and-import destinations, split by document type: ebooks (EPUB/PDF) route
 * to one library+path, audiobooks to another. An unset type — or a library that won't keep the
 * fulfilled format — falls back to Bookdrop.
 */
@Entity
@Table(name = "overdrive_import_destination")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverDriveImportDestinationEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ebook_library_id")
    private Long ebookLibraryId;

    @Column(name = "ebook_path_id")
    private Long ebookPathId;

    @Column(name = "audiobook_library_id")
    private Long audiobookLibraryId;

    @Column(name = "audiobook_path_id")
    private Long audiobookPathId;
}
