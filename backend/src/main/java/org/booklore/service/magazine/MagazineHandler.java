package org.booklore.service.magazine;

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
 * Hands an OverDrive magazine loan + card credentials to an operator-supplied external tool that
 * authenticates, borrows, fulfils, downloads and assembles the issue, returning the produced file.
 *
 * <p>Mirrors {@link org.booklore.service.audiobook.AudiobookHandler}: identical manifest shape and
 * (typically) the same tool binary, driven by a separate {@code app.magazine} config. Two differences
 * from the audiobook manifest: {@code loan.mediaType} is {@code "magazine"} and no {@code loan.formatId}
 * is sent (the tool picks the format). Grimmory does <b>no</b> borrow of its own — the tool does
 * everything from the card+PIN. A single magazine issue is normally delivered as <b>more than one
 * file</b> — an "as-is" fixed-layout PDF plus a reflowable "article" EPUB (text+image) — so the tool
 * may write several files; each is a format of the same issue, and its book file type is taken from
 * its extension.
 *
 * <h2>External tool contract</h2>
 * The tool receives two placeholders (see {@code app.magazine.tool-args}):
 * <ul>
 *   <li>{@code {input}} — path to a JSON manifest (same shape as the audiobook handler, with
 *     {@code loan.mediaType == "magazine"} and no {@code formatId}).</li>
 *   <li>{@code {output}} — an empty directory into which the tool writes one <b>or more</b> files
 *     (PDF and/or EPUB); Grimmory imports every file that appears there as one book (the extra
 *     formats attached to it), taking each book file type from its extension.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class MagazineHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MagazineHandlerConfig config;

    /**
     * The details handed to the tool to authenticate, borrow, fulfil and download a magazine loan. The
     * tool authenticates from {@code cardNumber} + {@code pin} (resolving the library via
     * {@code libraryKey}/{@code websiteId}) — Grimmory passes no bearer token.
     */
    public record Request(String sentryBaseUrl, String cardNumber, String pin, String libraryKey,
                          String websiteId, String ilsName, String cardId, String titleId) {}

    /** One file the tool produced: its name, extension (pdf/epub) and bytes. */
    public record OutputFile(String fileName, String extension, byte[] content) {}

    /**
     * The tool's produced magazine issue as one or more files (e.g. a fixed-layout PDF plus a
     * reflowable EPUB), in a stable order — the first is treated as the primary format.
     */
    public record Result(List<OutputFile> files) {}

    /** Whether an external magazine handler tool is configured and can be invoked. */
    public boolean isConfigured() {
        return config.isEnabled() && !config.getToolPath().isBlank();
    }

    /**
     * Hand the loan/auth details to the configured tool and return the produced magazine file.
     *
     * @return the magazine bytes + extension (pdf/epub)
     * @throws org.booklore.exception.APIException if no tool is configured or the tool fails
     */
    public Result handle(Request request) {
        return handle(request, null);
    }

    /**
     * As {@link #handle(Request)}, but streams each line of the tool's merged stdout/stderr to
     * {@code logSink} as it is produced (in addition to buffering it), so the UI can show live progress.
     */
    public Result handle(Request request, java.util.function.Consumer<String> logSink) {
        if (!isConfigured()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "This title is a magazine, but no magazine handler is configured on the server.");
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("overdrive-magazine-");
            Path manifestFile = tempDir.resolve("manifest.json");
            Path outputDir = tempDir.resolve("out");
            Files.createDirectory(outputDir);

            Files.write(manifestFile, JSON.writeValueAsBytes(buildManifest(request)));

            java.util.List<String> command = org.booklore.util.ExternalToolCommand.build(
                    config.getToolPath(), config.getToolArgs(),
                    java.util.Map.of("input", manifestFile.toString(), "output", outputDir.toString()));
            // Don't log the command verbatim — keep card credentials out of logs.
            log.info("Running magazine handler tool for title {}", request.titleId());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(tempDir.toFile());

            Process process = pb.start();

            StringBuilder toolLog = new StringBuilder();
            Thread drainer = new Thread(() -> drainToolOutput(process, toolLog, logSink), "magazine-output-drain");
            drainer.setDaemon(true);
            drainer.start();

            boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Magazine handler tool timed out after {} seconds", config.getTimeoutSeconds());
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Magazine handler timed out after " + config.getTimeoutSeconds() + "s.");
            }

            drainer.join(2000); // let the drainer flush the tail before we read the buffer
            int exitCode = process.exitValue();
            String output;
            synchronized (toolLog) {
                output = toolLog.toString().strip();
            }
            if (exitCode != 0) {
                log.error("Magazine handler tool exited with code {}. Output: {}", exitCode, output);
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Magazine handler failed (exit " + exitCode + "): " + tail(output));
            }

            List<OutputFile> produced = outputFiles(outputDir, output);
            log.info("Magazine handler tool produced {} file(s) for title {}: {}", produced.size(),
                    request.titleId(), produced.stream().map(OutputFile::fileName).toList());
            return new Result(produced);

        } catch (IOException e) {
            log.error("Magazine handler tool IO error: {}", e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException("Magazine handler IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiError.GENERIC_BAD_REQUEST.createException("Magazine handler was interrupted.");
        } finally {
            cleanup(tempDir);
        }
    }

    /** Build the handoff manifest: card+PIN auth + loan identifiers (mediaType magazine, no formatId). */
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
        loan.put("mediaType", "magazine");
        putIfPresent(loan, "cardId", r.cardId());
        loan.put("titleId", r.titleId());
        // No formatId: the tool picks the magazine format.

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

    /**
     * Every file the tool wrote to the output directory (a magazine issue is usually several: an
     * as-is PDF plus an article EPUB), read into memory in a stable name order so the first is a
     * deterministic primary. Errors only when nothing was produced.
     */
    private static List<OutputFile> outputFiles(Path outputDir, String toolOutput) throws IOException {
        try (Stream<Path> files = Files.list(outputDir)) {
            List<Path> produced = files.filter(Files::isRegularFile)
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
            if (produced.isEmpty()) {
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Magazine handler produced no file. Tool output: " + tail(toolOutput));
            }
            List<OutputFile> out = new java.util.ArrayList<>(produced.size());
            for (Path p : produced) {
                String name = p.getFileName().toString();
                out.add(new OutputFile(name, extensionOf(name), Files.readAllBytes(p)));
            }
            return out;
        }
    }

    /** Lowercase file extension of a name (pdf/epub/…), defaulting to {@code pdf}. */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "pdf";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? ext : "pdf";
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
