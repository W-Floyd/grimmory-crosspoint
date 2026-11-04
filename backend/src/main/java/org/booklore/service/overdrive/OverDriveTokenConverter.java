package org.booklore.service.overdrive;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Stores a Libby bearer token encrypted at rest whenever a credential key is configured, alongside the
 * card number and PIN it sits next to. Without a key the token is stored as-is: it is required to
 * function, unlike the opt-in credentials.
 *
 * <p>Encrypted values carry {@link #PREFIX}, so rows written before a key was configured still read.
 * A value that can no longer be decrypted (key removed or changed) reads as blank, which every caller
 * already treats as "connect your Libby account again".
 */
@Component
@Converter
public class OverDriveTokenConverter implements AttributeConverter<String, String> {

    static final String PREFIX = "enc:v1:";

    private final OverDriveCredentialCipher cipher;

    public OverDriveTokenConverter(OverDriveCredentialCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String token) {
        if (token == null || token.isEmpty() || !cipher.isEnabled()) {
            return token;
        }
        String encrypted = cipher.encrypt(token);
        // Failing closed would leave the card unusable; the cipher has already logged why.
        return encrypted != null ? PREFIX + encrypted : token;
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        String token = cipher.decrypt(stored.substring(PREFIX.length()));
        return token != null ? token : "";
    }
}
