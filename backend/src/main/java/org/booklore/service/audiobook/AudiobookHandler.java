package org.booklore.service.audiobook;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * Hands OverDrive audiobook loan + auth details to an operator-supplied external tool that fulfils,
 * downloads and assembles the audiobook, and returns the produced file bytes.
 *
 * <p>Unlike an ACSM (a self-contained fulfillment token), an audiobook download needs the loan's
 * <b>auth</b> to pull the parts — so the handoff carries the card's chip token alongside the loan/card
 * /format ids. Grimmory does <b>not</b> perform the fulfillment itself: the tool does everything
 * (fulfill call, manifest fetch, part downloads, muxing). Grimmory only writes the handoff manifest,
 * invokes the tool, and reads back the single audiobook file it produces.
 *
 * <h2>External tool contract</h2>
 * The tool receives two placeholders (see {@code app.audiobook.tool-args}):
 * <ul>
 *   <li>{@code {input}} — path to a JSON manifest:
 *     <pre>{@code {"token","cardId","loanId","formatId","titleId","sentryBaseUrl"}}</pre>
 *     where {@code token} is the card's chip/identity bearer token (the smallest auth surface).</li>
 *   <li>{@code {output}} — an empty directory into which the tool must write exactly one audiobook
 *     file (e.g. {@code book.m4b}); Grimmory imports whatever single file appears there, taking the
 *     book file type from its extension (m4b/m4a/mp3/opus).</li>
 * </ul>
 *
 * <h2>Example configuration</h2>
 * <pre>{@code
 * app:
 *   audiobook:
 *     enabled: true
 *     tool-path: "/opt/od/od-audiobook"
 *     tool-args: "--manifest {input} --out-dir {output}"
 *     timeout-seconds: 1800
 * }</pre>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AudiobookHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AudiobookHandlerConfig config;

    /** The details handed to the tool to fulfil and download an audiobook loan. */
    public record Request(String token, String cardId, String loanId, String formatId, String titleId,
                          String sentryBaseUrl) {}

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

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("token", request.token());
            manifest.put("cardId", request.cardId());
            manifest.put("loanId", request.loanId());
            manifest.put("formatId", request.formatId());
            manifest.put("titleId", request.titleId());
            manifest.put("sentryBaseUrl", request.sentryBaseUrl());
            Files.write(manifestFile, JSON.writeValueAsBytes(manifest));

            String args = config.getToolArgs()
                    .replace("{input}", manifestFile.toString())
                    .replace("{output}", outputDir.toString());
            // Don't log the command verbatim — the manifest path is safe, but keep the token out of logs.
            log.info("Running audiobook handler tool for loan {} (format {})", request.loanId(), request.formatId());

            ProcessBuilder pb = new ProcessBuilder((config.getToolPath() + " " + args).split("\\s+"));
            pb.redirectErrorStream(true);
            pb.directory(tempDir.toFile());

            Process process = pb.start();

            // Drain the tool's merged stdout/stderr so the pipe buffer never fills (which would deadlock
            // the process) and so the waitFor timeout can fire.
            ByteArrayOutputStream toolLog = new ByteArrayOutputStream();
            Thread drainer = new Thread(() -> {
                try (InputStream is = process.getInputStream()) {
                    is.transferTo(toolLog);
                } catch (IOException ignored) {
                    // process ended / stream closed
                }
            }, "audiobook-output-drain");
            drainer.setDaemon(true);
            drainer.start();

            boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Audiobook handler tool timed out after {} seconds", config.getTimeoutSeconds());
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Audiobook handler timed out after " + config.getTimeoutSeconds() + "s.");
            }

            int exitCode = process.exitValue();
            String output = toolLog.toString(StandardCharsets.UTF_8).strip();
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
