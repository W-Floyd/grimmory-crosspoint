import {computed, inject, Injectable, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {catchError, debounceTime, of, tap} from 'rxjs';
import {ReaderViewManagerService} from '../../core/view-manager.service';
import {LocalStorageService} from '../../../../../shared/service/local-storage.service';

export type ReadalongState = 'stopped' | 'playing' | 'paused';

/** Playback speeds offered in the reader, in the order they cycle. */
export const READALONG_RATES = [0.75, 1, 1.25, 1.5, 1.75, 2] as const;

const RATE_STORAGE_KEY = 'reader.readalong.rate';
const VOLUME_STORAGE_KEY = 'reader.readalong.volume';

/** How long navigation must be quiet before playback re-anchors to the new location. */
const NAVIGATION_SETTLE_MS = 300;

/**
 * Drives EPUB3 media overlay (readalong) playback for the ebook reader.
 *
 * The underlying Foliate player exposes no state of its own, so playback state is tracked
 * here: every transition is user-initiated apart from reaching the end of the book, which
 * surfaces as an error event.
 */
@Injectable()
export class ReaderReadalongService {
  private readonly viewManager = inject(ReaderViewManagerService);
  // Guarded storage: a browser blocking site data throws on access, which in a field initializer
  // would keep the whole reader from opening.
  private readonly storage = inject(LocalStorageService);

  private readonly _available = signal(false);
  private readonly _state = signal<ReadalongState>('stopped');
  private readonly _rate = signal(this.readStoredNumber(RATE_STORAGE_KEY, 1));
  private readonly _volume = signal(this.readStoredNumber(VOLUME_STORAGE_KEY, 1));

  readonly available = this._available.asReadonly();
  readonly state = this._state.asReadonly();
  readonly rate = this._rate.asReadonly();
  readonly volume = this._volume.asReadonly();
  readonly isPlaying = computed(() => this._state() === 'playing');

  constructor() {
    this.viewManager.navigation$
      .pipe(
        // Playback drives the renderer phrase by phrase, so while it runs it drags the
        // reader straight back to the audio. Stop that immediately or navigating during
        // playback is a fight the reader cannot win.
        tap(() => {
          if (this._state() === 'playing') this.viewManager.pauseMediaOverlay();
        }),
        // Settle first: a slider drag or held page-turn key is a burst, not one move.
        debounceTime(NAVIGATION_SETTLE_MS),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.resyncToReader());
  }

  /**
   * Re-anchors playback to wherever the reader now is, so seeking moves the narration
   * rather than being undone by it.
   */
  private resyncToReader(): void {
    if (this._state() !== 'playing') return;
    // Undo the pause above before restarting: the player will not resume a paused
    // element, it would only seek and highlight.
    this.viewManager.resumeMediaOverlay();
    this.viewManager.startMediaOverlay()
      .pipe(catchError((error: unknown) => {
        console.error('Readalong failed to resync after navigation', error);
        this._state.set('stopped');
        return of(undefined);
      }))
      .subscribe();
  }

  /**
   * Call once the book is open: whether overlays exist can only be known after Foliate has
   * parsed the package document.
   */
  detectAvailability(): void {
    this._available.set(this.viewManager.hasMediaOverlay());
  }

  toggle(): void {
    switch (this._state()) {
      case 'playing':
        this.pause();
        break;
      case 'paused':
        this.resume();
        break;
      default:
        this.start();
    }
  }

  start(): void {
    if (!this._available()) return;
    // Apply the stored preferences to the element the player is about to create.
    this.viewManager.setMediaOverlayRate(this._rate());
    this.viewManager.setMediaOverlayVolume(this._volume());
    this._state.set('playing');
    this.viewManager.startMediaOverlay()
      .pipe(catchError((error: unknown) => {
        // Never swallow this: a failed clip, an unreadable SMIL document and a book whose
        // overlays don't line up with its spine all land here and are otherwise silent.
        console.error('Readalong failed to start', error);
        this._state.set('stopped');
        return of(undefined);
      }))
      .subscribe();
  }

  /**
   * Jumps narration to the phrase covering a clicked point, starting playback if it was
   * stopped. A click on text the overlay doesn't narrate leaves playback as it was.
   */
  seekToPhraseAt(doc: Document, ids: string[]): void {
    if (!this._available() || ids.length === 0) return;
    const previous = this._state();
    // The player will not play a paused element; it would seek and highlight in silence.
    if (previous === 'paused') this.viewManager.resumeMediaOverlay();

    this.viewManager.startMediaOverlayAt(doc, ids)
      .pipe(catchError((error: unknown) => {
        console.error('Readalong failed to seek to the clicked phrase', error);
        return of(false);
      }))
      .subscribe(matched => {
        if (matched) {
          this._state.set('playing');
          return;
        }
        // Searching for the phrase pauses the audio, so put back what was interrupted.
        if (previous === 'playing') this.viewManager.resumeMediaOverlay();
        else if (previous === 'paused') this.viewManager.pauseMediaOverlay();
      });
  }

  pause(): void {
    if (this._state() !== 'playing') return;
    this.viewManager.pauseMediaOverlay();
    this._state.set('paused');
  }

  resume(): void {
    if (this._state() !== 'paused') return;
    this.viewManager.resumeMediaOverlay();
    this._state.set('playing');
  }

  stop(): void {
    if (this._state() === 'stopped') return;
    this.viewManager.stopMediaOverlay();
    this._state.set('stopped');
  }

  previousPhrase(): void {
    if (this._state() === 'stopped') return;
    this.viewManager.prevMediaOverlayPhrase();
  }

  nextPhrase(): void {
    if (this._state() === 'stopped') return;
    this.viewManager.nextMediaOverlayPhrase();
  }

  /** Advances to the next speed in {@link READALONG_RATES}, wrapping at the end. */
  cycleRate(): void {
    const index = READALONG_RATES.findIndex(rate => rate === this._rate());
    const next = READALONG_RATES[(index + 1) % READALONG_RATES.length];
    this.setRate(next);
  }

  setRate(rate: number): void {
    this._rate.set(rate);
    this.storage.set(RATE_STORAGE_KEY, rate);
    this.viewManager.setMediaOverlayRate(rate);
  }

  setVolume(volume: number): void {
    const clamped = Math.min(Math.max(volume, 0), 1);
    this._volume.set(clamped);
    this.storage.set(VOLUME_STORAGE_KEY, clamped);
    this.viewManager.setMediaOverlayVolume(clamped);
  }

  /**
   * The player reports a failed clip and the end of the book the same way, so playback is
   * simply wound back to a stopped state.
   */
  handlePlaybackError(): void {
    this._state.set('stopped');
  }

  reset(): void {
    this.stop();
    this._available.set(false);
  }

  private readStoredNumber(key: string, fallback: number): number {
    const stored = this.storage.get<unknown>(key);
    return typeof stored === 'number' && Number.isFinite(stored) ? stored : fallback;
  }
}
