import { describe, it, expect, afterEach } from 'vitest';
import { lazyReturnTo, resolveReturnTo } from '../utils/focusReturn.js';

/** Where focus goes back to after an Undo, for a control that may have moved while the request was in flight. */

const row = (attribute, value, testId) => {
  const wrapper = document.createElement('div');
  wrapper.setAttribute(attribute, value);
  const button = document.createElement('button');
  button.setAttribute('data-testid', testId);
  wrapper.appendChild(button);
  document.body.appendChild(wrapper);
  return { wrapper, button };
};

afterEach(() => { document.body.innerHTML = ''; });

describe('lazyReturnTo', () => {
  it('has no target when nothing, or only the page, had focus', () => {
    expect(lazyReturnTo(null)).toBeNull();
    expect(lazyReturnTo(document.body)).toBeNull();
  });

  it('answers the element itself while it is still in the page', () => {
    const { button } = row('data-entry-id', 'a', 'toggle');
    expect(lazyReturnTo(button)()).toBe(button);
  });

  it('finds the control again by its entry id and test id once its card has moved lists', () => {
    const { wrapper, button } = row('data-entry-id', 'king-tide:2026-12-03:2026-12-07', 'coming-up-watch-toggle');
    const target = lazyReturnTo(button);
    wrapper.remove();
    const moved = row('data-entry-id', 'king-tide:2026-12-03:2026-12-07', 'coming-up-watch-toggle');
    expect(target()).toBe(moved.button);
  });

  it('finds an unlisted Watching row\'s button by its watch key', () => {
    const { wrapper, button } = row('data-watch-key', 'meteor:2026-12-14', 'watching-unlisted-stop');
    const target = lazyReturnTo(button);
    wrapper.remove();
    expect(target()).toBeNull();
    const back = row('data-watch-key', 'meteor:2026-12-14', 'watching-unlisted-stop');
    expect(target()).toBe(back.button);
  });

  it('falls back to the marked heading when its row has left the page altogether (a date that became a lane)', () => {
    const { wrapper, button } = row('data-entry-id', 'king-tide:run', 'coming-up-watch-toggle');
    const heading = document.createElement('h2');
    heading.setAttribute('data-focus-fallback', 'further-ahead');
    document.body.appendChild(heading);
    const target = lazyReturnTo(button);
    wrapper.remove();
    expect(target()).toBe(heading);
    // A control with no row anchor has no such successor.
    const bare = document.createElement('button');
    document.body.appendChild(bare);
    const bareTarget = lazyReturnTo(bare);
    bare.remove();
    expect(bareTarget()).toBeNull();
  });

  it('does not borrow another entry\'s control, and a control with no row or test id has nothing to fall back on', () => {
    const { wrapper, button } = row('data-entry-id', 'a', 'toggle');
    const target = lazyReturnTo(button);
    wrapper.remove();
    row('data-entry-id', 'b', 'toggle');
    expect(target()).toBeNull();
    const bare = document.createElement('button');
    document.body.appendChild(bare);
    const bareTarget = lazyReturnTo(bare);
    bare.remove();
    expect(bareTarget()).toBeNull();
  });
});

describe('resolveReturnTo', () => {
  it('takes an element or a function, and answers only something that is in the page', () => {
    const live = document.createElement('button');
    document.body.appendChild(live);
    expect(resolveReturnTo(live)).toBe(live);
    expect(resolveReturnTo(() => live)).toBe(live);
    const gone = document.createElement('button');
    expect(resolveReturnTo(gone)).toBeNull();
    expect(resolveReturnTo(() => gone)).toBeNull();
    expect(resolveReturnTo(null)).toBeNull();
    expect(resolveReturnTo(undefined)).toBeNull();
  });
});
