import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {ReaderViewManagerService} from '../../core/view-manager.service';
import {READALONG_RATES, ReaderReadalongService} from './readalong.service';

describe('ReaderReadalongService', () => {
  let service: ReaderReadalongService;
  const viewManager = {
    hasMediaOverlay: vi.fn(),
    startMediaOverlay: vi.fn(),
    pauseMediaOverlay: vi.fn(),
    resumeMediaOverlay: vi.fn(),
    stopMediaOverlay: vi.fn(),
    prevMediaOverlayPhrase: vi.fn(),
    nextMediaOverlayPhrase: vi.fn(),
    setMediaOverlayRate: vi.fn(),
    setMediaOverlayVolume: vi.fn(),
  };

  beforeEach(() => {
    localStorage.clear();
    Object.values(viewManager).forEach(mock => mock.mockReset());
    viewManager.hasMediaOverlay.mockReturnValue(true);
    viewManager.startMediaOverlay.mockReturnValue(of(undefined));

    TestBed.configureTestingModule({
      providers: [
        ReaderReadalongService,
        {provide: ReaderViewManagerService, useValue: viewManager},
      ]
    });

    service = TestBed.inject(ReaderReadalongService);
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  it('reports availability only after detection', () => {
    expect(service.available()).toBe(false);

    service.detectAvailability();

    expect(service.available()).toBe(true);
  });

  it('does not start playback for a book without overlays', () => {
    viewManager.hasMediaOverlay.mockReturnValue(false);
    service.detectAvailability();

    service.start();

    expect(viewManager.startMediaOverlay).not.toHaveBeenCalled();
    expect(service.state()).toBe('stopped');
  });

  it('cycles through play, pause and resume', () => {
    service.detectAvailability();

    service.toggle();
    expect(viewManager.startMediaOverlay).toHaveBeenCalledTimes(1);
    expect(service.state()).toBe('playing');

    service.toggle();
    expect(viewManager.pauseMediaOverlay).toHaveBeenCalledTimes(1);
    expect(service.state()).toBe('paused');

    service.toggle();
    expect(viewManager.resumeMediaOverlay).toHaveBeenCalledTimes(1);
    expect(service.state()).toBe('playing');
    // Resuming must not restart the player from the top of the section
    expect(viewManager.startMediaOverlay).toHaveBeenCalledTimes(1);
  });

  it('applies stored rate and volume when starting', () => {
    service.detectAvailability();
    service.setRate(1.5);
    service.setVolume(0.4);
    viewManager.setMediaOverlayRate.mockClear();
    viewManager.setMediaOverlayVolume.mockClear();

    service.start();

    expect(viewManager.setMediaOverlayRate).toHaveBeenCalledWith(1.5);
    expect(viewManager.setMediaOverlayVolume).toHaveBeenCalledWith(0.4);
  });

  it('returns to stopped when the player fails to start', () => {
    viewManager.startMediaOverlay.mockReturnValue(throwError(() => new Error('no overlay')));
    service.detectAvailability();

    service.start();

    expect(service.state()).toBe('stopped');
  });

  it('treats a playback error as the end of playback', () => {
    service.detectAvailability();
    service.start();

    service.handlePlaybackError();

    expect(service.state()).toBe('stopped');
  });

  it('ignores phrase stepping while stopped', () => {
    service.detectAvailability();

    service.nextPhrase();
    service.previousPhrase();

    expect(viewManager.nextMediaOverlayPhrase).not.toHaveBeenCalled();
    expect(viewManager.prevMediaOverlayPhrase).not.toHaveBeenCalled();
  });

  it('wraps around the end of the rate list', () => {
    service.setRate(READALONG_RATES[READALONG_RATES.length - 1]);

    service.cycleRate();

    expect(service.rate()).toBe(READALONG_RATES[0]);
    expect(viewManager.setMediaOverlayRate).toHaveBeenLastCalledWith(READALONG_RATES[0]);
  });

  it('clamps volume to the audible range', () => {
    service.setVolume(2);
    expect(service.volume()).toBe(1);

    service.setVolume(-1);
    expect(service.volume()).toBe(0);
  });

  it('restores rate and volume from storage for the next book', () => {
    service.setRate(1.75);
    service.setVolume(0.25);

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        ReaderReadalongService,
        {provide: ReaderViewManagerService, useValue: viewManager},
      ]
    });
    const revived = TestBed.inject(ReaderReadalongService);

    expect(revived.rate()).toBe(1.75);
    expect(revived.volume()).toBe(0.25);
  });

  it('stops playback on reset so narration does not outlive the reader', () => {
    service.detectAvailability();
    service.start();

    service.reset();

    expect(viewManager.stopMediaOverlay).toHaveBeenCalledTimes(1);
    expect(service.state()).toBe('stopped');
    expect(service.available()).toBe(false);
  });
});
