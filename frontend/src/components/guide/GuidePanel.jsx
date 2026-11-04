import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import BottomSheet from '../BottomSheet.jsx';
import useDialogFocus from '../../hooks/useDialogFocus.js';
import useStickyViewportFill from '../../hooks/useStickyViewportFill.js';
import {
  GUIDE_LABEL, GUIDE_ORIGIN, GUIDE_PAGES, guideUrl, pageIdFromMessage,
} from '../../utils/guide.js';

/** A section id from a message: a `#` and a short run of id characters, or nothing. */
const HASH_PATTERN = /^#[A-Za-z0-9_-]{1,80}$/;

/**
 * The iframe, and the one place a `message` from it is believed.
 *
 * <p>Three checks, all required: the origin is the guide's ({@link GUIDE_ORIGIN}), the source is THIS
 * frame's window (not another frame, not the page itself), and the payload says it is a
 * `photocast-guide`. A page name that is not one of {@code GUIDE_PAGES} drops the whole message. The frame's own
 * {@code title} is deliberately IGNORED: it is an h1's text content, so markup runs together ("Ask
 * PhotoCastPro"). The header names the page from {@code GUIDE_PAGES}.
 *
 * <p>Keyed on {@code nonce}: every {@code openGuide} bumps it, so reopening the same `page#hash` after
 * the reader has navigated inside the guide reloads the frame at the right place instead of leaving it
 * where they wandered to.
 *
 * <p>⚠️ A keypress or press inside the iframe never reaches the app: it is a separate document, so
 * Escape and the Map's outside-press rules cannot hear it. They work only from the panel's own chrome.
 */
function GuideFrame({ page, hash, nonce, onTrack }) {
  const frameRef = useRef(null);
  const onTrackRef = useRef(onTrack);
  useEffect(() => { onTrackRef.current = onTrack; }, [onTrack]);
  useEffect(() => {
    const onMessage = (event) => {
      if (event.origin !== GUIDE_ORIGIN) return;
      const frame = frameRef.current;
      if (!frame || event.source !== frame.contentWindow) return;
      const data = event.data;
      if (!data || typeof data !== 'object' || data.type !== 'photocast-guide') return;
      const trackedPage = pageIdFromMessage(data.page);
      if (!trackedPage) return;
      onTrackRef.current({
        page: trackedPage,
        hash: typeof data.hash === 'string' && HASH_PATTERN.test(data.hash) ? data.hash : '',
      });
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, []);
  // ⚠️ `allow-scripts` + `allow-same-origin` together let a framed page remove its own sandbox, so the
  // sandbox protects the app ONLY because the guide is a DIFFERENT origin. If the two ever match (a
  // misconfigured VITE_GUIDE_ORIGIN), the frame is not rendered at all.
  const sameOrigin = GUIDE_ORIGIN === window.location.origin;
  useEffect(() => {
    if (sameOrigin) {
      console.error('GUIDE_ORIGIN equals the app origin; refusing to frame the guide (sandbox would be void).');
    }
  }, [sameOrigin]);
  if (sameOrigin) return null;
  return (
    <iframe
      key={nonce}
      ref={frameRef}
      className="wf-guide-frame"
      data-testid="guide-frame"
      title={GUIDE_LABEL}
      src={guideUrl(page, hash, { embed: true })}
      sandbox="allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox"
      referrerPolicy="strict-origin"
    />
  );
}

GuideFrame.propTypes = {
  page: PropTypes.string.isRequired,
  hash: PropTypes.string,
  nonce: PropTypes.number.isRequired,
  onTrack: PropTypes.func.isRequired,
};

/** The header's title (from the page whitelist, never the frame's text) and the new-tab URL: the tracked page once the frame has reported. */
function headerState(page, hash, tracked) {
  const shownPage = tracked?.page ?? page;
  return {
    title: GUIDE_PAGES[shownPage] ?? GUIDE_PAGES.index,
    newTabUrl: guideUrl(shownPage, tracked ? tracked.hash : hash),
  };
}

/** The header the docked column and the stacked dialog share: title, "Open in new tab", ✕. */
function GuideHeader({ title, newTabUrl, closeRef, onClose }) {
  return (
    <header className="wf-guide-head">
      <h2 className="wf-guide-title" data-testid="guide-title">{title}</h2>
      <a
        className="wf-guide-newtab"
        data-testid="guide-new-tab"
        href={newTabUrl}
        target="_blank"
        rel="noopener noreferrer"
      >
        Open in new tab ↗
      </a>
      <button
        ref={closeRef}
        type="button"
        className="wf-guide-x"
        data-testid="guide-close"
        aria-label="Close guide"
        onClick={onClose}
      >
        <span aria-hidden="true">✕</span>
      </button>
    </header>
  );
}

GuideHeader.propTypes = {
  title: PropTypes.string.isRequired,
  newTabUrl: PropTypes.string.isRequired,
  closeRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
  onClose: PropTypes.func.isRequired,
};

/**
 * Moves focus to the ✕ on mount and on every fresh `openGuide` (its `focusKey`). A tab-follow reloads the
 * frame (new `nonce`) but keeps the `focusKey`, so it never steals focus from the tab being arrowed.
 */
function useFocusClose(closeRef, focusKey) {
  useEffect(() => {
    closeRef.current?.focus({ preventScroll: true });
  }, [closeRef, focusKey]);
}

/**
 * Presentation 1: the docked column, in the Ask dock's slot of the shell's docked row.
 *
 * <p>An {@code <aside>}, deliberately NOT {@code role="dialog"}: {@code shellForeignDialog} would then
 * refuse Ask while it is up, and {@code aria-modal} would stand every Map Escape rule down. Escape is a
 * native listener on the node (as {@code AskDock}'s is): it closes the guide and stops propagating, and
 * works only while focus is in the panel chrome.
 */
function DockedGuide({
  page, hash, nonce, focusKey, tracked, onTrack, onClose, band, sticky, inert,
}) {
  const asideRef = useRef(null);
  const closeRef = useRef(null);
  useStickyViewportFill(asideRef, sticky);
  useFocusClose(closeRef, focusKey);
  const onCloseRef = useRef(onClose);
  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);
  useEffect(() => {
    const node = asideRef.current;
    if (!node) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || event.isComposing) return;
      event.stopPropagation();
      onCloseRef.current();
    };
    node.addEventListener('keydown', onKeyDown);
    return () => node.removeEventListener('keydown', onKeyDown);
  }, []);
  const { title, newTabUrl } = headerState(page, hash, tracked);
  return (
    <aside
      ref={asideRef}
      id="wf-guide-dock"
      aria-label={GUIDE_LABEL}
      data-guide-surface=""
      data-testid="guide-dock"
      data-presentation="docked"
      data-band={band}
      data-sticky={sticky ? '' : undefined}
      inert={inert || undefined}
      className="wf-guide-dock"
    >
      <div className="wf-guide-dock-inner">
        <GuideHeader title={title} newTabUrl={newTabUrl} closeRef={closeRef} onClose={onClose} />
        <GuideFrame page={page} hash={hash} nonce={nonce} onTrack={onTrack} />
      </div>
    </aside>
  );
}

DockedGuide.propTypes = {
  page: PropTypes.string.isRequired,
  hash: PropTypes.string,
  nonce: PropTypes.number.isRequired,
  focusKey: PropTypes.number,
  tracked: PropTypes.shape({ page: PropTypes.string, hash: PropTypes.string }),
  onTrack: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  band: PropTypes.oneOf(['desktop', 'wide']).isRequired,
  sticky: PropTypes.bool.isRequired,
  inert: PropTypes.bool,
};

/**
 * Presentation 2: a right-hand panel over another aria-modal layer (Settings, the popup, the four-day
 * sheet). Portalled to {@code <body>} on its own z rung (see `index.css`), with a scrim, so it is the
 * only layer a screen reader can reach; the layer under it is told it is stacked (`GuideContext`'s
 * {@code guideModal}). Escape answers only while this is the last dialog in the document.
 */
function DialogGuide({
  page, hash, nonce, focusKey, tracked, onTrack, onClose, returnFocus,
}) {
  const closeRef = useRef(null);
  const dialogRef = useDialogFocus(true, { restoreFallback: returnFocus });
  useFocusClose(closeRef, focusKey);
  const onCloseRef = useRef(onClose);
  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);
  useEffect(() => {
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || event.isComposing) return;
      const dialogs = document.querySelectorAll('[role="dialog"]');
      if (dialogs[dialogs.length - 1] !== dialogRef.current) return;
      onCloseRef.current();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [dialogRef]);
  const { title, newTabUrl } = headerState(page, hash, tracked);
  return createPortal(
    <div className="wf-guide-layer" data-testid="guide-layer">
      <div
        className="app-scrim wf-guide-scrim"
        role="presentation"
        data-testid="guide-scrim"
        onClick={onClose}
      />
      <div
        ref={dialogRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={GUIDE_LABEL}
        data-guide-surface=""
        data-testid="guide-dialog"
        data-presentation="dialog"
        className="wf-guide-panel"
      >
        <GuideHeader title={title} newTabUrl={newTabUrl} closeRef={closeRef} onClose={onClose} />
        <GuideFrame page={page} hash={hash} nonce={nonce} onTrack={onTrack} />
      </div>
    </div>,
    document.body,
  );
}

DialogGuide.propTypes = {
  page: PropTypes.string.isRequired,
  hash: PropTypes.string,
  nonce: PropTypes.number.isRequired,
  focusKey: PropTypes.number,
  tracked: PropTypes.shape({ page: PropTypes.string, hash: PropTypes.string }),
  onTrack: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  returnFocus: PropTypes.func,
};

/**
 * The guide panel in one of its three presentations (docs/engineering/user-guide-plan.md, G2): the
 * docked column, the stacked dialog, or the phone/tablet sheet. Props only: the state lives in
 * {@code GuideContext}, which chooses the presentation at press time.
 *
 * <p>Presentation 3 is a {@code BottomSheet size="tall" closeOnEscape}: it portals to {@code <body>}, so
 * the tablet Ask sheet's {@code inert} on the app container does not reach it; BottomSheet's own ✕ is
 * the close (no second one), and the header carries the title only. The iframe fills the scroller and
 * scrolls itself, so the scroller is {@code flush}. {@code returnFocus} is the sheet's
 * {@code restoreFallback}, so one function owns the return target for every presentation.
 *
 * @param {object} props
 * @param {'docked'|'dialog'|'sheet'} props.presentation
 * @param {string} props.page the page opened, a `GUIDE_PAGES` id
 * @param {string} [props.hash] the section opened
 * @param {number} props.nonce bumped by every open; keys the iframe
 * @param {?{page: string, hash: string}} [props.tracked] what the frame last reported
 * @param {function(object): void} props.onTrack called with a validated report from the frame
 * @param {function(): void} props.onClose
 * @param {function(): ?Element} [props.returnFocus] where focus goes on close (dialog and sheet)
 * @param {'desktop'|'wide'} [props.band] docked only: the dock's width band
 * @param {boolean} [props.sticky] docked only: Plan and Coming up stick to the viewport, the Map does not
 * @param {boolean} [props.inert] docked only: a shell dialog, search or settings is open
 */
export default function GuidePanel({
  presentation, page, hash = '', nonce, focusKey = 0, tracked = null, onTrack, onClose, returnFocus = undefined,
  band = 'desktop', sticky = true, inert = false,
}) {
  if (presentation === 'docked') {
    return (
      <DockedGuide
        page={page}
        hash={hash}
        nonce={nonce}
        focusKey={focusKey}
        tracked={tracked}
        onTrack={onTrack}
        onClose={onClose}
        band={band}
        sticky={sticky}
        inert={inert}
      />
    );
  }
  if (presentation === 'dialog') {
    return (
      <DialogGuide
        page={page}
        hash={hash}
        nonce={nonce}
        focusKey={focusKey}
        tracked={tracked}
        onTrack={onTrack}
        onClose={onClose}
        returnFocus={returnFocus}
      />
    );
  }
  const { title } = headerState(page, hash, tracked);
  return (
    <BottomSheet
      open
      onClose={onClose}
      size="tall"
      closeOnEscape
      flush
      label={GUIDE_LABEL}
      restoreFallback={returnFocus ?? null}
      header={<h2 className="wf-guide-title" data-testid="guide-title">{title}</h2>}
    >
      <GuideFrame page={page} hash={hash} nonce={nonce} onTrack={onTrack} />
    </BottomSheet>
  );
}

GuidePanel.propTypes = {
  presentation: PropTypes.oneOf(['docked', 'dialog', 'sheet']).isRequired,
  page: PropTypes.string.isRequired,
  hash: PropTypes.string,
  nonce: PropTypes.number.isRequired,
  focusKey: PropTypes.number,
  tracked: PropTypes.shape({ page: PropTypes.string, hash: PropTypes.string }),
  onTrack: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  returnFocus: PropTypes.func,
  band: PropTypes.oneOf(['desktop', 'wide']),
  sticky: PropTypes.bool,
  inert: PropTypes.bool,
};
