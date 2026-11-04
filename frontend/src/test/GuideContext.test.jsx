import React, { useEffect, useState } from 'react';
import {
  describe, it, expect, vi, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen,
} from '@testing-library/react';
import { GuideProvider, useGuide } from '../context/GuideContext.jsx';
import GuideDock from '../components/guide/GuideDock.jsx';
import { GUIDE_ORIGIN } from '../utils/guide.js';
import { installViewport, resetViewport } from './askViewport.js';

/**
 * The guide's state machine and its three presentations (docs/engineering/user-guide-plan.md, G2),
 * driven through a small harness: doors that call {@code openGuide}, a stack of fake modal layers the
 * two-deep rule can count and a {@code closeShellDialogs} that removes some of them. The shell's own
 * wiring (the masthead door, the dock exclusivity, the Settings protocol) is in {@code GuideShell.test.jsx}.
 *
 * <p>jsdom has no layout and no {@code inert}; what is asserted is the DOM the code builds and the
 * behaviour it has, never a pixel.
 */

function Harness({ initialLayers = 0, closes = 'all', tab = 'plan', probe }) {
  const [layers, setLayers] = useState(initialLayers);
  // One spy for the life of the mount: the provider holds the newest closer, and a spy rebuilt on each
  // render (the layers change on a close) would hide the call it was made for.
  const [spy] = useState(() => vi.fn());
  const closeShellDialogs = () => {
    spy();
    if (closes === 'all') setLayers(0);
    else if (closes === 'one') setLayers((n) => Math.max(0, n - 1));
  };
  useEffect(() => { probe.set('closeShellDialogs', spy); });
  return (
    <GuideProvider closeShellDialogs={closeShellDialogs} activeTab={tab}>
      <Doors probe={probe} />
      {/* The shell mounts this in the Ask dock's slot; here it stands in for that slot. */}
      <GuideDock band="wide" sticky inert={false} />
      {Array.from({ length: layers }, (_, i) => (
        <div key={i} role="dialog" aria-modal="true" aria-label={`Layer ${i}`} data-testid={`layer-${i}`}>
          <button type="button" data-testid={`layer-${i}-button`}>in layer</button>
        </div>
      ))}
    </GuideProvider>
  );
}

function Doors({ probe }) {
  const guide = useGuide();
  useEffect(() => { probe.set('guide', guide); });
  return (
    <>
      <button type="button" data-testid="door-map" onClick={() => guide.openGuide('map')}>map</button>
      <button type="button" data-testid="door-settings" onClick={() => guide.openGuide('settings', 'drive')}>s</button>
      <button
        type="button"
        data-testid="door-bogus"
        onClick={() => guide.openGuide('nope')}
      >
        bogus
      </button>
      <button type="button" data-testid="other-focus">other</button>
      <output data-testid="modal-flag">{String(guide.guideModal)}</output>
    </>
  );
}

/** Where the harness reports what it saw; `set` is a call, which a render may make on a prop. */
const makeProbe = () => {
  const probe = { set: (key, value) => { probe[key] = value; } };
  return probe;
};

const mount = (props = {}, width = 1280) => {
  const viewport = installViewport(width);
  const probe = makeProbe();
  const view = render(<Harness probe={probe} {...props} />);
  return {
    ...view,
    viewport,
    probe,
    rerenderWith: (next) => view.rerender(<Harness probe={probe} {...props} {...next} />),
  };
};

const press = (id) => act(() => { fireEvent.click(screen.getByTestId(id)); });
const frame = () => screen.getByTestId('guide-frame');

/** Delivers a message to the window as the browser would, from a chosen origin and source. */
const post = (data, { origin = GUIDE_ORIGIN, source = frame().contentWindow } = {}) => act(() => {
  window.dispatchEvent(new MessageEvent('message', { data, origin, source }));
});
const report = (overrides = {}) => ({
  type: 'photocast-guide', page: 'map.html', title: 'Map.', hash: '#filters', ...overrides,
});

afterEach(() => {
  cleanup();
  resetViewport();
});

describe('presentation, chosen at press time', () => {
  it('docks on a desktop with no modal layer: an aside that is not a dialog', () => {
    mount();
    press('door-map');
    const dock = screen.getByTestId('guide-dock');
    expect(dock.tagName).toBe('ASIDE');
    expect(dock).toHaveAttribute('aria-label', 'PhotoCast guide');
    expect(dock).toHaveAttribute('data-guide-surface');
    expect(dock).not.toHaveAttribute('role');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('modal-flag')).toHaveTextContent('false');
  });

  it('is a stacked dialog on a desktop while one modal layer is up', () => {
    mount({ initialLayers: 1 });
    press('door-map');
    const dialog = screen.getByRole('dialog', { name: 'PhotoCast guide' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAttribute('data-guide-surface');
    expect(dialog.parentElement.parentElement).toBe(document.body);
    expect(screen.queryByTestId('guide-dock')).toBeNull();
    expect(screen.getByTestId('modal-flag')).toHaveTextContent('true');
  });

  it.each([[390, 'phone'], [768, 'tablet'], [1023, 'tablet']])(
    'is the tall sheet at %i px (%s), never docked, with the sheet\'s own close and no second one',
    (width) => {
      mount({}, width);
      press('door-map');
      const sheet = screen.getByTestId('bottom-sheet');
      expect(sheet).toHaveAttribute('aria-label', 'PhotoCast guide');
      expect(sheet).toHaveAttribute('data-size', 'tall');
      expect(screen.queryByTestId('guide-dock')).toBeNull();
      expect(screen.getAllByRole('button', { name: /^Close/ })).toHaveLength(2); // sheet ✕ + the scrim
      expect(screen.queryByTestId('guide-close')).toBeNull();
      expect(screen.getByTestId('guide-title')).toHaveTextContent('Map');
      expect(screen.getByTestId('bottom-sheet-scroller')).toHaveAttribute('data-flush');
    },
  );

  it('ignores a page that is not in GUIDE_PAGES', () => {
    mount();
    press('door-bogus');
    expect(screen.queryByTestId('guide-dock')).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('does not dock on Operations, which has no dock row: it opens as a dialog', () => {
    mount({ tab: 'operations' });
    press('door-settings');
    expect(screen.getByTestId('guide-dialog')).toBeInTheDocument();
  });
});

describe('the two-deep rule', () => {
  it('opens stacked over one layer without touching the shell\'s dialogs', () => {
    const { probe } = mount({ initialLayers: 1 });
    press('door-map');
    expect(probe.closeShellDialogs).not.toHaveBeenCalled();
    expect(screen.getByTestId('guide-dialog')).toBeInTheDocument();
  });

  it('with two layers, closes the shell\'s, counts again and opens docked when none are left', () => {
    const { probe } = mount({ initialLayers: 2, closes: 'all' });
    press('door-map');
    expect(probe.closeShellDialogs).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('guide-dock')).toBeInTheDocument();
  });

  it('with two layers and one left after closing, opens stacked over the survivor', () => {
    mount({ initialLayers: 2, closes: 'one' });
    press('door-map');
    expect(screen.getByTestId('guide-dialog')).toBeInTheDocument();
    expect(screen.getAllByRole('dialog', { name: /Layer/ })).toHaveLength(1);
  });

  it('with two layers still standing (Settings over the Operations modal), does nothing and keeps focus', () => {
    const { probe } = mount({ initialLayers: 2, closes: 'none' });
    screen.getByTestId('other-focus').focus();
    press('door-map');
    expect(probe.closeShellDialogs).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('guide-dialog')).toBeNull();
    expect(screen.queryByTestId('guide-dock')).toBeNull();
    expect(screen.getByTestId('modal-flag')).toHaveTextContent('false');
    expect(document.activeElement).toBe(screen.getByTestId('other-focus'));
  });

  it('does not count its own dialog as a layer when pressed again', () => {
    mount({ initialLayers: 1 });
    press('door-map');
    press('door-settings');
    expect(screen.getAllByTestId('guide-dialog')).toHaveLength(1);
    expect(frame().getAttribute('src')).toContain('/settings.html?embed=1#drive');
  });
});

describe('the frame', () => {
  it('loads the embedded page and section in a sandboxed, titled iframe', () => {
    mount();
    press('door-settings');
    expect(frame()).toHaveAttribute('src', `${GUIDE_ORIGIN}/guide/settings.html?embed=1#drive`);
    expect(frame()).toHaveAttribute('title', 'PhotoCast guide');
    expect(frame()).toHaveAttribute(
      'sandbox',
      'allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox',
    );
    expect(frame()).toHaveAttribute('referrerpolicy', 'strict-origin');
  });

  it('reloads at the right place when the same page is opened again (the nonce keys the iframe)', () => {
    mount({ initialLayers: 1 });
    press('door-map');
    const first = frame();
    press('door-map');
    expect(frame()).not.toBe(first);
    expect(first.isConnected).toBe(false);
  });

  it('opens the header with the title, a plain new-tab link and a close button that has focus', () => {
    mount();
    press('door-map');
    expect(screen.getByTestId('guide-title')).toHaveTextContent('Map');
    const link = screen.getByTestId('guide-new-tab');
    expect(link).toHaveAttribute('href', `${GUIDE_ORIGIN}/guide/map.html`);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(document.activeElement).toBe(screen.getByTestId('guide-close'));
  });
});

describe('the message listener', () => {
  it('takes the title and page from the frame it owns and points "Open in new tab" at them', () => {
    mount();
    press('door-map');
    post(report({ page: 'coming-up.html', title: 'Coming up.', hash: '#tides' }));
    expect(screen.getByTestId('guide-title')).toHaveTextContent('Coming up');
    expect(screen.getByTestId('guide-new-tab')).toHaveAttribute(
      'href', `${GUIDE_ORIGIN}/guide/coming-up.html#tides`,
    );
  });

  it('does not reload the frame when it reports (the src is the opened page, not the tracked one)', () => {
    mount();
    press('door-map');
    const before = frame();
    post(report({ page: 'night-sky.html' }));
    expect(frame()).toBe(before);
    expect(before.getAttribute('src')).toContain('/map.html');
  });

  it('ignores a message from another origin', () => {
    mount();
    press('door-map');
    post(report({ page: 'night-sky.html' }), { origin: 'https://evil.example' });
    expect(screen.getByTestId('guide-title').textContent).toBe('Map');
  });

  it('ignores a message from a window that is not its iframe', () => {
    mount();
    press('door-map');
    post(report({ page: 'night-sky.html' }), { source: window });
    expect(screen.getByTestId('guide-title').textContent).toBe('Map');
  });

  it('ignores a page that is not a guide page, and a payload of the wrong type', () => {
    mount();
    press('door-map');
    post(report({ page: 'admin.html' }));
    post(report({ type: 'something-else', page: 'night-sky.html' }));
    post('photocast-guide');
    post(null);
    expect(screen.getByTestId('guide-title').textContent).toBe('Map');
  });

  it('names the page from the whitelist, never from the frame\'s own (markup-flattened) title', () => {
    mount();
    press('door-map');
    post(report({ page: 'ask-photocast.html', title: 'Ask PhotoCastPro', hash: '' }));
    expect(screen.getByTestId('guide-title').textContent).toBe('Ask PhotoCast');
    post(report({ page: 'getting-started.html', title: 'Your firstfive minutes', hash: '' }));
    expect(screen.getByTestId('guide-title').textContent).toBe('Getting started');
    post(report({ page: 'night-sky.html', title: '<img src=x onerror=alert(1)>', hash: '' }));
    const title = screen.getByTestId('guide-title');
    expect(title.querySelector('img')).toBeNull();
    expect(title.textContent).toBe('Aurora and night sky');
  });

  it('follows a link inside the frame: a posted page change updates the header to that page', () => {
    mount();
    press('door-map');
    expect(screen.getByTestId('guide-title').textContent).toBe('Map');
    post(report({ page: 'settings.html', title: 'Settings.' }));
    expect(screen.getByTestId('guide-title').textContent).toBe('Settings');
    post(report({ page: 'map.html', title: 'Map.' }));
    expect(screen.getByTestId('guide-title').textContent).toBe('Map');
  });

  it('drops a hash that is not a section id', () => {
    mount();
    press('door-map');
    post(report({ hash: '#a b<script>' }));
    expect(screen.getByTestId('guide-new-tab')).toHaveAttribute('href', `${GUIDE_ORIGIN}/guide/map.html`);
  });
});

describe('closing and focus', () => {
  it('the docked ✕ closes it and puts focus back on the door that opened it', () => {
    mount();
    screen.getByTestId('door-map').focus();
    press('door-map');
    expect(document.activeElement).toBe(screen.getByTestId('guide-close'));
    act(() => { fireEvent.click(screen.getByTestId('guide-close')); });
    expect(screen.queryByTestId('guide-dock')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('door-map'));
  });

  it('Escape inside the docked panel closes it and stops there', () => {
    mount();
    press('door-map');
    const outer = vi.fn();
    document.addEventListener('keydown', outer);
    act(() => { fireEvent.keyDown(screen.getByTestId('guide-close'), { key: 'Escape' }); });
    document.removeEventListener('keydown', outer);
    expect(screen.queryByTestId('guide-dock')).toBeNull();
    expect(outer).not.toHaveBeenCalled();
  });

  it('Escape elsewhere does not close the docked panel (it works from the panel chrome only)', () => {
    mount();
    press('door-map');
    act(() => { fireEvent.keyDown(document.body, { key: 'Escape' }); });
    expect(screen.getByTestId('guide-dock')).toBeInTheDocument();
  });

  it('Escape closes the stacked dialog only while it is the last dialog, and the scrim closes it', () => {
    mount({ initialLayers: 1 });
    press('door-map');
    act(() => { fireEvent.keyDown(document, { key: 'Escape' }); });
    expect(screen.queryByTestId('guide-dialog')).toBeNull();
    press('door-map');
    act(() => { fireEvent.click(screen.getByTestId('guide-scrim')); });
    expect(screen.queryByTestId('guide-dialog')).toBeNull();
  });

  it('the dialog\'s Escape stands down when another dialog is mounted after it', () => {
    mount({ initialLayers: 1 });
    press('door-map');
    const later = document.createElement('div');
    later.setAttribute('role', 'dialog');
    document.body.appendChild(later);
    act(() => { fireEvent.keyDown(document, { key: 'Escape' }); });
    later.remove();
    expect(screen.getByTestId('guide-dialog')).toBeInTheDocument();
  });

  it('returns focus through returnFocus when the door that opened it is gone (a popover that closed)', () => {
    const { probe } = mount({ initialLayers: 1 });
    const gone = document.createElement('button');
    document.body.appendChild(gone);
    gone.focus();
    act(() => probe.guide.openGuide('map', '', { returnFocus: () => screen.getByTestId('layer-0-button') }));
    gone.remove();
    act(() => { fireEvent.click(screen.getByTestId('guide-scrim')); });
    expect(screen.queryByTestId('guide-dialog')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('layer-0-button'));
  });

  it('a docked guide closes to the returnFocus node it was given', () => {
    const trigger = document.createElement('button');
    document.body.appendChild(trigger);
    const { probe } = mount();
    act(() => probe.guide.openGuide('map', '', { returnFocus: () => trigger }));
    act(() => { fireEvent.click(screen.getByTestId('guide-close')); });
    expect(document.activeElement).toBe(trigger);
    trigger.remove();
  });

  it('records what closed it, for the shell\'s dock restore', () => {
    const { probe } = mount();
    expect(probe.guide.lastClose.seq).toBe(0);
    press('door-map');
    act(() => { fireEvent.click(screen.getByTestId('guide-close')); });
    expect(probe.guide.lastClose).toEqual({ seq: 1, restoreFocus: true, doorInDock: false });
  });

  it('notes a door inside the Ask dock, which is gone by the time the guide closes', () => {
    const { probe } = mount();
    const dock = document.createElement('div');
    dock.setAttribute('data-ask-surface', '');
    const door = document.createElement('button');
    dock.appendChild(door);
    document.body.appendChild(dock);
    door.focus();
    act(() => probe.guide.openGuide('ask-photocast'));
    dock.remove();
    act(() => probe.guide.closeGuide());
    expect(probe.guide.lastClose.doorInDock).toBe(true);
  });
});

describe('tab changes', () => {
  it.each([['dialog', 1], ['sheet', 0]])('closes the %s presentation when the tab changes', (kind, layers) => {
    const width = kind === 'sheet' ? 768 : 1280;
    const { rerenderWith } = mount({ initialLayers: layers }, width);
    press('door-map');
    expect(screen.getByRole('dialog', { name: 'PhotoCast guide' })).toBeInTheDocument();
    rerenderWith({ tab: 'map', initialLayers: layers });
    expect(screen.queryByRole('dialog', { name: 'PhotoCast guide' })).toBeNull();
  });

  it('the docked panel follows the reader when it is showing the old tab\'s page', () => {
    const { rerenderWith } = mount({ tab: 'plan' });
    act(() => screen.getByTestId('door-map').click());
    // Opened on the Map page while on Plan: not Plan's own page, so it must stay put.
    rerenderWith({ tab: 'coming-up' });
    expect(frame().getAttribute('src')).toContain('/map.html');
  });

  it('the docked panel loads the new tab\'s page when it was showing the old tab\'s', () => {
    const probe = makeProbe();
    installViewport(1280);
    const view = render(<Harness probe={probe} tab="plan" />);
    act(() => probe.guide.openGuide('at-a-glance'));
    const before = frame();
    view.rerender(<Harness probe={probe} tab="coming-up" />);
    expect(frame().getAttribute('src')).toContain('/coming-up.html?embed=1');
    expect(frame()).not.toBe(before);
    expect(screen.getByTestId('guide-dock')).toBeInTheDocument();
  });

  it('follows by the page the frame REPORTED, not the one it was opened on', () => {
    const probe = makeProbe();
    installViewport(1280);
    const view = render(<Harness probe={probe} tab="plan" />);
    act(() => probe.guide.openGuide('settings'));
    post(report({ page: 'at-a-glance.html', title: 'At a Glance.', hash: '' }));
    view.rerender(<Harness probe={probe} tab="map" />);
    expect(frame().getAttribute('src')).toContain('/map.html');
    // ...and the reverse: a frame that wandered off the old tab's page stays where the reader took it.
    post(report({ page: 'settings.html', title: 'Settings.', hash: '' }));
    view.rerender(<Harness probe={probe} tab="coming-up" />);
    expect(frame().getAttribute('src')).toContain('/map.html');
  });

  it('entering Operations closes the docked panel', () => {
    const { rerenderWith } = mount({ tab: 'plan' });
    press('door-map');
    rerenderWith({ tab: 'operations' });
    expect(screen.queryByTestId('guide-dock')).toBeNull();
  });
});

describe('crossing 1024px', () => {
  it('ends a docked guide when the window narrows to a tablet', () => {
    const { viewport } = mount();
    press('door-map');
    act(() => viewport.resize(900));
    expect(screen.queryByTestId('guide-dock')).toBeNull();
    expect(screen.queryByTestId('bottom-sheet')).toBeNull();
  });

  it('ends a sheet when the window widens to a desktop', () => {
    const { viewport } = mount({}, 900);
    press('door-map');
    act(() => viewport.resize(1280));
    expect(screen.queryByTestId('bottom-sheet')).toBeNull();
  });
});

describe('without a provider', () => {
  it('every door is a no-op', () => {
    function Bare() {
      const guide = useGuide();
      guide.openGuide('map');
      guide.closeGuide();
      return <output data-testid="bare">{String(guide.open)}</output>;
    }
    render(<Bare />);
    expect(screen.getByTestId('bare')).toHaveTextContent('false');
  });
});
