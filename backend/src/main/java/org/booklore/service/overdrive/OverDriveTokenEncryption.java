package org.booklore.service.overdrive;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.repository.OverDriveTokenRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encrypts tokens stored before a credential key was configured. Hibernate writes an attribute only
 * when its (decrypted) value changes, so without this a plaintext row would stay plaintext until the
 * token itself was next replaced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverDriveTokenEncryption {

    private final OverDriveCredentialCipher cipher;
    private final OverDriveTokenConverter converter;
    private final OverDriveTokenRepository repository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void encryptStoredTokens() {
        if (!cipher.isEnabled()) {
            return;
        }
        int encrypted = 0;
        for (OverDriveTokenRepository.StoredToken row : repository.findStoredUnencryptedTokens()) {
            String stored = converter.convertToDatabaseColumn(row.getToken());
            if (stored != null && stored.startsWith(OverDriveTokenConverter.PREFIX)) {
                encrypted += repository.writeStoredToken(row.getId(), stored);
            }
        }
        if (encrypted > 0) {
            log.info("OverDrive: encrypted {} stored token(s) at rest", encrypted);
        }
    }
}
