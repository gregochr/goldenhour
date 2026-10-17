import { describe, it, expect, afterEach } from 'vitest';
import { foreignDialogOpen } from '../utils/shellForeignDialog.js';

/**
 * The one "is a dialog outside the shell open" test behind the `/` key, Ask's sheet opener and the
 * dock's opener. Containment, not names: every dialog the shell owns is a descendant of its root.
 */

const added = [];
function dialogIn(parent) {
  const node = document.createElement('div');
  node.setAttribute('role', 'dialog');
  parent.appendChild(node);
  added.push(node);
  return node;
}

function root() {
  const node = document.createElement('div');
  document.body.appendChild(node);
  added.push(node);
  return node;
}

afterEach(() => {
  while (added.length) added.pop().remove();
});

describe('foreignDialogOpen', () => {
  it('is false when nothing is a dialog at all', () => {
    expect(foreignDialogOpen(root())).toBe(false);
  });

  it('is false for a dialog INSIDE the shell root: the popup and its layers are the shell\'s own', () => {
    const shell = root();
    dialogIn(shell);
    expect(foreignDialogOpen(shell)).toBe(false);
  });

  it('is true for a dialog outside it — the settings modal, the map overlay, Ask\'s portalled sheet', () => {
    const shell = root();
    dialogIn(document.body);
    expect(foreignDialogOpen(shell)).toBe(true);
  });

  it('is true when ANY one is outside, even beside one that is inside', () => {
    const shell = root();
    dialogIn(shell);
    dialogIn(document.body);
    expect(foreignDialogOpen(shell)).toBe(true);
  });

  it('with no root, counts every dialog as foreign: not finding your own root is not evidence of nothing', () => {
    expect(foreignDialogOpen(null)).toBe(false);
    dialogIn(document.body);
    expect(foreignDialogOpen(null)).toBe(true);
    expect(foreignDialogOpen(undefined)).toBe(true);
  });

  it('only counts role="dialog" — a landmark, such as Ask\'s dock, is not one', () => {
    const shell = root();
    const aside = document.createElement('aside');
    aside.setAttribute('aria-label', 'Ask PhotoCast');
    document.body.appendChild(aside);
    added.push(aside);
    expect(foreignDialogOpen(shell)).toBe(false);
  });
});

describe('foreignDialogOpen — the one dialog a caller is about to close itself (F5)', () => {
  const named = (parent, label) => {
    const node = dialogIn(parent);
    node.setAttribute('aria-label', label);
    return node;
  };
  const isAsk = (node) => node.getAttribute('aria-label') === 'Ask PhotoCast';

  it('does not count the dialog the predicate names', () => {
    const shell = root();
    named(document.body, 'Ask PhotoCast');

    expect(foreignDialogOpen(shell)).toBe(true);
    expect(foreignDialogOpen(shell, isAsk)).toBe(false);
  });

  it('still counts every OTHER dialog outside the root, beside the ignored one', () => {
    const shell = root();
    named(document.body, 'Ask PhotoCast');
    named(document.body, 'Settings');

    expect(foreignDialogOpen(shell, isAsk)).toBe(true);
  });

  it('never ignores a dialog INSIDE the root (they never counted) or changes the no-root rule', () => {
    const shell = root();
    named(shell, 'Window');

    expect(foreignDialogOpen(shell, () => true)).toBe(false);
    named(document.body, 'Elsewhere');
    expect(foreignDialogOpen(null, isAsk)).toBe(true);
  });

  it('an absent predicate is the old behaviour exactly', () => {
    const shell = root();
    named(document.body, 'Ask PhotoCast');

    expect(foreignDialogOpen(shell, undefined)).toBe(foreignDialogOpen(shell));
  });
});
