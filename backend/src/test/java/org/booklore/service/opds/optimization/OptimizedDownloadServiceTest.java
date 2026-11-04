package org.booklore.service.opds.optimization;

import org.booklore.model.dto.opds.DevicePreset;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.book.BookDownloadService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class OptimizedDownloadServiceTest {

    private FileService fileService;
    private BookFileRepository bookFileRepository;
    private EpubCoverReplacer coverReplacer;
    private DevicePresetService devicePresetService;
    private AppSettingService appSettingService;
    private EpubDeviceOptimizer optimizer;
    private OpdsVariantHashService variantHashService;
    private OptimizedDownloadService service;

    @TempDir
    Path cacheRoot;

    @TempDir
    Path libraryRoot;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        when(fileService.getOpdsCachePath()).thenReturn(cacheRoot.toString());
        bookFileRepository = mock(BookFileRepository.class);
        coverReplacer = mock(EpubCoverReplacer.class);
        devicePresetService = mock(DevicePresetService.class);
        when(devicePresetService.maxSourceFileSizeBytes()).thenReturn(Long.MAX_VALUE);
        appSettingService = mock(AppSettingService.class);
        optimizer = mock(EpubDeviceOptimizer.class);
        variantHashService = mock(OpdsVariantHashService.class);
        when(variantHashService.sourceHash(any())).thenReturn(Optional.of("abc123"));
        when(appSettingService.getAppSettings()).thenReturn(new AppSettings());
        service = new OptimizedDownloadService(
                bookFileRepository,
                mock(BookDownloadService.class),
                optimizer,
                coverReplacer,
                devicePresetService,
                fileService,
                variantHashService,
                appSettingService);
    }

    @Test
    void cachedVariantSize_isEmptyBeforeAnyBuild() {
        assertThat(service.cachedVariantSize(1L, 2L, new DevicePreset(), "X4")).isEmpty();
    }

    @Test
    void cachedVariantSize_reportsTheBuiltVariant() throws Exception {
        DevicePreset preset = new DevicePreset();
        buildOptimized(preset, 64);

        assertThat(service.cachedVariantSize(1L, 2L, preset, "X4")).hasValue(64L);
    }

    static Stream<Arguments> changesThatOutdateABuiltVariant() {
        return Stream.of(
                Arguments.of("source file changed", (Consumer<OptimizedDownloadServiceTest>) t ->
                        when(t.variantHashService.sourceHash(any())).thenReturn(Optional.of("def456"))),
                Arguments.of("preset edited", (Consumer<OptimizedDownloadServiceTest>) t -> t.preset.setMaxWidth(600)),
                Arguments.of("other file", (Consumer<OptimizedDownloadServiceTest>) t -> t.lookupFileId = 3L));
    }

    private DevicePreset preset;
    private long lookupFileId = 2L;

    @ParameterizedTest(name = "{0}")
    @MethodSource("changesThatOutdateABuiltVariant")
    void cachedVariantSize_ignoresAVariantBuiltFromOtherInputs(String change, Consumer<OptimizedDownloadServiceTest> apply)
            throws Exception {
        preset = new DevicePreset();
        buildOptimized(preset, 64);

        apply.accept(this);

        // Advertising the old variant's length would also stop the current one ever being prewarmed.
        assertThat(service.cachedVariantSize(1L, lookupFileId, preset, "X4")).isEmpty();
    }

    @Test
    void rebuildingEvictsTheVariantItSupersedes() throws Exception {
        DevicePreset preset = new DevicePreset();
        buildOptimized(preset, 64);
        preset.setJpegQuality(60);
        buildOptimized(preset, 32);

        try (Stream<Path> cached = Files.list(cacheRoot.resolve("X4"))) {
            assertThat(cached).singleElement().satisfies(p -> assertThat(Files.size(p)).isEqualTo(32L));
        }
    }

    private void buildOptimized(DevicePreset preset, int bytes) throws Exception {
        Path source = libraryRoot.resolve("book.epub");
        if (!Files.exists(source)) Files.write(source, new byte[128]);
        when(bookFileRepository.findByIdWithBookAndLibraryPath(2L)).thenReturn(Optional.of(epubFile(source)));
        doAnswer(inv -> Files.write(inv.<Path>getArgument(2), new byte[bytes]))
                .when(optimizer).optimize(any(), any(), any());
        assertThat(service.servedSize(1L, 2L, preset, "X4")).hasValue(bytes);
    }

    @Test
    void anUntransformedVariantIsCachedWholeWithNoTempFileLeft() throws Exception {
        Path source = Files.write(libraryRoot.resolve("book.epub"), new byte[4096]);
        Path cover = Files.write(libraryRoot.resolve("cover.jpg"), new byte[16]);
        when(fileService.getCoverFile(1L)).thenReturn(cover.toString());
        AppSettings settings = new AppSettings();
        settings.setOpdsReplaceCover(true);
        when(appSettingService.getAppSettings()).thenReturn(settings);
        // Cover replacement requested but not applied, so the source itself is what gets cached.
        when(coverReplacer.replaceCover(any(), any(), anyString(), any())).thenReturn(false);
        when(bookFileRepository.findByIdWithBookAndLibraryPath(2L)).thenReturn(Optional.of(epubFile(source)));

        assertThat(service.servedSize(1L, 2L, null, null)).hasValue(4096L);

        try (Stream<Path> cached = Files.list(cacheRoot.resolve(OptimizedDownloadService.COVER_VARIANT))) {
            assertThat(cached).singleElement()
                    .satisfies(p -> assertThat(p.getFileName().toString()).endsWith(".epub"))
                    .satisfies(p -> assertThat(Files.mismatch(p, source)).isEqualTo(-1L));
        }
    }

    @Test
    void atMostTwoPrewarmsBuildAtOnce() throws Exception {
        Path source = Files.write(libraryRoot.resolve("book.epub"), new byte[64]);
        Path cover = Files.write(libraryRoot.resolve("cover.jpg"), new byte[16]);
        when(fileService.getCoverFile(1L)).thenReturn(cover.toString());
        AppSettings settings = new AppSettings();
        settings.setOpdsReplaceCover(true);
        when(appSettingService.getAppSettings()).thenReturn(settings);
        when(bookFileRepository.findByIdWithBookAndLibraryPath(any())).thenAnswer(inv -> {
            BookFileEntity file = epubFile(source);
            file.setId(inv.getArgument(0));
            return Optional.of(file);
        });
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger building = new AtomicInteger();
        when(coverReplacer.replaceCover(any(), any(), anyString(), any())).thenAnswer(inv -> {
            building.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return false;
        });

        List<Thread> warmers = LongStream.rangeClosed(2, 4)
                .mapToObj(fileId -> Thread.startVirtualThread(() -> service.prewarm(1L, fileId, null, null)))
                .toList();
        await().atMost(Duration.ofSeconds(5)).until(() -> building.get() == 2);
        // The third found no permit and returned without building.
        warmers.get(2).join(Duration.ofSeconds(5));
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> building.get() == 2);

        release.countDown();
        for (Thread t : warmers) t.join(Duration.ofSeconds(5));
    }

    private BookFileEntity epubFile(Path source) {
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(source.getParent().toString());
        BookEntity book = BookEntity.builder().id(1L).libraryPath(libraryPath).build();
        return BookFileEntity.builder().id(2L).book(book).fileSubPath("").fileName(source.getFileName().toString())
                .bookType(BookFileType.EPUB).currentHash("abc123").build();
    }
}
