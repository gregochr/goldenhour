import { describe, it, expect, afterEach } from 'vitest';
import { foreignModalOverPaneOf, mutationsMayChangeForeignModal } from '../utils/mapForeignModal.js';

afterEach(() => {
  document.body.innerHTML = '';
});

const plain = () => document.createElement('div');
const dialog = () => {
  const node = document.createElement('div');
  node.setAttribute('role', 'dialog');
  return node;
};
const holdsDialog = () => {
  const node = document.createElement('section');
  node.appendChild(dialog());
  return node;
};

describe('foreignModalOverPaneOf — a node that cannot look for its pane', () => {
  it('stands down for a text node (no closest method) while a modal is open', () => {
    const modal = dialog();
    modal.setAttribute('aria-modal', 'true');
    document.body.appendChild(modal);
    const text = document.createTextNode('hello');
    document.body.appendChild(text);
    expect(foreignModalOverPaneOf(text)).toBe(true);
  });

  it('is false for a text node when no modal is open at all', () => {
    const text = document.createTextNode('hello');
    document.body.appendChild(text);
    expect(foreignModalOverPaneOf(text)).toBe(false);
  });
});

describe('mutationsMayChangeForeignModal', () => {
  it('counts an empty or missing batch', () => {
    expect(mutationsMayChangeForeignModal([])).toBe(true);
    expect(mutationsMayChangeForeignModal(null)).toBe(true);
    expect(mutationsMayChangeForeignModal(undefined)).toBe(true);
  });

  it('counts a record kind it does not understand, since it cannot rule it out', () => {
    expect(mutationsMayChangeForeignModal([{ type: 'characterData', target: plain() }])).toBe(true);
  });

  it('counts only an aria-modal attribute change among attribute records', () => {
    expect(mutationsMayChangeForeignModal([
      { type: 'attributes', attributeName: 'aria-modal', target: plain() },
    ])).toBe(true);
    expect(mutationsMayChangeForeignModal([
      { type: 'attributes', attributeName: 'class', target: plain() },
    ])).toBe(false);
  });

  it('counts a child that is, or holds, a dialog that was added', () => {
    expect(mutationsMayChangeForeignModal([
      { type: 'childList', target: plain(), addedNodes: [dialog()], removedNodes: [] },
    ])).toBe(true);
    expect(mutationsMayChangeForeignModal([
      { type: 'childList', target: plain(), addedNodes: [holdsDialog()], removedNodes: [] },
    ])).toBe(true);
  });

  it('counts a record with no target at all', () => {
    expect(mutationsMayChangeForeignModal([
      { type: 'childList', addedNodes: [dialog()], removedNodes: [] },
    ])).toBe(true);
  });

  it('counts a batch where only ONE of several added nodes is a dialog', () => {
    expect(mutationsMayChangeForeignModal([
      { type: 'childList', target: plain(), addedNodes: [plain(), dialog(), plain()], removedNodes: [] },
    ])).toBe(true);
  });

  it('counts a batch where only ONE of several removed nodes is a dialog', () => {
    expect(mutationsMayChangeForeignModal([
      { type: 'childList', target: plain(), addedNodes: [], removedNodes: [plain(), holdsDialog()] },
    ])).toBe(true);
  });

  it('ignores child churn that carries no dialog, and text nodes', () => {
    expect(mutationsMayChangeForeignModal([
      {
        type: 'childList',
        target: plain(),
        addedNodes: [plain(), document.createTextNode('x')],
        removedNodes: [plain()],
      },
    ])).toBe(false);
  });

  it('ignores a dialog added inside Leaflet\'s own panes and tile containers', () => {
    const pane = plain();
    pane.className = 'leaflet-pane';
    const tiles = plain();
    tiles.className = 'leaflet-tile-container';
    const inner = plain();
    tiles.appendChild(inner);
    document.body.append(pane, tiles);
    expect(mutationsMayChangeForeignModal([
      { type: 'childList', target: pane, addedNodes: [dialog()], removedNodes: [] },
    ])).toBe(false);
    expect(mutationsMayChangeForeignModal([
      { type: 'attributes', attributeName: 'aria-modal', target: inner },
    ])).toBe(false);
  });

  it('counts the batch when any one record counts, even after an ignorable one', () => {
    const pane = plain();
    pane.className = 'leaflet-pane';
    document.body.appendChild(pane);
    expect(mutationsMayChangeForeignModal([
      { type: 'childList', target: pane, addedNodes: [plain()], removedNodes: [] },
      { type: 'childList', target: plain(), addedNodes: [dialog()], removedNodes: [] },
    ])).toBe(true);
  });
});
