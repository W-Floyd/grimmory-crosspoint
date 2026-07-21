package org.booklore.service.acsm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Hands an OverDrive ACSM (Adobe Content Server Message) fulfillment token to an operator-supplied
 * external tool that procures the actual book file, and returns those bytes.
 *
 * <p>An ACSM is only a fulfillment token (a pointer to the content), not the book. The external tool is responsible for
 * the entire ACSM → book step: fulfilling/downloading the content and, for DRM-protected titles,
 * whatever handling that requires. Grimmory itself holds no keys or accounts — it only writes the ACSM
 * to a temp file, invokes the configured tool, and reads back the produced document.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Write the ACSM to a temp file</li>
 *   <li>Invoke the configured CLI tool</li>
 *   <li>Read the produced book file from the {@code {output}} path</li>
 *   <li>Clean up temp files</li>
 * </ol>
 *
 * <h2>External tool contract</h2>
 * The tool receives the ACSM path and an output path (see {@code app.acsm.tool-args} placeholders
 * {@code {acsm}} and {@code {output}}), and must write the resulting book file to {@code {output}}.
 * Anything it needs to do that — network access, accounts, keys — it manages itself; Grimmory passes
 * none of that.
 *
 * <h2>Example configuration</h2>
 * <pre>{@code
 * app:
 *   acsm:
 *     enabled: true
 *     tool-path: "/opt/acsm/acsm-tool"
 *     tool-args: "--acsm {acsm} --output {output}"
 *     timeout-seconds: 120
 * }</pre>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AcsmHandler {

    private final AcsmHandlerConfig config;

    /** Whether an external ACSM handler tool is configured and can be invoked. */
    public boolean isConfigured() {
        return config.isEnabled() && !config.getToolPath().isBlank();
    }

    /**
     * Hand the given ACSM to the configured tool and return the produced book file bytes. The tool's
     * output path is named {@code book.epub}; callers that know the real format should use
     * {@link #handle(byte[], String)} instead, and callers that don't should detect it from the returned
     * bytes (e.g. the {@code %PDF} magic).
     */
    public byte[] handle(byte[] acsmBytes) {
        return handle(acsmBytes, null);
    }

    /**
     * Hand the given ACSM to the configured tool and return the produced book file bytes.
     *
     * @param acsmBytes the ACSM fulfillment token from OverDrive
     * @param extension the expected book file extension (e.g. {@code epub} or {@code pdf}); the tool's
     *                  output path is named accordingly, since some tools key the output format off it.
     *                  Blank/unknown falls back to {@code epub}.
     * @return the book file bytes, or null if no tool is configured or the tool fails
     */
    public byte[] handle(byte[] acsmBytes, String extension) {
        return handle(acsmBytes, extension, null);
    }

    /**
     * As {@link #handle(byte[], String)}, but streams each line of the tool's merged stdout/stderr to
     * {@code logSink} as it is produced (in addition to buffering it), so the UI can show live progress.
     */
    public byte[] handle(byte[] acsmBytes, String extension, java.util.function.Consumer<String> logSink) {
        if (!config.isEnabled() || config.getToolPath().isBlank()) {
            log.debug("ACSM handler not enabled or tool path not configured");
            return null;
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("overdrive-acsm-");
            Path acsmFile = tempDir.resolve("fulfillment.acsm");
            Path outputFile = tempDir.resolve("book." + normalizeExtension(extension));

            Files.write(acsmFile, acsmBytes);
            log.info("Wrote {} bytes to {}", acsmBytes.length, acsmFile);

            String args = config.getToolArgs()
                    .replace("{acsm}", acsmFile.toString())
                    .replace("{output}", outputFile.toString());

            String command = config.getToolPath() + " " + args;
            log.info("Running ACSM handler tool: {}", command);

            ProcessBuilder pb = new ProcessBuilder(command.split("\\s+"));
            pb.redirectErrorStream(true);
            pb.directory(tempDir.toFile());

            Process process = pb.start();

            // Drain the tool's merged stdout/stderr on a background thread so the pipe buffer never
            // fills (which would deadlock the process) and so the waitFor timeout can fire.
            StringBuilder toolLog = new StringBuilder();
            Thread drainer = new Thread(() -> drainToolOutput(process, toolLog, logSink), "acsm-output-drain");
            drainer.setDaemon(true);
            drainer.start();

            boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("ACSM handler tool timed out after {} seconds", config.getTimeoutSeconds());
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "ACSM handler timed out after " + config.getTimeoutSeconds() + "s.");
            }

            drainer.join(2000); // let the drainer flush the tail before we read the buffer
            int exitCode = process.exitValue();
            String output;
            synchronized (toolLog) {
                output = toolLog.toString().strip();
            }
            if (exitCode != 0) {
                log.error("ACSM handler tool exited with code {}. Output: {}", exitCode, output);
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "ACSM handler failed (exit " + exitCode + "): " + tail(output));
            }

            // The produced book file is written to the output path, not stdout. Many ACSM tools exit 0
            // even on failure, so surface their output — e.g. an unactivated ADE account reports
            // "did not find the licenseService certificate in the activation data".
            if (!Files.exists(outputFile)) {
                log.error("ACSM handler tool exited 0 but produced no output file. Output: {}", output);
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "ACSM handler produced no book file. Tool output: " + tail(output));
            }
            byte[] book = Files.readAllBytes(outputFile);
            log.info("ACSM handler tool produced {} bytes", book.length);
            return book;

        } catch (IOException e) {
            log.error("ACSM handler tool IO error: {}", e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException("ACSM handler IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("ACSM handler tool interrupted");
            throw ApiError.GENERIC_BAD_REQUEST.createException("ACSM handler was interrupted.");
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted((a, b) -> -a.compareTo(b))
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException ignored) {
                                    // best-effort cleanup
                                }
                            });
                    Files.delete(tempDir);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    /** A safe, lowercase book file extension for the tool output path; defaults to {@code epub}. */
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
                        // never let a streaming failure break the handler
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended / stream closed
        }
    }

    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return "epub";
        }
        String ext = extension.strip().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        return ext.matches("[a-z0-9]{1,8}") ? ext : "epub";
    }

    /** A single-line, length-capped tail of tool output for surfacing in error messages. */
    private static String tail(String output) {
        if (output == null || output.isBlank()) {
            return "(no output)";
        }
        String oneLine = output.replaceAll("\\s+", " ").strip();
        int max = 400;
        return oneLine.length() > max ? "…" + oneLine.substring(oneLine.length() - max) : oneLine;
    }
}
