import { describe, it, expect } from 'vitest';
import { mutationsMayChangeForeignModal } from '../utils/mapForeignModal.js';

const el = (cls, role) => {
  const n = document.createElement('div');
  if (cls) n.className = cls;
  if (role) n.setAttribute('role', role);
  return n;
};
const childList = (target, added = [], removed = []) => ({
  type: 'childList', target, addedNodes: added, removedNodes: removed,
});

describe('mutationsMayChangeForeignModal (performance review F22)', () => {
  it('treats an empty batch as relevant: it cannot be judged', () => {
    expect(mutationsMayChangeForeignModal([])).toBe(true);
  });

  it('ignores child-list churn under Leaflet panes and tile containers', () => {
    const pane = el('leaflet-pane');
    const tiles = el('leaflet-tile-container');
    pane.appendChild(tiles);
    expect(mutationsMayChangeForeignModal([childList(tiles, [el()])])).toBe(false);
    expect(mutationsMayChangeForeignModal([childList(pane, [el()])])).toBe(false);
    // The target check, not the node check, is what ignores these: even a dialog-role node added
    // under Leaflet's own subtree is never a foreign modal.
    expect(mutationsMayChangeForeignModal([childList(tiles, [el('', 'dialog')])])).toBe(false);
  });

  it('ignores plain child-list changes that carry no dialog', () => {
    expect(mutationsMayChangeForeignModal([childList(document.body, [el('x')], [el('y')])])).toBe(false);
  });

  it('sees an added or removed dialog, or a node containing one', () => {
    const wrapper = el('w');
    wrapper.appendChild(el('', 'dialog'));
    expect(mutationsMayChangeForeignModal([childList(document.body, [el('', 'dialog')])])).toBe(true);
    expect(mutationsMayChangeForeignModal([childList(document.body, [], [el('', 'dialog')])])).toBe(true);
    expect(mutationsMayChangeForeignModal([childList(document.body, [wrapper])])).toBe(true);
  });

  it('sees an aria-modal attribute change, and nothing else', () => {
    const target = el('', 'dialog');
    expect(mutationsMayChangeForeignModal([
      { type: 'attributes', target, attributeName: 'aria-modal' },
    ])).toBe(true);
    expect(mutationsMayChangeForeignModal([
      { type: 'attributes', target, attributeName: 'class' },
    ])).toBe(false);
  });

  it('lets one relevant record in a noisy batch through', () => {
    const tiles = el('leaflet-tile-container');
    expect(mutationsMayChangeForeignModal([
      childList(tiles, [el()]),
      childList(document.body, [el('', 'dialog')]),
    ])).toBe(true);
  });
});
