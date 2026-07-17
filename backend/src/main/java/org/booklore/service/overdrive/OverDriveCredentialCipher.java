package org.booklore.service.overdrive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM cipher for OverDrive library-card credentials (card number + PIN), used only to support
 * silent re-linking when a stored chip token expires. The key comes from {@code app.overdrive
 * .credential-key} (base64-encoded 16/24/32 bytes, e.g. {@code OVERDRIVE_CREDENTIAL_KEY}). When no
 * key is configured the cipher is disabled and credentials are simply not stored (token-only mode) —
 * so this never weakens the existing behaviour, it only enables the opt-in auto-relink feature.
 *
 * <p>Output format: base64( 12-byte IV || GCM ciphertext+tag ). The token itself remains stored as-is
 * (unchanged from before); only the reusable card credentials are encrypted at rest.
 */
@Slf4j
@Component
public class OverDriveCredentialCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public OverDriveCredentialCipher(@Value("${app.overdrive.credential-key:}") String base64Key) {
        SecretKey k = null;
        if (base64Key != null && !base64Key.isBlank()) {
            try {
                byte[] raw = Base64.getDecoder().decode(base64Key.trim());
                if (raw.length == 16 || raw.length == 24 || raw.length == 32) {
                    k = new SecretKeySpec(raw, "AES");
                } else {
                    log.warn("OverDrive credential-key must decode to 16/24/32 bytes; got {} — credential "
                            + "storage disabled.", raw.length);
                }
            } catch (IllegalArgumentException e) {
                log.warn("OverDrive credential-key is not valid base64 — credential storage disabled.");
            }
        }
        this.key = k;
    }

    /** Whether a valid key is configured (and thus credential storage / auto-relink is available). */
    public boolean isEnabled() {
        return key != null;
    }

    /** Encrypt a value to base64(iv||ciphertext), or null if disabled or input is null/blank. */
    public String encrypt(String plaintext) {
        if (key == null || plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            log.error("OverDrive credential encryption failed: {}", e.getMessage());
            return null;
        }
    }

    /** Decrypt a base64(iv||ciphertext) value, or null if disabled/blank/tampered. */
    public String decrypt(String stored) {
        if (key == null || stored == null || stored.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= IV_LENGTH) {
                return null;
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("OverDrive credential decryption failed: {}", e.getMessage());
            return null;
        }
    }
}
