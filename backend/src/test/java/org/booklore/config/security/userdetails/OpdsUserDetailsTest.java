package org.booklore.config.security.userdetails;

import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.OpdsUserV2;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code isEnabled()} is where the OPDS-access permission is enforced: Spring's post-authentication
 * checks reject a disabled principal, so a user without the permission cannot authenticate on the OPDS
 * filter chain at all. That makes this the single gate for every OPDS request — feeds, book listings
 * and content downloads alike — which is why none of the OPDS services re-check the permission.
 */
class OpdsUserDetailsTest {

    private static OpdsUserDetails detailsFor(BookLoreUser.UserPermissions permissions) {
        BookLoreUser user = BookLoreUser.builder().id(1L).username("reader").permissions(permissions).build();
        OpdsUserV2 v2 = OpdsUserV2.builder().userId(1L).username("reader").build();
        return new OpdsUserDetails(user, v2);
    }

    private static BookLoreUser.UserPermissions permissions(boolean admin, boolean canAccessOpds) {
        BookLoreUser.UserPermissions perms = new BookLoreUser.UserPermissions();
        perms.setAdmin(admin);
        perms.setCanAccessOpds(canAccessOpds);
        return perms;
    }

    @Test
    void enabledWhenTheUserHoldsTheOpdsPermission() {
        assertThat(detailsFor(permissions(false, true)).isEnabled()).isTrue();
    }

    @Test
    void enabledForAnAdminWithoutTheExplicitPermission() {
        assertThat(detailsFor(permissions(true, false)).isEnabled()).isTrue();
    }

    @Test
    void disabledWhenTheUserHoldsNeither() {
        // The revocation case: an OPDS credential outlives the permission, so the credential must stop
        // authenticating once the permission is taken away.
        assertThat(detailsFor(permissions(false, false)).isEnabled()).isFalse();
    }

    @Test
    void disabledWhenPermissionsAreMissingEntirely() {
        assertThat(detailsFor(null).isEnabled()).isFalse();
    }
}
