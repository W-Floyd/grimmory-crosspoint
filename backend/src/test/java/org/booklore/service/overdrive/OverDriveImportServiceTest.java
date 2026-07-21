package org.booklore.service.overdrive;

import org.booklore.exception.APIException;
import org.booklore.model.FileProcessResult;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.event.BookAddedEvent;
import org.booklore.service.file.FileMovingHelper;
import org.booklore.service.fileprocessor.BookFileProcessor;
import org.booklore.service.fileprocessor.BookFileProcessorRegistry;
import org.booklore.service.metadata.MetadataRefreshService;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.booklore.model.enums.BookFileType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverDriveImportServiceTest {

    @Mock private LibraryRepository libraryRepository;
    @Mock private BookRepository bookRepository;
    @Mock private FileMovingHelper fileMovingHelper;
    @Mock private BookFileProcessorRegistry processorRegistry;
    @Mock private MonitoringRegistrationService monitoringRegistrationService;
    @Mock private MetadataRefreshService metadataRefreshService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BookFileProcessor processor;
    @Mock private org.booklore.mapper.BookMapper bookMapper;
    @Mock private org.booklore.config.AppProperties appProperties;

    private OverDriveImportService service;

    @TempDir
    Path libraryRoot;

    @BeforeEach
    void setUp() {
        service = new OverDriveImportService(libraryRepository, bookRepository, fileMovingHelper,
                processorRegistry, monitoringRegistrationService, metadataRefreshService, eventPublisher, bookMapper,
                appProperties);
    }

    private LibraryEntity libraryWithPath() {
        LibraryPathEntity path = LibraryPathEntity.builder().id(1L).path(libraryRoot.toString()).build();
        return LibraryEntity.builder().id(1L).name("Lib").libraryPaths(List.of(path)).build();
    }

    @Test
    void importEpub_writesFileProcessesAndPublishesEvent() {
        LibraryEntity library = libraryWithPath();
        Path target = libraryRoot.resolve("Dune.epub");
        when(libraryRepository.findByIdWithPaths(1L)).thenReturn(Optional.of(library));
        when(fileMovingHelper.getFileNamingPattern(library)).thenReturn("{title}");
        when(fileMovingHelper.generateNewFilePath(eq(libraryRoot.toString()), any(), any(), any())).thenReturn(target);
        when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(processor);
        when(processor.processFile(any())).thenReturn(FileProcessResult.builder()
                .book(Book.builder().id(42L).build()).build());

        Book book = service.importBook("data".getBytes(), "Dune.epub", 1L, 1L, null, BookFileType.EPUB);

        assertThat(book.getId()).isEqualTo(42L);
        assertThat(Files.exists(target)).isTrue();
        verify(monitoringRegistrationService).unregisterLibrary(1L);
        verify(eventPublisher).publishEvent(any(BookAddedEvent.class));
        // No metadata supplied → no enrichment call.
        verify(metadataRefreshService, never()).updateBookMetadata(any());
    }

    @Test
    void importEpub_appliesOverDriveMetadataWhenProvided() {
        LibraryEntity library = libraryWithPath();
        Path target = libraryRoot.resolve("Dune.epub");
        when(libraryRepository.findByIdWithPaths(1L)).thenReturn(Optional.of(library));
        when(fileMovingHelper.getFileNamingPattern(library)).thenReturn("{title}");
        when(fileMovingHelper.generateNewFilePath(any(), any(), any(), any())).thenReturn(target);
        when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(processor);
        when(processor.processFile(any())).thenReturn(FileProcessResult.builder()
                .book(Book.builder().id(42L).build()).build());
        when(bookRepository.findByIdWithBookFiles(42L))
                .thenReturn(Optional.of(new org.booklore.model.entity.BookEntity()));

        BookMetadata metadata = BookMetadata.builder().title("Dune").thumbnailUrl("http://c/cover.jpg").build();
        service.importBook("data".getBytes(), "Dune.epub", 1L, 1L, metadata, BookFileType.EPUB);

        verify(metadataRefreshService).updateBookMetadata(any());
    }

    @Test
    void importEpub_rejectsWhenTargetAlreadyExists() throws Exception {
        LibraryEntity library = libraryWithPath();
        Path target = libraryRoot.resolve("Dune.epub");
        Files.writeString(target, "existing");
        when(libraryRepository.findByIdWithPaths(1L)).thenReturn(Optional.of(library));
        when(fileMovingHelper.getFileNamingPattern(library)).thenReturn("{title}");
        when(fileMovingHelper.generateNewFilePath(any(), any(), any(), any())).thenReturn(target);

        assertThatThrownBy(() -> service.importBook("data".getBytes(), "Dune.epub", 1L, 1L, null, BookFileType.EPUB))
                .isInstanceOf(APIException.class);
        verify(processorRegistry, never()).getProcessorOrThrow(any());
    }

    @Test
    void importEpub_rejectsEmptyContent() {
        assertThatThrownBy(() -> service.importBook(new byte[0], "x.epub", 1L, 1L, null, BookFileType.EPUB))
                .isInstanceOf(APIException.class);
        verify(monitoringRegistrationService, never()).unregisterLibrary(any());
    }

}
