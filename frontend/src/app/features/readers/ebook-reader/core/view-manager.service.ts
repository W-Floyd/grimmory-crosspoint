import {inject, Injectable} from '@angular/core';
import {defer, from, Observable, of, Subject, throwError, timer} from 'rxjs';
import {catchError, map, switchMap, tap} from 'rxjs/operators';
import {ReaderAnnotationService, Annotation} from '../features/annotations/annotation-renderer.service';
import {ReaderEventService, ViewEvent, TextSelection} from './event.service';
import {PageInfo, ThemeInfo, PageDecorator} from '../shared/header-footer.util';
import {EpubStreamingService, EpubBookInfo} from './epub-streaming.service';

export type {ViewEvent, TextSelection} from './event.service';
export type {PageInfo, ThemeInfo} from '../shared/header-footer.util';

interface FoliateTocItem {
  label: string;
  href: string;
  subitems?: FoliateTocItem[];
}

interface TocItem {
  label: string;
  href: string;
  subitems?: TocItem[];
}

export interface BookMetadata {
  title?: string;
  authors?: string[];
  language?: string;
  publisher?: string;
  description?: string;
  identifier?: string;
  coverUrl?: string | null;

  [key: string]: unknown;
}

interface RendererContent {
  index: number;
  doc: Document;
}

export interface FoliateRenderer {
  heads?: HTMLElement[];
  feet?: HTMLElement[];
  getContents(): RendererContent[];
  setAttribute(name: string, value: string | number): void;
  removeAttribute(name: string): void;
  setStyles?(css: string): void;
}

interface FoliateBook {
  toc?: FoliateTocItem[];
  metadata?: BookMetadata;
  getCover?(): Promise<Blob | null> | null;
}

interface FoliateSearchSubitem {
  cfi: string;
  excerpt: {
    pre: string;
    match: string;
    post: string;
  };
}

interface FoliateSearchProgress {
  progress: number;
}

interface FoliateSearchSectionResult {
  label?: string;
  subitems?: FoliateSearchSubitem[];
}

type FoliateSearchResult = FoliateSearchProgress | FoliateSearchSectionResult | 'done';

/**
 * Foliate's SMIL media overlay player: the EPUB3 readalong engine that drives narration
 * audio and keeps the highlighted text in step with it.
 */
interface FoliateMediaOverlay {
  pause(): void;
  resume(): void;
  stop(): void;
  prev(): void;
  next(): void;
  setVolume(volume: number): void;
  setRate(rate: number): void;
}

interface FoliateViewElement extends HTMLElement {
  renderer?: FoliateRenderer | null;
  book?: FoliateBook;
  mediaOverlay?: FoliateMediaOverlay | null;
  hasMediaOverlay?: boolean;
  startMediaOverlay?(): Promise<void>;
  open(target: File | object): Promise<void>;
  goTo(target: string | number): Promise<void>;
  goToFraction(fraction: number): Promise<void>;
  prev(): void;
  next(): void;
  getCFI(index: number, range: Range): string | null;
  deselect(): void;
  addAnnotation(annotation: { value: string }): void;
  deleteAnnotation(annotation: { value: string }): Promise<void>;
  showAnnotation(annotation: { value: string }): Promise<void>;
  getSectionFractions?(): number[];
  search?(opts: { query: string; matchCase?: boolean; matchWholeWords?: boolean }): AsyncGenerator<FoliateSearchResult>;
  clearSearch?(): void;
}

interface StreamingBookFactoryWindow extends Window {
  makeStreamingBook?: (
    bookId: number,
    baseUrl: string,
    bookInfo: EpubBookInfo,
    authToken: string | null,
    bookType?: string,
    fileId?: number
  ) => Promise<object>;
}

@Injectable({
  providedIn: 'root'
})
export class ReaderViewManagerService {
  private annotationService = inject(ReaderAnnotationService);
  private eventService = inject(ReaderEventService);
  private epubStreamingService = inject(EpubStreamingService);
  private view: FoliateViewElement | null = null;
  private readonly navigation = new Subject<void>();

  public get events$(): Observable<ViewEvent> {
    return this.eventService.events$;
  }

  /**
   * Emits when the reader is moved deliberately — a page turn, the progress slider, a
   * chapter jump. Media overlay highlighting drives the renderer directly and is
   * deliberately absent here, so a listener can tell a reader's intent from playback
   * following along.
   */
  public get navigation$(): Observable<void> {
    return this.navigation.asObservable();
  }

  createView(container: HTMLElement): void {
    this.view = document.createElement('foliate-view') as FoliateViewElement;
    this.view.style.width = '100%';
    this.view.style.height = '100%';
    this.view.style.display = 'block';
    container.appendChild(this.view);

    this.eventService.initialize(this.view, {
      prev: () => this.prev(),
      next: () => this.next(),
      getCFI: (index: number, range: Range) => this.view?.getCFI(index, range) ?? null,
      getContents: () => this.view?.renderer?.getContents() ?? null
    });
  }

  loadEpub(epubPath: string): Observable<void> {
    if (!this.view) {
      return throwError(() => new Error('View not created'));
    }

    const view = this.view;
    return timer(100).pipe(
      switchMap(() => from(fetch(epubPath))),
      switchMap(response => {
        if (!response.ok) {
          throw new Error(`EPUB not found: ${response.status}`);
        }
        return from(response.blob());
      }),
      switchMap(blob => {
        const file = new File([blob], epubPath.split('/').pop() || 'book.epub', {
          type: 'application/epub+zip'
        });
        return from(view.open(file) as Promise<void>);
      }),
      map(() => undefined),
      catchError(err => throwError(() => err))
    );
  }

  loadEpubStreaming(bookId: number, bookType?: string, fileId?: number): Observable<void> {
    if (!this.view) {
      return throwError(() => new Error('View not created'));
    }

    return this.epubStreamingService.getBookInfo(bookId, bookType, fileId).pipe(
      switchMap(bookInfo => from(this.openStreamingBook(bookId, bookInfo, bookType, fileId))),
      map(() => undefined),
      catchError(err => throwError(() => err))
    );
  }

  private async openStreamingBook(bookId: number, bookInfo: EpubBookInfo, bookType?: string, fileId?: number): Promise<void> {
    const makeStreamingBook = (window as StreamingBookFactoryWindow).makeStreamingBook;
    if (!makeStreamingBook) {
      throw new Error('makeStreamingBook not available - Foliate script may not be loaded');
    }
    const baseUrl = this.epubStreamingService.getBaseUrl();
    const authToken = this.epubStreamingService.getAuthToken();
    const book = await makeStreamingBook(bookId, baseUrl, bookInfo, authToken, bookType, fileId);
    const view = this.view;
    if (!view) {
      throw new Error('View not created');
    }
    await view.open(book);
  }

  destroy(): void {
    this.eventService.destroy();
    this.view?.remove();
    this.view = null;
  }

  goTo(target?: string | number | null): Observable<void> {
    const resolvedTarget = target ?? 0;
    if (!this.view) {
      return of(undefined);
    }
    const view = this.view;
    return defer(() =>
      from(view.goTo(resolvedTarget) as Promise<void>)
    ).pipe(
      map(() => undefined),
      tap(() => this.navigation.next())
    );
  }

  goToSection(index: number): Observable<void> {
    return this.goTo(index);
  }

  goToFraction(fraction: number): Observable<void> {
    if (!this.view) {
      return of(undefined);
    }
    const view = this.view;
    return defer(() => from(view.goToFraction(fraction) as Promise<void>)).pipe(
      map(() => undefined),
      tap(() => this.navigation.next())
    );
  }

  prev(): void {
    this.view?.prev();
    this.navigation.next();
  }

  next(): void {
    this.view?.next();
    this.navigation.next();
  }

  getRenderer(): FoliateRenderer | null {
    return this.view?.renderer ?? null;
  }

  getSelection(): TextSelection | null {
    const renderer = this.getRenderer();
    if (!renderer) return null;

    const contents = renderer.getContents();
    if (!contents || contents.length === 0) return null;

    const {index, doc} = contents[0];
    if (!doc) return null;

    const selection = doc.defaultView?.getSelection();
    if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;

    const range = selection.getRangeAt(0);
    const text = range.toString().trim();
    if (!text) return null;

    const cfi = this.view?.getCFI(index, range);
    if (!cfi) return null;

    return {text, cfi, range, index};
  }

  clearSelection(): void {
    this.view?.deselect();
  }

  addAnnotation(annotation: Annotation): Observable<{index: number; label: string} | undefined> {
    return this.annotationService.addAnnotation(this.view, annotation);
  }

  deleteAnnotation(cfi: string): Observable<void> {
    return this.annotationService.deleteAnnotation(this.view, cfi);
  }

  addAnnotations(annotations: Annotation[]): void {
    this.annotationService.addAnnotations(this.view, annotations);
  }

  updateHeadersAndFooters(chapterName: string, pageInfo?: PageInfo, theme?: ThemeInfo, timeRemainingLabel?: string): void {
    const renderer = this.getRenderer();
    PageDecorator.updateHeadersAndFooters(renderer, chapterName, pageInfo, theme, timeRemainingLabel);
  }

  getChapters(): TocItem[] {
    if (!this.view?.book?.toc) return [];

    const mapToc = (items: FoliateTocItem[]): TocItem[] =>
      items.map(item => ({
        label: item.label,
        href: item.href,
        subitems: item.subitems?.length ? mapToc(item.subitems) : undefined
      }));

    return mapToc(this.view.book.toc);
  }

  getSectionFractions(): number[] {
    if (!this.view?.getSectionFractions) return [];
    return this.view.getSectionFractions();
  }

  getMetadata(): Observable<BookMetadata> {
    if (!this.view?.book?.metadata) {
      return of({});
    }

    const {metadata} = this.view.book;

    return this.getCoverUrl().pipe(
      map(coverUrl => ({
        title: metadata.title,
        authors: metadata.authors,
        language: metadata.language,
        publisher: metadata.publisher,
        description: metadata.description,
        identifier: metadata.identifier,
        coverUrl,
        ...metadata
      }))
    );
  }

  getCover(): Observable<Blob | null> {
    if (!this.view?.book?.getCover) {
      return of(null);
    }
    const book = this.view.book;
    return defer(() => {
      const coverPromise = book.getCover?.();
      return coverPromise ? from(coverPromise as Promise<Blob | null>) : of(null);
    });
  }

  getCoverUrl(): Observable<string | null> {
    return this.getCover().pipe(
      map(blob => blob ? URL.createObjectURL(blob) : null)
    );
  }

  /**
   * Whether the open book ships SMIL media overlays. Only meaningful once the book is open:
   * the player is built during `view.open`.
   */
  hasMediaOverlay(): boolean {
    return this.view?.hasMediaOverlay === true;
  }

  /** Starts readalong playback at the section currently on screen. */
  startMediaOverlay(): Observable<void> {
    const view = this.view;
    if (!view?.startMediaOverlay) {
      return throwError(() => new Error('Media overlay not available'));
    }
    return defer(() => from(view.startMediaOverlay!())).pipe(map(() => undefined));
  }

  pauseMediaOverlay(): void {
    this.view?.mediaOverlay?.pause();
  }

  resumeMediaOverlay(): void {
    this.view?.mediaOverlay?.resume();
  }

  stopMediaOverlay(): void {
    this.view?.mediaOverlay?.stop();
  }

  /** Steps to the previous SMIL phrase, not the previous page. */
  prevMediaOverlayPhrase(): void {
    this.view?.mediaOverlay?.prev();
  }

  nextMediaOverlayPhrase(): void {
    this.view?.mediaOverlay?.next();
  }

  setMediaOverlayRate(rate: number): void {
    this.view?.mediaOverlay?.setRate(rate);
  }

  setMediaOverlayVolume(volume: number): void {
    this.view?.mediaOverlay?.setVolume(volume);
  }

  async* search(opts: { query: string; matchCase?: boolean; matchWholeWords?: boolean }): AsyncGenerator<FoliateSearchResult> {
    const search = this.view?.search;
    if (!search) return;
    yield* search.call(this.view, opts);
  }

  clearSearch(): void {
    this.view?.clearSearch?.();
  }
}
