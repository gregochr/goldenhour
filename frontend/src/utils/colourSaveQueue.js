/**
 * One line of map-colour saves for the whole page.
 *
 * <p><b>Why a line, and why it outlives the settings dialog.</b> The colour radios stay enabled
 * while a save is out — a radio in a fieldset disabled mid-save loses the focus of a reader arrowing
 * between the two scales — so a second choice can arrive before the first save has landed. Two
 * requests in flight can commit in either order, and the server could end on the scale the reader
 * moved away from. A line per dialog was not enough, and a review reproduced it: the dialog unmounts
 * on close but its saves do not stop, so a choice still waiting in a closed dialog's line went out
 * AFTER a newer choice made in the reopened dialog, and the server and the map ended on the older
 * scale. So `App` owns one line for the page and hands it to every opening of the dialog.
 *
 * <p><b>The rules.</b> Saves go out one at a time, in the order the choices were made. When a save's
 * turn comes, it is skipped if a newer choice has been queued behind it — that one says what the
 * reader wants now — so the newest choice is always the last one written. A save that fails does
 * not stop the ones behind it. A save made while the line is idle starts at once, in the same task
 * as the choice.
 *
 * <p><b>What an opening reads off the line.</b> A dialog reopened while an earlier opening's choice
 * is still saving would otherwise show the server's answer to its own read — older than that choice
 * — and say nothing about how the choice goes. So the line keeps:
 * <ul>
 *   <li>{@code pending} — the newest choice that has not finished, or null. What the server is
 *       about to hold, so it outranks a read's answer (see {@link colourAfterRead}).</li>
 *   <li>{@code newest} — that choice's turn, the promise {@link saveColourInTurn} returned for it,
 *       so an opening that did not make the choice can still say "Saving…" and show its failure.</li>
 *   <li>{@code landed} and {@code saved} — how many saves have landed, and the newest one's scale.
 *       A save that lands while a read is out outranks the read's answer, which the server may have
 *       taken before the save committed: the order rule `useReaderSettings` applies to the page.</li>
 * </ul>
 *
 * @returns {{tail: Promise<void>, latest: number, outstanding: number, pending: ?string,
 *           newest: ?Promise<string>, landed: number, saved: ?string}}
 */
export function createColourSaveQueue() {
  return {
    tail: Promise.resolve(), latest: 0, outstanding: 0, pending: null, newest: null, landed: 0, saved: null,
  };
}

/**
 * The scale a settings read should show: the newest choice still in the line, else a save that
 * landed after the read was asked, else null — the read's own answer stands.
 *
 * @param {object} queue a line from {@link createColourSaveQueue}
 * @param {number} landedWhenAsked the line's {@code landed} when the read was asked
 * @returns {?string}
 */
export function colourAfterRead(queue, landedWhenAsked) {
  if (queue.pending != null) return queue.pending;
  return queue.landed > landedWhenAsked ? queue.saved : null;
}

/**
 * Queues one choice on the line, and says how its turn ended.
 *
 * @param {object} queue a line from {@link createColourSaveQueue}
 * @param {string} scale the scale the reader chose
 * @param {object} handlers
 * @param {function(string): Promise<object>} handlers.save sends the choice; resolves with the saved
 *        settings
 * @param {function(?object, string)} [handlers.onSaved] called with the save's response and the
 *        scale, when a save lands — after the dialog has closed, too
 * @returns {Promise<'saved'|'failed'|'superseded'>} settles when this choice's turn is over
 */
export function saveColourInTurn(queue, scale, { save, onSaved }) {
  queue.latest += 1;
  const turn = queue.latest;
  queue.pending = scale;

  const attempt = async () => {
    try {
      if (turn !== queue.latest) return 'superseded';
      let updated;
      try {
        updated = await save(scale);
      } catch {
        return 'failed';
      }
      // Before the reporter, so one that throws cannot leave the line believing nothing landed.
      queue.landed += 1;
      queue.saved = scale;
      onSaved?.(updated, scale);
      return 'saved';
    } finally {
      queue.outstanding -= 1;
      if (turn === queue.latest) queue.pending = null;
    }
  };

  const idle = queue.outstanding === 0;
  queue.outstanding += 1;
  const run = idle ? attempt() : queue.tail.then(attempt);
  queue.newest = run;
  // The line keeps moving whatever a turn does — even a reporter that throws.
  queue.tail = run.then(() => {}, () => {});
  return run;
}
