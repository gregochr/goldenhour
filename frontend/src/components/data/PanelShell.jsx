import PropTypes from 'prop-types';
import { PANEL_TITLE_ATTR } from './DataSplitLayout.jsx';

/**
 * The Data tabs' panel frame (handoff, shared rules): the title in the serif face, a one-line meta, the ×, then the
 * body and an optional pinned footer. Every panel mode (view, edit, add) draws it, so the header stays where it is
 * as the body changes. {@code testIdPrefix} names the title, meta and × ({@code {prefix}-title},
 * {@code -subtitle}, {@code -close}), so Users can keep {@code user-panel-title} and {@code user-panel-close}.
 *
 * <p>The body scrolls inside the panel and the footer stays pinned under it (on the phone page the footer sticks to
 * the foot of the screen). The title takes focus when a panel opens ({@code tabIndex=-1}; no focus ring, it is not a
 * control).
 *
 * <p>On a phone the layout's back bar closes the panel, so the × is hidden at 639px and under (CSS only).
 *
 * @param {object} props
 * @param {string} props.title the panel title (an email, a name)
 * @param {import('react').ReactNode} [props.subtitle] one line under the title
 * @param {() => void} props.onClose closes the panel
 * @param {string} props.testIdPrefix prefix for the test ids; also the root's test id unless {@code testId} is given
 * @param {string} [props.testId] the root's test id, when a tab has several panel modes that tests tell apart
 *   (Users: {@code user-reader-panel}, {@code user-edit-account}) but that share one title and × id
 * @param {import('react').ReactNode} [props.children] the body
 * @param {import('react').ReactNode} [props.footer] pinned under the body
 */
export default function PanelShell({
  title, subtitle = null, onClose, testIdPrefix, testId = undefined, children = null, footer = null,
}) {
  return (
    <div className="flex min-h-0 flex-1 flex-col" data-testid={testId ?? testIdPrefix}>
      <header className="flex shrink-0 items-start justify-between gap-3 border-b border-plex-border px-[18px] pb-4 pt-[18px] max-[639px]:px-4">
        <div className="min-w-0">
          <h2
            className="break-words font-serif text-[22px] font-semibold leading-tight text-plex-text focus:outline-hidden"
            tabIndex={-1}
            {...{ [PANEL_TITLE_ATTR]: '' }}
            data-testid={`${testIdPrefix}-title`}
          >
            {title}
          </h2>
          {subtitle && (
            <p className="mt-1 text-[12.5px] text-plex-text-secondary" data-testid={`${testIdPrefix}-subtitle`}>
              {subtitle}
            </p>
          )}
        </div>
        <button
          type="button"
          className="-mr-1 flex size-9 shrink-0 items-center justify-center rounded text-lg leading-none text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:hidden"
          aria-label="Close panel"
          onClick={onClose}
          data-testid={`${testIdPrefix}-close`}
        >
          <span aria-hidden="true">&times;</span>
        </button>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto" data-testid={`${testIdPrefix}-body`}>{children}</div>
      {footer && (
        <div
          className="shrink-0 border-t border-plex-border bg-plex-panel max-[639px]:sticky max-[639px]:bottom-0"
          data-testid={`${testIdPrefix}-footer`}
        >
          {footer}
        </div>
      )}
    </div>
  );
}

PanelShell.propTypes = {
  title: PropTypes.string.isRequired,
  subtitle: PropTypes.node,
  onClose: PropTypes.func.isRequired,
  testIdPrefix: PropTypes.string.isRequired,
  testId: PropTypes.string,
  children: PropTypes.node,
  footer: PropTypes.node,
};
