package org.booklore.repository;

import org.booklore.model.entity.BookLoreUserEntity;

import java.util.List;
import java.util.Optional;

public interface UserRepositoryCustom {

    Optional<BookLoreUserEntity> findByIdWithDetails(Long id);

    List<BookLoreUserEntity> findAllWithDetails();

    /**
     * All users with their permissions fetch-joined — for callers that filter on a permission and would
     * otherwise trip lazy loading outside a session. Lighter than {@link #findAllWithDetails()}, which
     * also hydrates settings, libraries and library paths.
     */
    List<BookLoreUserEntity> findAllWithPermissions();

    Optional<BookLoreUserEntity> findByIdWithSettings(Long id);

    Optional<BookLoreUserEntity> findByIdWithLibraries(Long id);

    Optional<BookLoreUserEntity> findByIdWithPermissions(Long id);
}
