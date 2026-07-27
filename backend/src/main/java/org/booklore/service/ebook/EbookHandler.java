package org.booklore.service.ebook;

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
 * Hands an OverDrive ebook loan + card credentials to an operator-supplied external tool that
 * authenticates, fulfils, downloads and assembles the book, returning the produced file.
 *
 * <p>This exists for the {@code ebook-overdrive} format — Libby's "read in browser" format, which is
 * <b>not</b> a downloadable file: the book is served as web-reader assets (an <i>openbook</i> manifest
 * plus spine content) rather than an EPUB, and it yields no ACSM, so neither the direct open-format
 * fulfillment nor the ACSM handler can procure it. An increasing number of titles are licensed in this
 * format only. Reconstructing an EPUB from the reader assets is the tool's job; Grimmory just hands
 * over the loan and imports whatever file comes back.
 *
 * <p>Mirrors {@link org.booklore.service.audiobook.AudiobookHandler} and
 * {@link org.booklore.service.magazine.MagazineHandler}: identical manifest shape and (typically) the
 * same tool binary, driven by a separate {@code app.ebook} config. {@code loan.mediaType} is
 * {@code "ebook"} and {@code loan.formatId} carries the offered format id.
 *
 * <h2>External tool contract</h2>
 * The tool receives two placeholders (see {@code app.ebook.tool-args}):
 * <ul>
 *   <li>{@code {input}} — path to a JSON manifest (same shape as the audiobook handler, with
 *     {@code loan.mediaType == "ebook"}).</li>
 *   <li>{@code {output}} — an empty directory into which the tool must write exactly one book file;
 *     Grimmory imports it, taking the book file type from its extension (epub/pdf).</li>
 * </ul>
 * As with the other handlers the produced file is never read into heap — the caller owns
 * {@code workDir} and the import moves the file out of it.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class EbookHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EbookHandlerConfig config;

    /**
     * The details handed to the tool to authenticate, fulfil and download an ebook loan. The tool
     * authenticates from {@code cardNumber} + {@code pin} (resolving the library via
     * {@code libraryKey}/{@code websiteId}) — Grimmory passes no bearer token.
     */
    public record Request(String sentryBaseUrl, String cardNumber, String pin, String libraryKey,
                          String websiteId, String ilsName, String cardId, String titleId, String formatId) {}

    /** The tool's produced book: the file it wrote (inside the caller's work dir) and its extension. */
    public record Result(Path file, String extension) {}

    /** Whether an external ebook handler tool is configured and can be invoked. */
    public boolean isConfigured() {
        return config.isEnabled() && !config.getToolPath().isBlank();
    }

    /**
     * Hand the loan/auth details to the configured tool and return the produced book file.
     *
     * @param workDir a caller-owned scratch directory the manifest and output are staged under; the
     *                returned file lives inside it, so the caller must not delete it until the file
     *                has been imported
     * @throws org.booklore.exception.APIException if no tool is configured or the tool fails
     */
    public Result handle(Request request, Path workDir) {
        return handle(request, workDir, null);
    }

    /**
     * As {@link #handle(Request, Path)}, but streams each line of the tool's merged stdout/stderr to
     * {@code logSink} as it is produced (in addition to buffering it), so the UI can show live progress.
     */
    public Result handle(Request request, Path workDir, java.util.function.Consumer<String> logSink) {
        if (!isConfigured()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "This title is only offered in Libby's read-in-browser format, but no ebook handler "
                            + "is configured on the server.");
        }

        // The manifest carries card number + PIN in cleartext — shred it as soon as the tool exits.
        Path manifestFile = null;
        try {
            Path tempDir = Files.createTempDirectory(workDir, "run-");
            manifestFile = tempDir.resolve("manifest.json");
            Path outputDir = tempDir.resolve("out");
            Files.createDirectory(outputDir);

            Files.write(manifestFile, JSON.writeValueAsBytes(buildManifest(request)));

            java.util.List<String> command = org.booklore.util.ExternalToolCommand.build(
                    config.getToolPath(), config.getToolArgs(),
                    java.util.Map.of("input", manifestFile.toString(), "output", outputDir.toString()));
            // Don't log the command verbatim — keep card credentials out of logs.
            log.info("Running ebook handler tool for title {} (format {})", request.titleId(), request.formatId());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(tempDir.toFile());

            Process process = pb.start();

            StringBuilder toolLog = new StringBuilder();
            Thread drainer = new Thread(() -> drainToolOutput(process, toolLog, logSink), "ebook-output-drain");
            drainer.setDaemon(true);
            drainer.start();

            boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Ebook handler tool timed out after {} seconds", config.getTimeoutSeconds());
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Ebook handler timed out after " + config.getTimeoutSeconds() + "s.");
            }

            drainer.join(2000); // let the drainer flush the tail before we read the buffer
            int exitCode = process.exitValue();
            String output;
            synchronized (toolLog) {
                output = toolLog.toString().strip();
            }
            if (exitCode != 0) {
                log.error("Ebook handler tool exited with code {}. Output: {}", exitCode, output);
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Ebook handler failed (exit " + exitCode + "): " + tail(output));
            }

            Path produced = singleOutputFile(outputDir, output);
            String extension = extensionOf(produced.getFileName().toString());
            log.info("Ebook handler tool produced {} ({} bytes)", produced.getFileName(), Files.size(produced));
            return new Result(produced, extension);

        } catch (IOException e) {
            log.error("Ebook handler tool IO error: {}", e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException("Ebook handler IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiError.GENERIC_BAD_REQUEST.createException("Ebook handler was interrupted.");
        } finally {
            shredManifest(manifestFile);
        }
    }

    /** Build the handoff manifest: card+PIN auth + loan identifiers (mediaType ebook). */
    private static Map<String, Object> buildManifest(Request r) {
        Map<String, Object> card = new LinkedHashMap<>();
        putIfPresent(card, "library", r.libraryKey());
        putIfPresent(card, "websiteId", r.websiteId());
        putIfPresent(card, "ilsName", r.ilsName());
        card.put("number", r.cardNumber());
        card.put("pin", r.pin());

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("card", card);

        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("mediaType", "ebook");
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
                        "Ebook handler produced no file. Tool output: " + tail(toolOutput));
            }
            if (produced.size() > 1) {
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Ebook handler produced multiple files; expected a single book file.");
            }
            return produced.getFirst();
        }
    }

    /** Lowercase file extension of a name (epub/pdf/…), defaulting to {@code epub}. */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "epub";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? ext : "epub";
    }

    /** Read the process's merged stdout/stderr line by line, buffering each and streaming to logSink. */
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

    /**
     * Delete the credential-bearing manifest the moment the tool is done with it. The rest of the work
     * directory (including the produced file) belongs to the caller.
     */
    private static void shredManifest(Path manifestFile) {
        if (manifestFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(manifestFile);
        } catch (IOException e) {
            log.warn("Could not delete ebook handler manifest {}: {}", manifestFile, e.getMessage());
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
