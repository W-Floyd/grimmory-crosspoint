package org.booklore.model.enums;

import org.booklore.model.dto.request.UserUpdateRequest;
import org.booklore.model.entity.UserPermissionsEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OverDrive card-management permission is a nested capability: it only means anything for a user
 * who can reach OverDrive at all, so it must never persist on its own.
 */
class UserPermissionNormalizeTest {

    @Test
    void normalize_clearsCardManagementWhenOverdriveAccessIsAbsent() {
        UserPermissionsEntity perms = UserPermissionsEntity.builder()
                .permissionAccessOverdrive(false)
                .permissionManageAllOverdriveShares(true)
                .permissionManageAllOverdriveCards(true)
                .build();

        UserPermission.normalize(perms);

        assertThat(perms.isPermissionManageAllOverdriveShares()).isFalse();
        assertThat(perms.isPermissionManageAllOverdriveCards()).isFalse();
    }

    @Test
    void normalize_keepsCardManagementWhenOverdriveAccessIsPresent() {
        UserPermissionsEntity perms = UserPermissionsEntity.builder()
                .permissionAccessOverdrive(true)
                .permissionManageAllOverdriveShares(true)
                .build();

        UserPermission.normalize(perms);

        assertThat(perms.isPermissionManageAllOverdriveShares()).isTrue();
    }

    @Test
    void copyFromRequestToEntity_dropsAnOrphanedCardManagementGrant() {
        UserUpdateRequest.Permissions request = new UserUpdateRequest.Permissions();
        request.setCanAccessOverdrive(false);
        request.setCanManageAllOverdriveShares(true);
        request.setCanManageAllOverdriveCards(true);
        UserPermissionsEntity perms = UserPermissionsEntity.builder().build();

        UserPermission.copyFromRequestToEntity(request, perms);

        assertThat(perms.isPermissionAccessOverdrive()).isFalse();
        assertThat(perms.isPermissionManageAllOverdriveShares()).isFalse();
        assertThat(perms.isPermissionManageAllOverdriveCards()).isFalse();
    }
}
