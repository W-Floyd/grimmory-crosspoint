package org.booklore.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.time.Instant;

/**
 * A readable byte range addressable by absolute position, decoupled from where the bytes live.
 * Lets {@link FileStreamingService} serve HTTP ranges out of a standalone file or out of an
 * entry inside an archive without duplicating the RFC 7233 protocol handling.
 *
 * <p>Metadata ({@link #size()}, {@link #lastModified()}) must be available without opening the
 * underlying resource: it feeds the ETag and conditional-request checks that can short-circuit
 * a request before any bytes are read.
 */
public interface ByteRangeSource {

    /**
     * Total length of the readable content in bytes. For compressed archive entries this is the
     * uncompressed length, since that is the range space the client addresses.
     */
    long size();

    /**
     * Timestamp used for {@code Last-Modified} and ETag derivation.
     */
    Instant lastModified();

    /**
     * Writes {@code count} bytes starting at {@code position} to {@code out}.
     * Implementations open and close whatever resources they need per call.
     */
    void transferTo(long position, long count, OutputStream out) throws IOException;

    /**
     * Zero-copy transfer from a file channel to an output stream via NIO.
     * Delegates to sendfile(2) on Linux / equivalent on macOS when the servlet container's
     * OutputStream maps to a socket channel. Shared by the file-backed and archive-backed
     * sources, since both ultimately read from a {@link FileChannel}.
     *
     * <p>Closes the output stream upon completion, as the channel wrapper propagates close.
     */
    static void transferFromChannel(FileChannel source, long position, long count, OutputStream out) throws IOException {
        try (WritableByteChannel destination = Channels.newChannel(out)) {
            long remaining = count;
            long currentPos = position;
            int zeroTransferCount = 0;

            while (remaining > 0) {
                long transferred = source.transferTo(currentPos, remaining, destination);
                if (transferred <= 0) {
                    ++zeroTransferCount;
                    if (zeroTransferCount > 100) {
                        throw new IOException("File transfer stalled with " + remaining + " bytes remaining");
                    }
                    Thread.onSpinWait();
                    continue;
                }
                zeroTransferCount = 0;
                currentPos += transferred;
                remaining -= transferred;
            }
        }
    }
}
