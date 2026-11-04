import {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import GuidePanel from '../components/guide/GuidePanel.jsx';
import useAskSurface from '../hooks/useAskSurface.js';
import {
  GUIDE_LABEL, GUIDE_PAGES, MODAL_DIALOG_SELECTOR, TAB_GUIDE, guideUrl,
} from '../utils/guide.js';

/** What a close last did, for the shell: it restores the Ask dock and decides whether to focus it. */
const NEVER_CLOSED = Object.freeze({ seq: 0, restoreFocus: true, doorInDock: false });

/**
 * With no provider (a rewind-free test, a storybook-style mount) every door is a no-op, so a component
 * that renders a `?` never has to be wrapped just to render.
 */
const NO_GUIDE = Object.freeze({
  open: false,
  presentation: null,
  page: null,
  hash: '',
  nonce: 0,
  focusKey: 0,
  tracked: null,
  guideModal: false,
  lastClose: NEVER_CLOSED,
  openGuide: () => {},
  closeGuide: () => {},
  trackGuide: () => {},
});

const GuideContext = createContext(NO_GUIDE);

/** Every dialog; {@link openGuide} keeps the modal ones and the stacked (inert) ones. */
const LAYER_SELECTOR = '[role="dialog"]';

/**
 * The in-app guide's state and its three presentations (docs/engineering/user-guide-plan.md, G2).
 *
 * <p>{@code useGuide()} returns {@code { open, presentation, page, hash, guideModal, openGuide(page,
 * hash?, { returnFocus? }), closeGuide() }}. The presentation is chosen at PRESS time from the state
 * of the page:
 *
 * <ol>
 *   <li>{@code 'docked'} — desktop and wide ({@code useAskSurface()} is {@code 'desktop'} or
 *       {@code 'wide'}) with no {@code aria-modal} layer in the document. The SHELL draws it, in the Ask
 *       dock's slot, because it needs the docked row; this provider only holds the state.
 *   <li>{@code 'dialog'} — desktop and wide with one modal layer up (Settings, the popup, a sheet): a
 *       right-hand panel over a scrim, drawn here.
 *   <li>{@code 'sheet'} — phone and tablet: a tall {@code BottomSheet}, drawn here.
 * </ol>
 *
 * <p><b>The two-deep rule.</b> A layer is a {@code [role=dialog]} that is modal ({@code aria-modal="true"})
 * or STACKED (inert, or inside an inert ancestor: the shell's lower layers give up {@code aria-modal} but are
 * still layers). An upsell popover is a non-modal, non-inert dialog and is not counted. Fewer than two: open
 * over them. Two or more: the reader's layers are left alone (never closed for the guide) and the full guide page
 * opens in a new tab, at the section, so a third layer is never stacked. (G2 counted only {@code aria-modal} layers, so it never saw a stacked popup under a sheet.)
 *
 * <p><b>The stacked protocol.</b> {@code guideModal} is true for presentations 2 and 3. Every layer that
 * can sit under the guide reads it and, while it is true, passes {@code stacked} to its {@code Modal}
 * (inert, no {@code aria-modal}) and withholds its own Escape, as {@code UserSettingsModal} does for its
 * sub-region sheet. Presentation 1 is not a dialog, so it asks nothing of anyone.
 *
 * <p><b>Tabs.</b> Presentations 2 and 3 close when the active tab changes (an effect keyed on the tab,
 * never {@code selectTab}, which runs during render). Presentation 1 follows the reader: if the page it is
 * showing is the old tab's {@code TAB_GUIDE} page it loads the new tab's, otherwise it stays; Operations
 * has no guide page and closes it.
 *
 * @param {object} props
 * @param {React.ReactNode} props.children
 * @param {function(): void} [props.closeShellDialogs] unused since G3 review 1 (the two-deep rule no longer closes
 *        the shell's dialogs); App still passes it
 * @param {string} props.activeTab the tab id in force (App's {@code activePlanTab})
 */
export function GuideProvider({ children, activeTab }) {
  const surface = useAskSurface();
  const [guide, setGuide] = useState(null);
  const [tracked, setTracked] = useState(null);
  const [lastClose, setLastClose] = useState(NEVER_CLOSED);

  const guideRef = useRef(null);
  const trackedRef = useRef(null);
  const nonceRef = useRef(0);
  const surfaceRef = useRef(surface);
  const tabRef = useRef(activeTab);
  useEffect(() => {
    surfaceRef.current = surface;
    tabRef.current = activeTab;
  });

  const commit = useCallback((next, nextTracked = null) => {
    guideRef.current = next;
    trackedRef.current = nextTracked;
    setGuide(next);
    setTracked(nextTracked);
  }, []);

  const trackGuide = useCallback((report) => {
    trackedRef.current = report;
    setTracked(report);
  }, []);

  const closeGuide = useCallback(({ restoreFocus = true } = {}) => {
    const current = guideRef.current;
    if (!current) return;
    // The docked column is no dialog, so nothing restores focus for it: move it BEFORE the state
    // changes (the node that holds it is about to go), to the door if the door can still take it. A
    // door that lived in the Ask dock is gone with the dock; the shell then focuses the restored
    // dock's field instead. The dialog and the sheet restore through `useDialogFocus`.
    if (restoreFocus && current.presentation === 'docked') {
      const node = current.returnFocus();
      if (node?.isConnected) node.focus({ preventScroll: true });
    }
    commit(null);
    setLastClose((prev) => ({
      seq: prev.seq + 1, restoreFocus, doorInDock: current.doorInDock,
    }));
  }, [commit]);

  const openGuide = useCallback((page, hash = '', { returnFocus } = {}) => {
    if (!Object.prototype.hasOwnProperty.call(GUIDE_PAGES, page)) return;
    const hashId = typeof hash === 'string' ? hash.replace(/^#/, '') : '';
    const current = guideRef.current;
    // Already a dialog or sheet: it is the only layer the reader can reach, so a second press just
    // takes the same panel to the new place.
    if (current && current.presentation !== 'docked') {
      nonceRef.current += 1;
      commit({
        ...current, page, hash: hashId, nonce: nonceRef.current, focusKey: nonceRef.current,
      }, null);
      return;
    }
    const active = document.activeElement;
    const press = active && active !== document.body && active !== document.documentElement ? active : null;
    const standingLayers = () => Array.from(document.querySelectorAll(LAYER_SELECTOR))
      .filter((node) => node.getAttribute('aria-label') !== GUIDE_LABEL)
      .filter((node) => node.matches(MODAL_DIALOG_SELECTOR) || node.closest('[inert]'));
    const openFullPage = () => window.open(guideUrl(page, hashId), '_blank', 'noopener,noreferrer');
    const layers = standingLayers().length;
    if (layers >= 2) {
      // Already two deep, whichever door was pressed: never a third layer, and never take the reader's own
      // layers down for the guide. The full page, in a tab.
      openFullPage();
      return;
    }
    const surfaceNow = surfaceRef.current;
    const wide = surfaceNow === 'desktop' || surfaceNow === 'wide';
    let presentation = 'sheet';
    if (wide) presentation = layers === 0 && tabRef.current !== 'operations' ? 'docked' : 'dialog';
    nonceRef.current += 1;
    commit({
      presentation,
      page,
      hash: hashId,
      nonce: nonceRef.current,
      // Only an openGuide moves focus to the ✕; the tab-follow below reloads the frame without it.
      focusKey: nonceRef.current,
      returnFocus: returnFocus ?? (() => press),
      doorInDock: Boolean(press?.closest?.('[data-ask-surface]')),
    });
  }, [commit]);

  // Rotating or resizing across 1024px: the docked column and the dialog need the wide bands and the
  // sheet needs the narrow ones, so a guide left over from the other side is ended rather than redrawn.
  useEffect(() => {
    const current = guideRef.current;
    if (!current) return;
    const wide = surface === 'desktop' || surface === 'wide';
    if ((current.presentation === 'sheet') === wide) closeGuide({ restoreFocus: false });
  }, [surface, closeGuide]);

  // A tab change. Never from `selectTab` (it runs during render and may hold shell setters only).
  const previousTab = useRef(activeTab);
  useEffect(() => {
    const before = previousTab.current;
    previousTab.current = activeTab;
    if (before === activeTab) return;
    const current = guideRef.current;
    if (!current) return;
    if (current.presentation !== 'docked') {
      closeGuide({ restoreFocus: false });
      return;
    }
    if (activeTab === 'operations' || !TAB_GUIDE[activeTab]) {
      closeGuide({ restoreFocus: false });
      return;
    }
    const showing = trackedRef.current?.page ?? current.page;
    if (TAB_GUIDE[before] && showing === TAB_GUIDE[before]) {
      nonceRef.current += 1;
      commit({ ...current, page: TAB_GUIDE[activeTab], hash: '', nonce: nonceRef.current });
    }
  }, [activeTab, closeGuide, commit]);

  const open = guide !== null;
  const guideModal = open && guide.presentation !== 'docked';
  const value = useMemo(() => ({
    open,
    presentation: guide?.presentation ?? null,
    page: guide?.page ?? null,
    hash: guide?.hash ?? '',
    nonce: guide?.nonce ?? 0,
    focusKey: guide?.focusKey ?? 0,
    tracked,
    guideModal,
    lastClose,
    openGuide,
    closeGuide,
    trackGuide,
  }), [open, guide, tracked, guideModal, lastClose, openGuide, closeGuide, trackGuide]);

  return (
    <GuideContext.Provider value={value}>
      {children}
      {guideModal && (
        <GuidePanel
          presentation={guide.presentation}
          page={guide.page}
          hash={guide.hash}
          nonce={guide.nonce}
          focusKey={guide.focusKey}
          tracked={tracked}
          onTrack={trackGuide}
          onClose={() => closeGuide()}
          returnFocus={guide.returnFocus}
        />
      )}
    </GuideContext.Provider>
  );
}

GuideProvider.propTypes = {
  children: PropTypes.node,
  closeShellDialogs: PropTypes.func,
  activeTab: PropTypes.string,
};

/**
 * The guide's state and doors. With no {@link GuideProvider} above, every function is a no-op.
 *
 * @returns {object} see {@link GuideProvider}
 */
export function useGuide() {
  return useContext(GuideContext);
}
