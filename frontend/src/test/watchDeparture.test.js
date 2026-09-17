/**
 * `watchDeparture` — the ref-cleanup record that `MastheadTickLine` and `MapBreadcrumb` spend.
 *
 * <p>Tested directly as well as through its owners. It has no single natural consumer, and until
 * this file its re-attach guard was pinned only by the tick line's StrictMode tests, so an owner that
 * stopped using the helper would take the guard's only pin with it. The owners' tests prove React
 * calls it in the order these rules assume; this file proves the rules.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { watchDeparture } from '../utils/watchDeparture.js';

const added = [];
function button(name) {
  const node = document.createElement('button');
  node.textContent = name;
  document.body.appendChild(node);
  added.push(node);
  return node;
}
afterEach(() => {
  added.splice(0).forEach((node) => node.remove());
});

describe('watchDeparture — the record its cleanup makes', () => {
  it('records the node when it holds focus as its ref is detached', () => {
    const departed = { current: null };
    const node = button('clear');
    const detach = watchDeparture(departed, node);
    node.focus();

    detach();

    expect(departed.current).toBe(node);
  });

  it('records nothing for a node that does not hold focus as its ref is detached', () => {
    const departed = { current: null };
    const node = button('clear');
    const detach = watchDeparture(departed, node);
    button('elsewhere').focus();

    detach();

    expect(departed.current).toBeNull();
  });

  it('leaves the record another node made standing when a node without focus is detached', () => {
    // One owner watches several nodes (the tick line: the slot's element and ⌂), and one commit can
    // detach a focused one and an unfocused one in either order.
    const departed = { current: null };
    const left = button('origin');
    const detachLeft = watchDeparture(departed, left);
    const detachHome = watchDeparture(departed, button('⌂'));
    left.focus();

    detachLeft();
    detachHome();

    expect(departed.current).toBe(left);
  });
});

describe('watchDeparture — a node attaching again cannot also have departed', () => {
  it.each([
    // StrictMode re-runs a newly mounted node's ref, cleanup first, and the node keeps its focus.
    ['still holding focus, as StrictMode\'s re-run leaves it', false],
    // An `<Activity mode="hidden">` or a re-suspending `<Suspense>` detaches its subtree's refs
    // first; a browser takes focus off the hidden node before the subtree is shown again.
    ['after losing focus while its ref was detached', true],
  ])('clears the record the node left — %s', (_label, blurred) => {
    const departed = { current: null };
    const node = button('clear');
    const detach = watchDeparture(departed, node);
    node.focus();
    detach();
    expect(departed.current, 'precondition: the detach recorded it').toBe(node);
    if (blurred) node.blur();

    watchDeparture(departed, node);

    expect(departed.current).toBeNull();
  });

  it('leaves the record another node made standing when a different node attaches', () => {
    // A slot that swaps one element for another attaches the new one in the commit the old one left
    // in, before the owner's layout effect spends the old one's record.
    const departed = { current: null };
    const left = button('origin');
    const detach = watchDeparture(departed, left);
    left.focus();
    detach();

    watchDeparture(departed, button('statement'));

    expect(departed.current).toBe(left);
  });
});
