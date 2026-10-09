package org.booklore.service.overdrive;

import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stops all Libby traffic while the live Libby build is one this deployment cannot speak as.
 *
 * <p>OverDrive serves one build at a time and drops the old one the moment a new one ships, so a
 * deployment announcing a fixed version starts lying the day a release lands. We did exactly that for
 * two months once. Failing closed turns a silent drift into a visible stop until a profile for the new
 * build is added to {@link LibbyClientProfiles}.
 *
 * <p>The live version is read off the libbyapp.com index page, which loads its assets from
 * {@code /dewey-<version>/}. It is checked once a day, at the first Libby request on or after the start
 * of waking hours — the shape of somebody opening the app in the morning — and once after a restart,
 * whatever the hour, since nothing is known until then. A failed read blocks too: an unconfirmed
 * version is not a supported one. It is retried at most every {@link #RETRY_AFTER_FAILURE}.
 */
@Slf4j
@Service
public class LibbyClientVersionGuard {

    static final String LIBBY_INDEX_URL = "https://libbyapp.com/";
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(30);
    private static final Pattern VERSION_PATTERN = Pattern.compile("dewey-(\\d+\\.\\d+\\.\\d+)");

    private final HttpClient httpClient;

    @Value("${app.overdrive.waking-hours.zone:#{T(java.time.ZoneId).systemDefault().getId()}}")
    String wakingHoursZone = ZoneId.systemDefault().getId(); // package-private for testing

    @Value("${app.overdrive.waking-hours.from:8}")
    int wakingHourFrom = 8; // package-private for testing

    Clock clock = Clock.systemUTC(); // package-private for testing

    private LibbyClientProfile profile;
    private String liveVersion;
    private String blockedReason = "The live Libby version has not been checked yet.";
    private LocalDate checkedOn;
    private Instant checkedAt;
    private Instant failedAt;

    public LibbyClientVersionGuard(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** A guard that never fetches and always speaks as {@code profile}. For tests. */
    static LibbyClientVersionGuard pinned(LibbyClientProfile profile) {
        LibbyClientVersionGuard guard = new LibbyClientVersionGuard(null) {
            @Override
            synchronized void refreshIfDue() {
            }
        };
        guard.accept(profile.version());
        return guard;
    }

    /** What we are speaking as right now, or a CONFLICT naming why nothing may be sent. */
    public synchronized LibbyClientProfile requireProfile() {
        refreshIfDue();
        if (blockedReason != null) {
            throw ApiError.CONFLICT.createException(blockedReason);
        }
        return profile;
    }

    /** Null when Libby traffic may go out; otherwise why not. Checks the live version if one is due. */
    public synchronized String blockedReason() {
        refreshIfDue();
        return blockedReason;
    }

    /** The last verdict, without fetching. For display. */
    public synchronized Status status() {
        return new Status(liveVersion, profile != null ? profile.version() : null,
                LibbyClientProfiles.supportedVersions(), checkedAt, blockedReason);
    }

    public record Status(String liveVersion, String speakingAs, List<String> supportedVersions,
                         Instant checkedAt, String blockedReason) {
    }

    synchronized void refreshIfDue() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone()));
        boolean due = checkedOn == null
                || (checkedOn.isBefore(now.toLocalDate()) && now.getHour() >= wakingHourFrom);
        if (!due) {
            return;
        }
        if (failedAt != null && Duration.between(failedAt, clock.instant()).compareTo(RETRY_AFTER_FAILURE) < 0) {
            return;
        }
        String live;
        try {
            live = fetchLiveVersion();
        } catch (Exception e) {
            reject(null, "Could not read the live Libby version from " + LIBBY_INDEX_URL + " (" + e.getMessage()
                    + "), so nothing is sent to OverDrive until it can be confirmed. Retrying within "
                    + RETRY_AFTER_FAILURE.toMinutes() + " minutes.");
            failedAt = clock.instant();
            return;
        }
        failedAt = null;
        checkedOn = now.toLocalDate();
        checkedAt = clock.instant();
        if (LibbyClientProfiles.forVersion(live).isPresent()) {
            accept(live);
        } else {
            reject(live, "Libby " + live + " is live, and this build of Grimmory only speaks "
                    + String.join(", ", LibbyClientProfiles.supportedVersions()) + ". Every OverDrive action "
                    + "is stopped until support for " + live + " is added: announcing a client version "
                    + "OverDrive does not serve marks every request we make.");
        }
    }

    private void accept(String live) {
        if (checkedAt != null && !live.equals(liveVersion)) {
            log.info("OverDrive: Libby {} is live and supported", live);
        }
        liveVersion = live;
        profile = LibbyClientProfiles.forVersion(live).orElseThrow();
        blockedReason = null;
    }

    private void reject(String live, String reason) {
        if (!reason.equals(blockedReason)) {
            log.warn("OverDrive: {}", reason);
        }
        if (live != null) {
            liveVersion = live;
        }
        profile = null;
        blockedReason = reason;
    }

    /** Load the index page the way a browser opening the app does, and read the build out of it. */
    String fetchLiveVersion() throws Exception { // package-private for testing
        HttpRequest request = HttpRequest.newBuilder(URI.create(LIBBY_INDEX_URL))
                .header("User-Agent", OverDriveService.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", OverDriveService.ACCEPT_LANGUAGE)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return parseVersion(response.body());
    }

    static String parseVersion(String html) { // package-private for testing
        Matcher m = VERSION_PATTERN.matcher(html != null ? html : "");
        if (!m.find()) {
            throw new IllegalStateException("no dewey-<version> asset path on the page; its layout may have changed");
        }
        return m.group(1);
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(wakingHoursZone);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }
}
