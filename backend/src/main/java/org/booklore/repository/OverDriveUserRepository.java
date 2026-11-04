package org.booklore.repository;

import org.booklore.model.entity.BookLoreUserEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;

/** The fork's user queries, kept out of upstream's UserRepository. */
public interface OverDriveUserRepository extends Repository<BookLoreUserEntity, Long> {

    /**
     * All users with their permissions fetch-joined — for callers that filter on a permission and would
     * otherwise trip lazy loading outside a session. Lighter than UserRepository#findAllWithDetails,
     * which also hydrates settings, libraries and library paths.
     */
    @Query("SELECT u FROM BookLoreUserEntity u LEFT JOIN FETCH u.permissions")
    List<BookLoreUserEntity> findAllWithPermissions();
}
