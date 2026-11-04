package org.booklore.service.overdrive;

import org.booklore.repository.OverDriveTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OverDriveTokenConverterTest {

    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJjaGlwIjoiMSJ9.sig";

    private static OverDriveCredentialCipher cipher(int seed) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) seed);
        return new OverDriveCredentialCipher(Base64.getEncoder().encodeToString(key));
    }

    private static final OverDriveCredentialCipher NO_KEY = new OverDriveCredentialCipher("");

    static Stream<Arguments> reads() {
        String encryptedUnderKey1 = new OverDriveTokenConverter(cipher(1)).convertToDatabaseColumn(JWT);
        return Stream.of(
                Arguments.of("encrypted, same key", cipher(1), encryptedUnderKey1, JWT),
                Arguments.of("written before a key existed", cipher(1), JWT, JWT),
                Arguments.of("no key configured", NO_KEY, JWT, JWT),
                // Blank is what every caller already treats as "connect your Libby account again".
                Arguments.of("key changed", cipher(2), encryptedUnderKey1, ""),
                Arguments.of("key removed", NO_KEY, encryptedUnderKey1, ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reads")
    void readsBackWhatCanStillBeRead(String label, OverDriveCredentialCipher cipher, String stored, String expected) {
        assertThat(new OverDriveTokenConverter(cipher).convertToEntityAttribute(stored)).isEqualTo(expected);
    }

    @Test
    void encryptsAtRestOnlyWithAKey() {
        String withKey = new OverDriveTokenConverter(cipher(1)).convertToDatabaseColumn(JWT);
        assertThat(withKey).startsWith(OverDriveTokenConverter.PREFIX).doesNotContain(JWT);
        assertThat(new OverDriveTokenConverter(NO_KEY).convertToDatabaseColumn(JWT)).isEqualTo(JWT);
    }

    @Test
    void startupEncryptsRowsStoredInPlainText() {
        OverDriveTokenRepository repository = mock(OverDriveTokenRepository.class);
        OverDriveTokenRepository.StoredToken row = mock(OverDriveTokenRepository.StoredToken.class);
        when(row.getId()).thenReturn(7L);
        when(row.getToken()).thenReturn(JWT);
        when(repository.findStoredUnencryptedTokens()).thenReturn(List.of(row));
        OverDriveCredentialCipher cipher = cipher(1);

        new OverDriveTokenEncryption(cipher, new OverDriveTokenConverter(cipher), repository).encryptStoredTokens();

        verify(repository).writeStoredToken(eq(7L), startsWith(OverDriveTokenConverter.PREFIX));
    }

    @Test
    void startupLeavesTokensAloneWithoutAKey() {
        OverDriveTokenRepository repository = mock(OverDriveTokenRepository.class);

        new OverDriveTokenEncryption(NO_KEY, new OverDriveTokenConverter(NO_KEY), repository).encryptStoredTokens();

        verifyNoInteractions(repository);
        verify(repository, never()).writeStoredToken(anyLong(), anyString());
    }
}
