package org.booklore.service.bookdrop;

import org.booklore.config.AppProperties;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.service.acsm.AcsmHandler;
import org.booklore.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Slf4j
@Service
public class BookdropMonitoringService implements SmartLifecycle {

    private static final int LIFECYCLE_PHASE = 20;

    private final AppProperties appProperties;
    private final BookdropEventHandlerService eventHandler;
    private final BookdropFileRepository bookdropFileRepository;
    private final AcsmHandler acsmHandler;

    private Path bookdrop;
    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running;
    private WatchKey watchKey;
    private volatile boolean paused;
    private volatile boolean disabled;
    private final Lock monitorLock = new ReentrantLock();

    public BookdropMonitoringService(
            AppProperties appProperties,
            BookdropEventHandlerService eventHandler,
            BookdropFileRepository bookdropFileRepository,
            AcsmHandler acsmHandler
    ) {
        this.appProperties = appProperties;
        this.eventHandler = eventHandler;
        this.bookdropFileRepository = bookdropFileRepository;
        this.acsmHandler = acsmHandler;
    }

    @Override
    public void start() {
        bookdrop = Path.of(appProperties.getBookdropFolder());
        if (Files.notExists(bookdrop)) {
            try {
                Files.createDirectories(bookdrop);
                log.info("Created missing bookdrop folder: {}", bookdrop);
            } catch (IOException e) {
                log.warn("Bookdrop folder is not available at '{}'. Bookdrop monitoring is disabled. " +
                        "Mount a volume at this path to enable it.", bookdrop);
                this.disabled = true;
                return;
            }
        }

        try {
            log.info("Starting bookdrop folder monitor: {}", bookdrop);
            this.watchService = FileSystems.getDefault().newWatchService();
            this.watchKey = bookdrop.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
            this.running = true;
            this.paused = false;
            this.watchThread = new Thread(this::processEvents, "BookdropFolderWatcher");
            this.watchThread.setDaemon(true);
            this.watchThread.start();
            scanExistingBookdropFiles();
            this.disabled = false;
        } catch (IOException e) {
            log.warn("Failed to start bookdrop folder monitor. Bookdrop monitoring is disabled.", e);
            this.disabled = true;
        }
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        log.info("Stopping bookdrop folder monitor...");
        running = false;
        if (watchThread != null) {
            watchThread.interrupt();
            try {
                watchThread.join(5000);
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for watchThread to stop");
                Thread.currentThread().interrupt();
            }
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.error("Error closing WatchService", e);
            }
        }
        log.info("Stopped bookdrop folder monitor");
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    public void pauseMonitoring() {
        if (disabled) return;
        monitorLock.lock();
        try {
            if (!paused) {
                if (watchKey != null) {
                    watchKey.cancel();
                    watchKey = null;
                }
                paused = true;
                log.info("Bookdrop monitoring paused.");
            } else {
                log.info("Bookdrop monitoring already paused.");
            }
        } finally {
            monitorLock.unlock();
        }
    }

    public void resumeMonitoring() {
        if (disabled) return;
        monitorLock.lock();
        try {
            if (paused) {
                try {
                    watchKey = bookdrop.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE);
                    paused = false;
                    log.info("Bookdrop monitoring resumed.");
                } catch (IOException e) {
                    log.error("Error reregistering bookdrop folder during resume", e);
                }
            } else {
                log.info("Bookdrop monitoring is not paused, cannot resume.");
            }
        } finally {
            monitorLock.unlock();
        }
    }

    private void processEvents() {
        while (running) {
            if (paused) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.info("Bookdrop monitor thread interrupted during pause");
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                log.info("Bookdrop monitor thread interrupted");
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                log.info("WatchService closed, stopping thread");
                return;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    log.warn("Overflow event detected");
                    continue;
                }

                Path context = (Path) event.context();
                Path fullPath = bookdrop.resolve(context);

                log.info("Detected {} event on: {}", kind.name(), fullPath);

                if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    if (Files.isDirectory(fullPath)) {
                        log.info("New directory detected, scanning recursively: {}", fullPath);
                        try (Stream<Path> pathStream = Files.walk(fullPath)) {
                            pathStream
                                    .filter(Files::isRegularFile)
                                    .filter(path -> !FileUtils.shouldIgnore(path))
                                    .filter(path -> BookFileExtension.fromFileName(path.getFileName().toString()).isPresent())
                                    .forEach(path -> eventHandler.enqueueFile(path, StandardWatchEventKinds.ENTRY_CREATE));
                        } catch (IOException e) {
                            log.error("Failed to scan new directory: {}", fullPath, e);
                        }
                    } else {
                        if (!FileUtils.shouldIgnore(fullPath)) {
                            if (BookFileExtension.fromFileName(fullPath.getFileName().toString()).isPresent()) {
                                eventHandler.enqueueFile(fullPath, kind);
                            } else {
                                log.info("Ignored unsupported file type: {}", fullPath);
                            }
                        }
                    }
                } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    if (Files.isDirectory(fullPath)) {
                        log.info("Directory deleted: {}, performing bulk DB cleanup", fullPath);
                    } else {
                        log.info("File deleted: {}", fullPath);
                    }
                    eventHandler.enqueueFile(fullPath, kind);
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                log.warn("WatchKey is no longer valid");
                break;
            }
        }
    }

    public void rescanBookdropFolder() {
        if (disabled) {
            log.warn("Bookdrop monitoring is disabled. Skipping rescan.");
            return;
        }
        log.info("Rescan of Bookdrop folder triggered.");
        convertPendingAcsmFiles();
        scanExistingBookdropFiles();
    }

    /**
     * Convert any {@code .acsm} files dropped into the bookdrop folder into real book files, in place,
     * via the configured external ACSM handler: the produced EPUB/PDF is written alongside and the
     * {@code .acsm} deleted, so the normal scan then ingests the book. No-op when no handler is
     * configured (the {@code .acsm} files are left untouched rather than silently discarded).
     */
    private void convertPendingAcsmFiles() {
        if (bookdrop == null || !acsmHandler.isConfigured()) {
            return;
        }
        List<Path> acsmFiles;
        try (Stream<Path> files = Files.walk(bookdrop)) {
            acsmFiles = files.filter(Files::isRegularFile)
                    .filter(path -> !FileUtils.shouldIgnore(path))
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".acsm"))
                    .toList();
        } catch (IOException e) {
            log.error("Error scanning bookdrop folder for ACSM files", e);
            return;
        }
        for (Path acsm : acsmFiles) {
            try {
                byte[] content = acsmHandler.handle(Files.readAllBytes(acsm));
                if (content == null || content.length == 0) {
                    log.warn("ACSM handler produced no book for bookdrop file {}; leaving it in place", acsm.getFileName());
                    continue;
                }
                boolean isPdf = content.length >= 4
                        && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
                String name = acsm.getFileName().toString();
                String base = name.substring(0, name.length() - ".acsm".length());
                Path out = acsm.resolveSibling(base + (isPdf ? ".pdf" : ".epub"));
                if (Files.exists(out)) {
                    out = acsm.resolveSibling(base + "-" + System.currentTimeMillis() + (isPdf ? ".pdf" : ".epub"));
                }
                Files.write(out, content);
                Files.delete(acsm);
                log.info("Converted bookdrop ACSM {} -> {}", name, out.getFileName());
            } catch (Exception e) {
                log.error("Failed to convert bookdrop ACSM {}: {}", acsm.getFileName(), e.getMessage());
            }
        }
    }

    private void scanExistingBookdropFiles() {
        List<Path> supportedFiles;
        try (Stream<Path> files = Files.walk(bookdrop)) {
            supportedFiles = files.filter(Files::isRegularFile)
                    .filter(path -> !FileUtils.shouldIgnore(path))
                    .filter(path -> BookFileExtension.fromFileName(path.getFileName().toString()).isPresent())
                    .toList();
        } catch (IOException e) {
            log.error("Error scanning bookdrop folder", e);
            return;
        }

        if (!supportedFiles.isEmpty()) {
            List<String> supportedFilePaths = supportedFiles.stream()
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .toList();
            List<String> knownFilePaths = bookdropFileRepository.findAllFilePathsIn(supportedFilePaths);
            Set<String> knownPaths = knownFilePaths == null ? Set.of() : new HashSet<>(knownFilePaths);

            supportedFilePaths.stream()
                    .filter(path -> !knownPaths.contains(path))
                    .map(Path::of)
                    .forEach(path -> {
                        log.info("Found existing supported file: {}", path);
                        eventHandler.enqueueFile(path, StandardWatchEventKinds.ENTRY_CREATE);
                    });
        }
    }
}
