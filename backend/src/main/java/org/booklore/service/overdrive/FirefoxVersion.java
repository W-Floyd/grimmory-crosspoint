package org.booklore.service.overdrive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Firefox release our user agent claims, kept current from Mozilla's own release history.
 *
 * <p>Firefox ships a major release every two weeks (since September 2026), so a hard-coded version
 * goes stale twice a month. {@link LibbyClientVersionGuard} refreshes this in its daily check.
 *
 * <p>The newest major released at least {@link #ROLLOUT_LAG} ago is chosen, not the newest outright:
 * Firefox updates roll out in stages, so on release day almost nobody runs the new version yet. A
 * failed read keeps the last good value, unlike an unsupported Libby build, which stops traffic: a
 * Firefox a release or two old describes plenty of real users. It never drops below
 * {@link #BUILT_IN_MAJOR}, so bad data cannot make us claim an ancient browser.
 */
@Slf4j
@Service
public class FirefoxVersion {

    static final String HISTORY_URL = "https://product-details.mozilla.org/1.0/firefox_history_major_releases.json";
    /** Last verified against a capture: the user's own browser, 2026-10-05. */
    static final int BUILT_IN_MAJOR = 157;
    static final Duration ROLLOUT_LAG = Duration.ofDays(7);
    /** How long reads may keep failing before it is worth a warning. */
    static final Duration STALE_AFTER = Duration.ofDays(3);
    private static final Pattern MAJOR_KEY = Pattern.compile("(\\d+)\\.0");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    Clock clock = Clock.systemUTC(); // package-private for testing

    private volatile int major = BUILT_IN_MAJOR;
    private Instant firstFailure;

    public FirefoxVersion(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** Only the built-in version, never fetched. For tests. */
    static FirefoxVersion builtIn() {
        return new FirefoxVersion(null, null) {
            @Override
            synchronized void refresh() {
            }
        };
    }

    public int major() {
        return major;
    }

    /** Firefox puts only the major version in its user agent, always as {@code <major>.0}. */
    public String userAgent() {
        int v = major;
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:" + v + ".0) Gecko/20100101 Firefox/" + v + ".0";
    }

    synchronized void refresh() {
        Map<Integer, LocalDate> history;
        try {
            history = fetchHistory();
        } catch (Exception e) {
            Instant now = clock.instant();
            if (firstFailure == null) {
                firstFailure = now;
            }
            if (Duration.between(firstFailure, now).compareTo(STALE_AFTER) >= 0) {
                log.warn("OverDrive: could not read Firefox releases from Mozilla since {} ({}); still claiming Firefox {}",
                        firstFailure, e.getMessage(), major);
            } else {
                log.debug("OverDrive: could not read Firefox releases ({}); keeping Firefox {}", e.getMessage(), major);
            }
            return;
        }
        firstFailure = null;
        int chosen = choose(history, LocalDate.ofInstant(clock.instant(), clock.getZone()));
        if (chosen != major) {
            log.info("OverDrive: user agent now claims Firefox {} (was {})", chosen, major);
            major = chosen;
        }
    }

    /** Newest major released at least ROLLOUT_LAG before {@code today}, never below the built-in one. */
    static int choose(Map<Integer, LocalDate> history, LocalDate today) {
        LocalDate cutoff = today.minusDays(ROLLOUT_LAG.toDays());
        int best = BUILT_IN_MAJOR;
        for (Map.Entry<Integer, LocalDate> e : history.entrySet()) {
            if (!e.getValue().isAfter(cutoff) && e.getKey() > best) {
                best = e.getKey();
            }
        }
        return best;
    }

    Map<Integer, LocalDate> fetchHistory() throws Exception { // package-private for testing
        HttpRequest request = HttpRequest.newBuilder(URI.create(HISTORY_URL))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return parseHistory(objectMapper.readTree(response.body()));
    }

    static Map<Integer, LocalDate> parseHistory(JsonNode root) {
        Map<Integer, LocalDate> out = new java.util.HashMap<>();
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            Matcher m = MAJOR_KEY.matcher(e.getKey());
            if (m.matches()) {
                out.put(Integer.parseInt(m.group(1)), LocalDate.parse(e.getValue().asString()));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("no <major>.0 releases in the history; its format may have changed");
        }
        return out;
    }
}
