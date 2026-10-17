import { describe, it, expect, vi } from 'vitest';
import {
  colourAfterRead, createColourSaveQueue, keepColourSaveLineOpen, saveColourInTurn,
} from '../utils/colourSaveQueue.js';

/**
 * `utils/colourSaveQueue.js` — the page's one line of map-colour saves.
 *
 * <p>The line exists because the colour radios stay enabled while a save is out, so choices can
 * overlap, and because the settings dialog's saves outlive the dialog: a line per opening let a
 * closed dialog's waiting choice go out after a reopened dialog's newer one (found by review, and
 * reproduced against the real components). Every rule here is what keeps "the newest choice is the
 * last one written" true.
 */

/** A save the test settles by hand. */
function heldSave() {
  const calls = [];
  const save = vi.fn((scale) => new Promise((resolve, reject) => {
    calls.push({ scale, resolve, reject });
  }));
  return { save, calls };
}

/**
 * Lets a settled save's continuations run — microtasks only, no timer (the suite uses none). The
 * line is a few `then`s deep per turn, so twenty turns of the microtask queue is ample.
 */
async function drain() {
  for (let i = 0; i < 20; i += 1) await Promise.resolve();
}

describe('colourSaveQueue', () => {
  it('starts a save at once, in the same task, when nothing is ahead of it', () => {
    const queue = createColourSaveQueue();
    const { save } = heldSave();

    saveColourInTurn(queue, 'temp', { save });

    expect(save).toHaveBeenCalledTimes(1);
    expect(save).toHaveBeenCalledWith('temp');
  });

  it('holds a later choice until the save ahead of it has landed', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    saveColourInTurn(queue, 'temp', { save });
    saveColourInTurn(queue, 'verdict', { save });
    await drain();

    expect(save, 'nothing goes out beside a save in flight').toHaveBeenCalledTimes(1);

    calls[0].resolve({ mapColourScale: 'temp' });
    await drain();

    expect(save).toHaveBeenCalledTimes(2);
    expect(save).toHaveBeenLastCalledWith('verdict');
  });

  it('⚠️ skips a waiting choice a newer one has overtaken — the NEWEST is the last written', async () => {
    // temp is out; verdict then temp arrive behind it. The first queued (verdict) is not the answer:
    // the reader has since chosen temp again.
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    const first = saveColourInTurn(queue, 'temp', { save });
    const skipped = saveColourInTurn(queue, 'verdict', { save });
    const newest = saveColourInTurn(queue, 'temp', { save });

    calls[0].resolve({});
    await drain();
    calls[1].resolve({});

    expect(await first).toBe('saved');
    expect(await skipped).toBe('superseded');
    expect(await newest).toBe('saved');
    expect(save.mock.calls.map(([scale]) => scale)).toEqual(['temp', 'temp']);
  });

  it('keeps the line moving past a save that fails', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    const first = saveColourInTurn(queue, 'temp', { save });
    const second = saveColourInTurn(queue, 'verdict', { save });

    calls[0].reject(new Error('502'));
    await drain();
    calls[1].resolve({});

    expect(await first).toBe('failed');
    expect(await second).toBe('saved');
  });

  it('keeps the line moving past a reporter that throws', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    const first = saveColourInTurn(queue, 'temp', {
      save, onSaved: () => { throw new Error('reporter bug'); },
    });
    const second = saveColourInTurn(queue, 'verdict', { save });

    calls[0].resolve({});
    await expect(first).rejects.toThrow('reporter bug');
    await drain();
    calls[1].resolve({});

    expect(await second).toBe('saved');
  });

  it('reports each landed save with its response and its own scale, in the order chosen', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    const onSaved = vi.fn();
    saveColourInTurn(queue, 'temp', { save, onSaved });
    saveColourInTurn(queue, 'verdict', { save, onSaved });

    calls[0].resolve({ mapColourScale: 'temp' });
    await drain();
    calls[1].resolve({});
    await drain();

    expect(onSaved.mock.calls).toEqual([[{ mapColourScale: 'temp' }, 'temp'], [{}, 'verdict']]);
  });

  it('does not report a failed save, or a skipped one', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    const onSaved = vi.fn();
    saveColourInTurn(queue, 'temp', { save, onSaved });
    saveColourInTurn(queue, 'verdict', { save, onSaved });
    saveColourInTurn(queue, 'temp', { save, onSaved });

    calls[0].reject(new Error('502'));
    await drain();
    calls[1].reject(new Error('502'));
    await drain();

    expect(onSaved).not.toHaveBeenCalled();
  });

  it('names the newest unfinished choice as pending, until that choice has landed', async () => {
    // A dialog reopened while a choice from an earlier opening is still in the line shows it.
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    expect(queue.pending).toBeNull();
    saveColourInTurn(queue, 'temp', { save });
    saveColourInTurn(queue, 'verdict', { save });
    expect(queue.pending).toBe('verdict');

    calls[0].resolve({});
    await drain();
    expect(queue.pending, 'the older save landing does not finish the newest').toBe('verdict');

    calls[1].resolve({});
    await drain();
    expect(queue.pending).toBeNull();
  });

  it('clears pending when the newest choice fails, so a reopened dialog shows the server again', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    saveColourInTurn(queue, 'verdict', { save });

    calls[0].reject(new Error('502'));
    await drain();

    expect(queue.pending).toBeNull();
  });

  it('is idle again once everything has landed — the next choice goes out at once', async () => {
    // "Idle" is what lets a save start in the same task. A line that never became idle again would
    // hold every later choice a microtask back — and one that never reset would never send at all.
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    saveColourInTurn(queue, 'temp', { save });
    calls[0].resolve({});
    await drain();

    saveColourInTurn(queue, 'verdict', { save });

    expect(save).toHaveBeenCalledTimes(2);
    expect(save).toHaveBeenLastCalledWith('verdict');
  });

  it('names the newest choice\'s turn, so an opening that did not make the choice can follow it', () => {
    const queue = createColourSaveQueue();
    const { save } = heldSave();
    expect(queue.newest).toBeNull();

    const first = saveColourInTurn(queue, 'temp', { save });
    expect(queue.newest).toBe(first);
    const second = saveColourInTurn(queue, 'verdict', { save });
    expect(queue.newest).toBe(second);
  });

  it('counts the saves that land and keeps the newest one\'s scale — a failed or skipped turn changes neither', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    expect([queue.landed, queue.saved]).toEqual([0, null]);
    saveColourInTurn(queue, 'temp', { save });
    calls[0].resolve({});
    await drain();
    expect([queue.landed, queue.saved]).toEqual([1, 'temp']);

    saveColourInTurn(queue, 'verdict', { save });
    saveColourInTurn(queue, 'temp', { save }); // overtaken by the next before its turn: skipped
    saveColourInTurn(queue, 'temp', { save });
    calls[1].resolve({});
    await drain();
    expect([queue.landed, queue.saved]).toEqual([2, 'verdict']);

    calls[2].reject(new Error('502'));
    await drain();
    expect([queue.landed, queue.saved], 'the failed temp, like the skipped one, counts for nothing')
      .toEqual([2, 'verdict']);
  });

  it('counts a landed save even when its reporter throws — the server holds it all the same', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    const run = saveColourInTurn(queue, 'temp', { save, onSaved: () => { throw new Error('reporter bug'); } });

    calls[0].resolve({});
    await expect(run).rejects.toThrow('reporter bug');

    expect([queue.landed, queue.saved]).toEqual([1, 'temp']);
  });
});

describe('colourSaveQueue — the line ends with its owner', () => {
  // From review (Codex, on #859): `App` unmounts when the reader signs out, and a choice still
  // waiting in its line went out when its turn came — under whichever token was stored by then.

  it('⚠️ sends no waiting choice once the line has ended', async () => {
    const queue = createColourSaveQueue();
    const end = keepColourSaveLineOpen(queue);
    const { save, calls } = heldSave();
    const first = saveColourInTurn(queue, 'temp', { save });
    const waiting = saveColourInTurn(queue, 'verdict', { save });

    end(); // the owner unmounts: the reader signed out
    calls[0].resolve({});

    expect(await first).toBe('ended');
    expect(await waiting).toBe('ended');
    expect(save.mock.calls.map(([scale]) => scale), 'only the save already out').toEqual(['temp']);
  });

  it('reports nothing from a save already out when the line ended — the page it belonged to is gone', async () => {
    const queue = createColourSaveQueue();
    const end = keepColourSaveLineOpen(queue);
    const { save, calls } = heldSave();
    const onSaved = vi.fn();
    saveColourInTurn(queue, 'temp', { save, onSaved });

    end();
    calls[0].resolve({ mapColourScale: 'temp' });
    await drain();

    expect(onSaved).not.toHaveBeenCalled();
    expect([queue.landed, queue.saved], 'nor is it counted for a later read').toEqual([0, null]);
  });

  it('sends nothing chosen on an ended line, even when it is idle', async () => {
    const queue = createColourSaveQueue();
    keepColourSaveLineOpen(queue)();
    const { save } = heldSave();

    expect(await saveColourInTurn(queue, 'temp', { save })).toBe('ended');
    expect(save).not.toHaveBeenCalled();
  });

  it('opens again when held open again — StrictMode re-runs an owner\'s effect on a page that stays', async () => {
    const queue = createColourSaveQueue();
    keepColourSaveLineOpen(queue)(); // the development re-run's cleanup …
    keepColourSaveLineOpen(queue); // … and its setup, straight after
    const { save, calls } = heldSave();
    const onSaved = vi.fn();
    const run = saveColourInTurn(queue, 'temp', { save, onSaved });

    calls[0].resolve({});

    expect(await run).toBe('saved');
    expect(onSaved).toHaveBeenCalledTimes(1);
  });
});

describe('colourAfterRead — what a settings read shows of the line', () => {
  it('leaves the read to answer for itself when nothing has been chosen', () => {
    expect(colourAfterRead(createColourSaveQueue(), 0)).toBeNull();
  });

  it('answers with the newest choice still in the line — what the server is about to hold', () => {
    const queue = createColourSaveQueue();
    const { save } = heldSave();
    saveColourInTurn(queue, 'temp', { save });
    saveColourInTurn(queue, 'verdict', { save });

    expect(colourAfterRead(queue, 0)).toBe('verdict');
  });

  it('answers with a save that landed after the read was asked — the server may have read before it', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    const landedWhenAsked = queue.landed;
    saveColourInTurn(queue, 'temp', { save });
    calls[0].resolve({});
    await drain();

    expect(colourAfterRead(queue, landedWhenAsked)).toBe('temp');
  });

  it('leaves a read asked after the newest landing to answer for itself — it is the newer word', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    saveColourInTurn(queue, 'temp', { save });
    calls[0].resolve({});
    await drain();

    expect(colourAfterRead(queue, queue.landed)).toBeNull();
  });

  it('prefers a choice still in the line over one that landed during the read', async () => {
    const queue = createColourSaveQueue();
    const { save, calls } = heldSave();
    saveColourInTurn(queue, 'temp', { save });
    saveColourInTurn(queue, 'verdict', { save });
    calls[0].resolve({});
    await drain();

    expect(colourAfterRead(queue, 0), 'temp landed, but verdict is still to be written').toBe('verdict');
  });
});
