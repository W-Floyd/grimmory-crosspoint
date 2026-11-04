package org.booklore.controller.overdrive;

import org.booklore.model.dto.settings.OverdriveProperties;
import org.booklore.exception.APIException;
import org.booklore.exception.ApiError;
import org.booklore.service.acsm.AcsmHandler;
import org.booklore.service.acsm.AcsmHandlerConfig;
import org.booklore.service.audiobook.AudiobookHandler;
import org.booklore.service.overdrive.LibbyClientVersionGuard;
import org.booklore.service.overdrive.OverDriveService;
import org.booklore.service.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OverDriveControllerTest {

    private final OverDriveService service = mock(OverDriveService.class);
    private final AcsmHandler acsmHandler = mock(AcsmHandler.class);
    private final AcsmHandlerConfig acsmConfig = mock(AcsmHandlerConfig.class);
    private final OverdriveProperties properties = mock(OverdriveProperties.class);
    private final OverDriveController controller = new OverDriveController(service, mock(TaskService.class),
            acsmHandler, acsmConfig, mock(AudiobookHandler.class), properties, mock(LibbyClientVersionGuard.class));

    @Test
    void anAcsmDownloadKeepsTheReasonFulfillmentFailed() {
        when(properties.isEnabled()).thenReturn(true);
        when(acsmConfig.isEnabled()).thenReturn(true);
        APIException frozen = ApiError.CONFLICT.createException("Frozen by its owner.");
        when(service.fulfill("card-1", "loan-1")).thenThrow(frozen);

        // Not a generic "Failed to fetch ACSM" 400: the frozen card is what the user needs to hear.
        assertThatThrownBy(() -> controller.downloadViaAcsm("card-1", "loan-1"))
                .isSameAs(frozen)
                .extracting(e -> ((APIException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(acsmHandler);
    }
}
