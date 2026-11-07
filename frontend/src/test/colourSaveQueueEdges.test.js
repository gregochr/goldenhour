import { describe, it, expect, vi } from 'vitest';
import {
  colourAfterRead, createColourSaveQueue, keepColourSaveLineOpen, saveColourInTurn,
} from '../utils/colourSaveQueue.js';

describe('saveColourInTurn', () => {
  it('saves one choice, reports it and records it on the line', async () => {
    const queue = createColourSaveQueue();
    const onSaved = vi.fn();
    const save = vi.fn().mockResolvedValue({ ok: 1 });
    await expect(saveColourInTurn(queue, 'viridis', { save, onSaved })).resolves.toBe('saved');
    expect(save).toHaveBeenCalledWith('viridis');
    expect(onSaved).toHaveBeenCalledWith({ ok: 1 }, 'viridis');
    expect(queue).toMatchObject({ landed: 1, saved: 'viridis', pending: null, outstanding: 0 });
  });

  it('sends only the newest of several queued choices and supersedes the rest', async () => {
    const queue = createColourSaveQueue();
    let release;
    const save = vi.fn((scale) => (scale === 'a'
      ? new Promise((resolve) => { release = () => resolve({}); })
      : Promise.resolve({})));
    const first = saveColourInTurn(queue, 'a', { save });
    const second = saveColourInTurn(queue, 'b', { save });
    const third = saveColourInTurn(queue, 'c', { save });
    expect(queue.pending).toBe('c');
    release();
    await expect(first).resolves.toBe('saved');
    await expect(second).resolves.toBe('superseded');
    await expect(third).resolves.toBe('saved');
    expect(save.mock.calls.map((c) => c[0])).toEqual(['a', 'c']);
    expect(queue.saved).toBe('c');
    expect(queue.pending).toBeNull();
  });

  it('says failed when the save rejects, and does not report or count it', async () => {
    const queue = createColourSaveQueue();
    const onSaved = vi.fn();
    await expect(saveColourInTurn(queue, 'a', { save: vi.fn().mockRejectedValue(new Error('x')), onSaved }))
      .resolves.toBe('failed');
    expect(onSaved).not.toHaveBeenCalled();
    expect(queue.landed).toBe(0);
  });

  it('sends nothing once the line has ended', async () => {
    const queue = createColourSaveQueue();
    const end = keepColourSaveLineOpen(queue);
    end();
    const save = vi.fn();
    await expect(saveColourInTurn(queue, 'a', { save })).resolves.toBe('ended');
    expect(save).not.toHaveBeenCalled();
  });

  it('reports nothing when the line ends while the save is out', async () => {
    const queue = createColourSaveQueue();
    keepColourSaveLineOpen(queue);
    let finish;
    const save = vi.fn(() => new Promise((resolve) => { finish = resolve; }));
    const onSaved = vi.fn();
    const run = saveColourInTurn(queue, 'a', { save, onSaved });
    queue.ended = true;
    finish({});
    await expect(run).resolves.toBe('ended');
    expect(onSaved).not.toHaveBeenCalled();
    expect(queue.landed).toBe(0);
  });
});

describe('colourAfterRead', () => {
  it('prefers a choice still in the line, then a save that landed after the read was asked', () => {
    const queue = createColourSaveQueue();
    expect(colourAfterRead(queue, 0)).toBeNull();
    queue.landed = 2;
    queue.saved = 'b';
    expect(colourAfterRead(queue, 1)).toBe('b');
    expect(colourAfterRead(queue, 2)).toBeNull();
    queue.pending = 'c';
    expect(colourAfterRead(queue, 2)).toBe('c');
  });
});

describe('keepColourSaveLineOpen', () => {
  it('reopens an ended line and returns the function that ends it', () => {
    const queue = createColourSaveQueue();
    queue.ended = true;
    const end = keepColourSaveLineOpen(queue);
    expect(queue.ended).toBe(false);
    end();
    expect(queue.ended).toBe(true);
  });
});
