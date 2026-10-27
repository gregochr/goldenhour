import PropTypes from 'prop-types';

/**
 * The reader panel's frame (handoff B3): the title in the serif face, a one-line subtitle, the close
 * button, then the body. Every panel mode (the reader, Change picks, Edit account, Add user) draws it, so
 * the header and the × stay where they are as the body changes.
 *
 * @param {object} props
 * @param {string} props.title the panel title (a username, or an action naming it)
 * @param {React.ReactNode} [props.subtitle] one line under the title
 * @param {() => void} props.onClose closes the panel
 * @param {string} props.testId the panel's test id
 * @param {React.ReactNode} props.children the body
 * @param {React.ReactNode} [props.footer] pinned under the body
 */
export default function PanelShell({ title, subtitle = null, onClose, testId, children, footer = null }) {
  return (
    <section className="flex h-full flex-col" aria-label={title} data-testid={testId}>
      <header className="flex items-start justify-between gap-3 border-b border-plex-border px-5 pb-4 pt-5">
        <div className="min-w-0">
          <h2 className="truncate font-serif text-[22px] font-semibold leading-tight text-plex-text" data-testid="user-panel-title">
            {title}
          </h2>
          {subtitle && (
            <p className="mt-1 text-xs text-plex-text-secondary" data-testid="user-panel-subtitle">{subtitle}</p>
          )}
        </div>
        <button
          type="button"
          className="-mr-1 flex size-8 shrink-0 items-center justify-center rounded text-lg leading-none text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
          aria-label="Close panel"
          onClick={onClose}
          data-testid="user-panel-close"
        >
          <span aria-hidden="true">&times;</span>
        </button>
      </header>
      <div className="min-h-0 flex-1">{children}</div>
      {footer}
    </section>
  );
}

PanelShell.propTypes = {
  title: PropTypes.string.isRequired,
  subtitle: PropTypes.node,
  onClose: PropTypes.func.isRequired,
  testId: PropTypes.string.isRequired,
  children: PropTypes.node,
  footer: PropTypes.node,
};
