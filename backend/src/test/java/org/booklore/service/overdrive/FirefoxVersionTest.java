package org.booklore.service.overdrive;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirefoxVersionTest {

    private static final Map<Integer, LocalDate> HISTORY = Map.of(
            156, LocalDate.of(2026, 9, 15),
            157, LocalDate.of(2026, 9, 29),
            158, LocalDate.of(2026, 10, 13),
            159, LocalDate.of(2026, 10, 27));

    @Test
    void choosesTheNewestReleaseThatHasHadAWeekToRollOut() {
        assertThat(FirefoxVersion.choose(HISTORY, LocalDate.of(2026, 10, 19))).isEqualTo(157);
        assertThat(FirefoxVersion.choose(HISTORY, LocalDate.of(2026, 10, 20))).isEqualTo(158);
        assertThat(FirefoxVersion.choose(HISTORY, LocalDate.of(2026, 11, 30))).isEqualTo(159);
    }

    @Test
    void neverClaimsOlderThanTheBuiltInVersion() {
        Map<Integer, LocalDate> ancient = Map.of(120, LocalDate.of(2023, 11, 21));
        assertThat(FirefoxVersion.choose(ancient, LocalDate.of(2026, 10, 9))).isEqualTo(FirefoxVersion.BUILT_IN_MAJOR);
    }

    @Test
    void readsOnlyMajorReleasesFromTheHistory() {
        var json = new ObjectMapper().readTree("""
                {"1.0": "2004-11-09", "3.5": "2009-06-30", "157.0": "2026-09-29", "158.0": "2026-10-13"}
                """);
        assertThat(FirefoxVersion.parseHistory(json))
                .containsEntry(157, LocalDate.of(2026, 9, 29))
                .containsEntry(158, LocalDate.of(2026, 10, 13))
                .containsEntry(1, LocalDate.of(2004, 11, 9))
                .doesNotContainKey(3);
    }

    @Test
    void anUnrecognisableHistoryIsAFailureNotAnEmptyAnswer() {
        var json = new ObjectMapper().readTree("{\"releases\": []}");
        assertThatThrownBy(() -> FirefoxVersion.parseHistory(json)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailedReadKeepsTheLastGoodVersion() {
        Map<Integer, LocalDate>[] next = new Map[]{HISTORY};
        FirefoxVersion version = new FirefoxVersion(null, null) {
            @Override
            Map<Integer, LocalDate> fetchHistory() {
                if (next[0] == null) {
                    throw new IllegalStateException("connection reset");
                }
                return next[0];
            }
        };
        version.clock = Clock.fixed(LocalDate.of(2026, 11, 30).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

        version.refresh();
        assertThat(version.major()).isEqualTo(159);

        next[0] = null;
        version.refresh();
        assertThat(version.major()).isEqualTo(159);
        assertThat(version.userAgent())
                .isEqualTo("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:159.0) Gecko/20100101 Firefox/159.0");
    }
}
