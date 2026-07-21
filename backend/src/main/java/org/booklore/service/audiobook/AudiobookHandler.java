package org.booklore.service.audiobook;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Hands OverDrive audiobook loan + auth details to an operator-supplied external tool that
 * authenticates, fulfils, downloads and assembles the audiobook, and returns the produced file bytes.
 *
 * <p>The tool <b>authenticates itself from the card credentials</b> (card number + PIN) and manages
 * its own chip with its own app-emulating User-Agent — deliberately <i>not</i> Grimmory's web chip, so
 * the two never interfere. Grimmory therefore hands off the raw card+PIN (decrypted from its encrypted
 * store) rather than a bearer token, and does <b>none</b> of the fulfillment: the tool does everything
 * (chip mint, card link, fulfil, part downloads, assembly). Grimmory only writes the handoff manifest,
 * invokes the tool, and reads back the single audiobook file it produces.
 *
 * <h2>External tool contract</h2>
 * The tool receives two placeholders (see {@code app.audiobook.tool-args}):
 * <ul>
 *   <li>{@code {input}} — path to a JSON manifest:
 *     <pre>{@code
 * {
 *   "sentryBaseUrl": "https://sentry.libbyapp.com",
 *   "auth": { "card": {"library": "...", "websiteId": "...", "ilsName": "...", "number": "...", "pin": "..."} },
 *   "loan": { "mediaType": "audiobook", "cardId": "...", "titleId": "...", "formatId": "audiobook-mp3" }
 * }}</pre>
 *     The tool logs in with the card+PIN (resolving the library via {@code library}/{@code websiteId})
 *     and fulfils the loan named by {@code loan.titleId}.</li>
 *   <li>{@code {output}} — an empty directory into which the tool must write exactly one audiobook
 *     file; Grimmory imports whatever single file appears there, taking the book file type from its
 *     extension (m4b/m4a/mp3/opus).</li>
 * </ul>
 *
 * <h2>Example configuration</h2>
 * <pre>{@code
 * app:
 *   audiobook:
 *     enabled: true
 *     tool-path: "/opt/audiobook/go-od-audiobook"
 *     # go-od is a subcommand CLI: lead with "download". Pin a stable --account-dir so its chip cache
 *     # and Adobe activation persist across runs (see the deploy compose notes).
 *     tool-args: "download --manifest {input} --out-dir {output} --account-dir /opt/audiobook/account"
 *     timeout-seconds: 1800
 * }</pre>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AudiobookHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AudiobookHandlerConfig config;

    /**
     * The details handed to the tool to authenticate, fulfil and download an audiobook loan. The tool
     * authenticates from {@code cardNumber} + {@code pin} (resolving the library via {@code libraryKey}
     * or {@code websiteId}) — Grimmory passes no bearer token, so the tool's own chip stays separate.
     */
    public record Request(String sentryBaseUrl, String cardNumber, String pin, String libraryKey,
                          String websiteId, String ilsName, String cardId, String titleId, String formatId) {}

    /** The tool's produced audiobook: the file bytes and its extension (m4b/mp3/…). */
    public record Result(byte[] content, String extension) {}

    /** Whether an external audiobook handler tool is configured and can be invoked. */
    public boolean isConfigured() {
        return config.isEnabled() && !config.getToolPath().isBlank();
    }

    /**
     * Hand the loan/auth details to the configured tool and return the produced audiobook file.
     *
     * @return the audiobook bytes + extension
     * @throws org.booklore.exception.APIException if no tool is configured or the tool fails
     */
    public Result handle(Request request) {
        return handle(request, null);
    }

    /**
     * As {@link #handle(Request)}, but streams each line of the tool's merged stdout/stderr to
     * {@code logSink} as it is produced (in addition to buffering it for the failure message), so the UI
     * can show live progress.
     */
    public Result handle(Request request, java.util.function.Consumer<String> logSink) {
        if (!isConfigured()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "This title is an audiobook, but no audiobook handler is configured on the server.");
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("overdrive-audiobook-");
            Path manifestFile = tempDir.resolve("manifest.json");
            Path outputDir = tempDir.resolve("out");
            Files.createDirectory(outputDir);

            Files.write(manifestFile, JSON.writeValueAsBytes(buildManifest(request)));

            String args = config.getToolArgs()
                    .replace("{input}", manifestFile.toString())
                    .replace("{output}", outputDir.toString());
            // Don't log the command verbatim — the manifest path is safe, but keep card credentials out of logs.
            log.info("Running audiobook handler tool for title {} (format {})", request.titleId(), request.formatId());

            ProcessBuilder pb = new ProcessBuilder((config.getToolPath() + " " + args).split("\\s+"));
            pb.redirectErrorStream(true);
            pb.directory(tempDir.toFile());

            Process process = pb.start();

            // Drain the tool's merged stdout/stderr so the pipe buffer never fills (which would deadlock
            // the process) and so the waitFor timeout can fire.
            StringBuilder toolLog = new StringBuilder();
            Thread drainer = new Thread(() -> drainToolOutput(process, toolLog, logSink), "audiobook-output-drain");
            drainer.setDaemon(true);
            drainer.start();

            boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Audiobook handler tool timed out after {} seconds", config.getTimeoutSeconds());
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Audiobook handler timed out after " + config.getTimeoutSeconds() + "s.");
            }

            drainer.join(2000); // let the drainer flush the tail before we read the buffer
            int exitCode = process.exitValue();
            String output;
            synchronized (toolLog) {
                output = toolLog.toString().strip();
            }
            if (exitCode != 0) {
                log.error("Audiobook handler tool exited with code {}. Output: {}", exitCode, output);
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Audiobook handler failed (exit " + exitCode + "): " + tail(output));
            }

            Path produced = singleOutputFile(outputDir, output);
            byte[] bytes = Files.readAllBytes(produced);
            String extension = extensionOf(produced.getFileName().toString());
            log.info("Audiobook handler tool produced {} ({} bytes)", produced.getFileName(), bytes.length);
            return new Result(bytes, extension);

        } catch (IOException e) {
            log.error("Audiobook handler tool IO error: {}", e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException("Audiobook handler IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiError.GENERIC_BAD_REQUEST.createException("Audiobook handler was interrupted.");
        } finally {
            cleanup(tempDir);
        }
    }

    /** Build the nested handoff manifest: card+PIN auth + loan identifiers. */
    private static Map<String, Object> buildManifest(Request r) {
        // library/websiteId/ilsName live INSIDE auth.card (the tool's CardCredentials shape), not on auth.
        Map<String, Object> card = new LinkedHashMap<>();
        putIfPresent(card, "library", r.libraryKey());
        putIfPresent(card, "websiteId", r.websiteId());
        putIfPresent(card, "ilsName", r.ilsName());
        card.put("number", r.cardNumber());
        card.put("pin", r.pin());

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("card", card);

        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("mediaType", "audiobook");
        putIfPresent(loan, "cardId", r.cardId());
        loan.put("titleId", r.titleId());
        putIfPresent(loan, "formatId", r.formatId());

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("sentryBaseUrl", r.sentryBaseUrl());
        manifest.put("auth", auth);
        manifest.put("loan", loan);
        return manifest;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /** The single file the tool wrote to the output directory, or a clear error otherwise. */
    private static Path singleOutputFile(Path outputDir, String toolOutput) throws IOException {
        try (Stream<Path> files = Files.list(outputDir)) {
            List<Path> produced = files.filter(Files::isRegularFile).toList();
            if (produced.isEmpty()) {
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Audiobook handler produced no file. Tool output: " + tail(toolOutput));
            }
            if (produced.size() > 1) {
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Audiobook handler produced multiple files; expected a single assembled audiobook.");
            }
            return produced.getFirst();
        }
    }

    /** Lowercase file extension of a name (m4b/mp3/…), defaulting to {@code m4b}. */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "m4b";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? ext : "m4b";
    }

    /**
     * Read the process's merged stdout/stderr line by line, appending each to {@code buffer} (for the
     * failure message) and forwarding it to {@code logSink} (for live UI streaming) when present.
     */
    private static void drainToolOutput(Process process, StringBuilder buffer,
                                        java.util.function.Consumer<String> logSink) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (buffer) {
                    buffer.append(line).append('\n');
                }
                if (logSink != null) {
                    try {
                        logSink.accept(line);
                    } catch (RuntimeException ignored) {
                        // never let a streaming failure break the download
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended / stream closed
        }
    }

    private static void cleanup(Path tempDir) {
        if (tempDir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted((a, b) -> -a.compareTo(b)).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /** Last ~500 chars of tool output, for surfacing failures without flooding the message. */
    private static String tail(String s) {
        if (s == null || s.isBlank()) {
            return "(no output)";
        }
        return s.length() <= 500 ? s : "…" + s.substring(s.length() - 500);
    }
}
