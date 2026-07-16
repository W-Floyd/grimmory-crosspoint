package org.booklore.service.opds.optimization;

import org.booklore.repository.BookFileRepository;
import org.booklore.service.book.BookDownloadService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptimizedDownloadServiceTest {

    private FileService fileService;
    private OptimizedDownloadService service;

    @TempDir
    Path cacheRoot;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        when(fileService.getOpdsCachePath()).thenReturn(cacheRoot.toString());
        service = new OptimizedDownloadService(
                mock(BookFileRepository.class),
                mock(BookDownloadService.class),
                mock(EpubDeviceOptimizer.class),
                mock(EpubCoverReplacer.class),
                mock(DevicePresetService.class),
                fileService,
                mock(OpdsVariantHashService.class),
                mock(org.booklore.service.appsettings.AppSettingService.class));
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
}
