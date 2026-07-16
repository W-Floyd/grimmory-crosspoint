package org.booklore.service.opds.optimization;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.OpdsVariantHashEntity;
import org.booklore.repository.OpdsVariantHashRepository;
import org.booklore.service.file.FileFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OpdsVariantHashServiceTest {

    private OpdsVariantHashRepository repository;
    private OpdsVariantHashService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        repository = mock(OpdsVariantHashRepository.class);
        service = new OpdsVariantHashService(repository);
    }

    private Path variantFile() throws Exception {
        Path f = tempDir.resolve("variant.epub");
        byte[] data = new byte[5000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
        Files.write(f, data);
        return f;
    }

    private BookFileEntity bookFile(long fileId, long bookId) {
        BookEntity book = mock(BookEntity.class);
        when(book.getId()).thenReturn(bookId);
        BookFileEntity bf = mock(BookFileEntity.class);
        when(bf.getId()).thenReturn(fileId);
        when(bf.getBook()).thenReturn(book);
        return bf;
    }

    @Test
    void register_insertsMappingWithVariantPartialMd5() throws Exception {
        Path variant = variantFile();
        when(repository.findByBookFile_IdAndPreset(1L, "X3")).thenReturn(Optional.empty());

        service.register(bookFile(1L, 10L), "X3", "src-hash-abc", variant);

        ArgumentCaptor<OpdsVariantHashEntity> captor = ArgumentCaptor.forClass(OpdsVariantHashEntity.class);
        verify(repository).save(captor.capture());
        OpdsVariantHashEntity saved = captor.getValue();
        assertThat(saved.getPreset()).isEqualTo("X3");
        assertThat(saved.getSourceHash()).isEqualTo("src-hash-abc");
        assertThat(saved.getBook().getId()).isEqualTo(10L);
        assertThat(saved.getVariantHash())
                .isEqualTo(FileFingerprint.generateHash(variant))
                .matches("[0-9a-f]{32}");
    }

    @Test
    void register_skipsWhenVariantHashUnchanged() throws Exception {
        Path variant = variantFile();
        OpdsVariantHashEntity existing = OpdsVariantHashEntity.builder()
                .preset("X3").sourceHash("same").variantHash(FileFingerprint.generateHash(variant)).build();
        when(repository.findByBookFile_IdAndPreset(1L, "X3")).thenReturn(Optional.of(existing));

        service.register(bookFile(1L, 10L), "X3", "same", variant);

        verify(repository, never()).save(any());
    }

    @Test
    void register_updatesWhenVariantBytesChangeEvenIfSourceHashUnchanged() throws Exception {
        // Cover replacement changes the served bytes without changing the source file hash;
        // the row must refresh to the new variant hash or KOReader sync 404s.
        OpdsVariantHashEntity existing = OpdsVariantHashEntity.builder()
                .preset("xteink-x4").sourceHash("H").variantHash("stale-non-cover-hash").build();
        when(repository.findByBookFile_IdAndPreset(1L, "xteink-x4")).thenReturn(Optional.of(existing));

        Path variant = variantFile();
        service.register(bookFile(1L, 10L), "xteink-x4", "H", variant);

        ArgumentCaptor<OpdsVariantHashEntity> captor = ArgumentCaptor.forClass(OpdsVariantHashEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getVariantHash()).isEqualTo(FileFingerprint.generateHash(variant));
        assertThat(captor.getValue().getSourceHash()).isEqualTo("H");
    }

    @Test
    void register_neverThrowsOnFailure() {
        when(repository.findByBookFile_IdAndPreset(anyLong(), any())).thenThrow(new RuntimeException("db down"));
        // Missing/unreadable variant file + repo failure must not propagate.
        service.register(bookFile(1L, 10L), "X3", "src", tempDir.resolve("missing.epub"));
        verify(repository, never()).save(any());
    }

    @Test
    void findBookByVariantHash_delegates_andGuardsBlank() {
        BookEntity book = mock(BookEntity.class);
        when(repository.findBookByVariantHash("abc")).thenReturn(Optional.of(book));

        assertThat(service.findBookByVariantHash("abc")).containsSame(book);
        assertThat(service.findBookByVariantHash("")).isEmpty();
        assertThat(service.findBookByVariantHash(null)).isEmpty();
        verify(repository, never()).findBookByVariantHash("");
    }
}
