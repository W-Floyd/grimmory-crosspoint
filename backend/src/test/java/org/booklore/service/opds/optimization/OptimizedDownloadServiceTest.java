package org.booklore.service.opds.optimization;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(appSettingService.getAppSettings()).thenReturn(new AppSettings());
        service = new OptimizedDownloadService(
                bookFileRepository,
                mock(BookDownloadService.class),
                mock(EpubDeviceOptimizer.class),
                coverReplacer,
                devicePresetService,
                fileService,
                mock(OpdsVariantHashService.class),
                appSettingService);
    }

    @Test
    void cachedVariantSize_returnsEmptyWhenPresetDirMissing() {
        assertThat(service.cachedVariantSize(1L, 2L, "X4")).isEmpty();
    }

    @Test
    void cachedVariantSize_returnsSizeOfMatchingVariant() throws Exception {
        Path presetDir = Files.createDirectories(cacheRoot.resolve("X4"));
        Files.write(presetDir.resolve("1_2_abcdef.epub"), new byte[64]);

        assertThat(service.cachedVariantSize(1L, 2L, "X4")).hasValue(64L);
    }

    @Test
    void cachedVariantSize_ignoresOtherBooksAndFiles() throws Exception {
        Path presetDir = Files.createDirectories(cacheRoot.resolve("X4"));
        // Same file id but different (prefix-colliding) book id, and a different file id for the book.
        Files.write(presetDir.resolve("11_2_hash.epub"), new byte[10]);
        Files.write(presetDir.resolve("1_23_hash.epub"), new byte[20]);

        assertThat(service.cachedVariantSize(1L, 2L, "X4")).isEmpty();
    }

    @Test
    void cachedVariantSize_ignoresEmptyVariantFile() throws Exception {
        Path presetDir = Files.createDirectories(cacheRoot.resolve("X4"));
        Files.write(presetDir.resolve("1_2_hash.epub"), new byte[0]);

        assertThat(service.cachedVariantSize(1L, 2L, "X4")).isEmpty();
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

    private BookFileEntity epubFile(Path source) {
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(source.getParent().toString());
        BookEntity book = BookEntity.builder().id(1L).libraryPath(libraryPath).build();
        return BookFileEntity.builder().id(2L).book(book).fileSubPath("").fileName(source.getFileName().toString())
                .bookType(BookFileType.EPUB).currentHash("abc123").build();
    }
}
