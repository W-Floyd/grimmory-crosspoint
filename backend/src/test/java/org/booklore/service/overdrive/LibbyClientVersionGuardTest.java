package org.booklore.service.overdrive;

import org.booklore.exception.APIException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibbyClientVersionGuardTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    /** A guard whose page reads come from a queue: a version string, or null for a failed read. */
    private static final class ScriptedGuard extends LibbyClientVersionGuard {
        final Deque<String> pages = new ArrayDeque<>();
        int fetches;

        ScriptedGuard() {
            super(null);
            wakingHoursZone = ZONE.getId();
            wakingHourFrom = 8;
        }

        void at(int day, int hour, int minute) {
            clock = Clock.fixed(ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZONE).toInstant(), ZONE);
        }

        @Override
        String fetchLiveVersion() {
            fetches++;
            String next = pages.poll();
            if (next == null) {
                throw new IllegalStateException("connection reset");
            }
            return next;
        }
    }

    @Test
    void readsTheVersionOffTheAssetPath() {
        String html = "<script src=\"/dewey-22.1.2/src/main.js\"></script><link href=\"dewey-22.1.2/css/main.css\">";
        assertThat(LibbyClientVersionGuard.parseVersion(html)).isEqualTo("22.1.2");
    }

    @Test
    void aSupportedLiveVersionIsSpokenAs() {
        ScriptedGuard guard = new ScriptedGuard();
        guard.at(9, 9, 0);
        guard.pages.add("22.1.1");

        assertThat(guard.requireProfile().version()).isEqualTo("22.1.1");
        assertThat(guard.requireProfile().chipQuery("abc")).isEqualTo("c=d:22.1.1&s=0&v=abc");
    }

    @Test
    void anUnknownLiveVersionStopsEverything() {
        ScriptedGuard guard = new ScriptedGuard();
        guard.at(9, 9, 0);
        guard.pages.add("22.2.0");

        assertThatThrownBy(guard::requireProfile)
                .isInstanceOf(APIException.class)
                .hasMessageContaining("Libby 22.2.0 is live");
        assertThat(guard.status().liveVersion()).isEqualTo("22.2.0");
        assertThat(guard.status().speakingAs()).isNull();
    }

    @Test
    void anUnreadablePageBlocksAndIsNotHammered() {
        ScriptedGuard guard = new ScriptedGuard();
        guard.at(9, 9, 0);

        assertThat(guard.blockedReason()).contains("Could not read the live Libby version");
        guard.at(9, 9, 20);
        assertThat(guard.blockedReason()).isNotNull();
        assertThat(guard.fetches).isEqualTo(1);

        guard.pages.add("22.1.2");
        guard.at(9, 9, 31);
        assertThat(guard.blockedReason()).isNull();
        assertThat(guard.fetches).isEqualTo(2);
    }

    @Test
    void checksOncePerDayAndOnlyFromTheMorning() {
        ScriptedGuard guard = new ScriptedGuard();
        guard.at(9, 10, 0);
        guard.pages.add("22.1.2");
        guard.requireProfile();

        guard.at(9, 22, 0);
        guard.requireProfile();
        guard.at(10, 3, 0);
        guard.requireProfile();
        assertThat(guard.fetches).as("same day, then the small hours: yesterday's verdict stands").isEqualTo(1);

        guard.pages.add("22.2.0");
        guard.at(10, 8, 5);
        assertThat(guard.blockedReason()).contains("22.2.0");
        assertThat(guard.fetches).isEqualTo(2);
    }

    @Test
    void aRestartChecksWhateverTheHour() {
        ScriptedGuard guard = new ScriptedGuard();
        guard.at(9, 3, 0);
        guard.pages.add("22.1.2");

        assertThat(guard.blockedReason()).isNull();
        assertThat(guard.fetches).isEqualTo(1);
    }

    @Test
    void aPinnedGuardNeverFetches() {
        LibbyClientVersionGuard guard = LibbyClientVersionGuard.pinned(
                LibbyClientProfiles.forVersion("22.0.2").orElseThrow());

        assertThat(guard.requireProfile().version()).isEqualTo("22.0.2");
        assertThat(guard.status().checkedAt()).isNull();
    }
}
