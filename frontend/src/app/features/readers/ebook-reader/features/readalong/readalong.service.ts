import {computed, inject, Injectable, signal} from '@angular/core';
import {catchError, of} from 'rxjs';
import {ReaderViewManagerService} from '../../core/view-manager.service';

export type ReadalongState = 'stopped' | 'playing' | 'paused';

/** Playback speeds offered in the reader, in the order they cycle. */
export const READALONG_RATES = [0.75, 1, 1.25, 1.5, 1.75, 2] as const;

const RATE_STORAGE_KEY = 'reader.readalong.rate';
const VOLUME_STORAGE_KEY = 'reader.readalong.volume';

/**
 * Drives EPUB3 media overlay (readalong) playback for the ebook reader.
 *
 * The underlying Foliate player exposes no state of its own, so playback state is tracked
 * here: every transition is user-initiated apart from reaching the end of the book, which
 * surfaces as an error event.
 */
@Injectable()
export class ReaderReadalongService {
  private viewManager = inject(ReaderViewManagerService);

  private readonly _available = signal(false);
  private readonly _state = signal<ReadalongState>('stopped');
  private readonly _rate = signal(readStoredNumber(RATE_STORAGE_KEY, 1));
  private readonly _volume = signal(readStoredNumber(VOLUME_STORAGE_KEY, 1));

  readonly available = this._available.asReadonly();
  readonly state = this._state.asReadonly();
  readonly rate = this._rate.asReadonly();
  readonly volume = this._volume.asReadonly();
  readonly isPlaying = computed(() => this._state() === 'playing');

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
      .pipe(catchError(() => {
        this._state.set('stopped');
        return of(undefined);
      }))
      .subscribe();
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
    const index = READALONG_RATES.indexOf(this._rate() as typeof READALONG_RATES[number]);
    const next = READALONG_RATES[(index + 1) % READALONG_RATES.length];
    this.setRate(next);
  }

  setRate(rate: number): void {
    this._rate.set(rate);
    writeStoredNumber(RATE_STORAGE_KEY, rate);
    this.viewManager.setMediaOverlayRate(rate);
  }

  setVolume(volume: number): void {
    const clamped = Math.min(Math.max(volume, 0), 1);
    this._volume.set(clamped);
    writeStoredNumber(VOLUME_STORAGE_KEY, clamped);
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
}

function readStoredNumber(key: string, fallback: number): number {
  const raw = localStorage.getItem(key);
  if (raw === null) return fallback;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function writeStoredNumber(key: string, value: number): void {
  localStorage.setItem(key, String(value));
}
