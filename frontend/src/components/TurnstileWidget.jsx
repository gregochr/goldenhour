import React, { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';

/** Cloudflare Turnstile API script — loaded on demand rather than eagerly in index.html. */
const TURNSTILE_SCRIPT_SRC = 'https://challenges.cloudflare.com/turnstile/v0/api.js';

/**
 * Injects the Turnstile script tag once, the first time a widget mounts. Loading it here —
 * only when an auth page actually renders a CAPTCHA — keeps the third-party request off every
 * authenticated page load (the common case is a returning, signed-in user who never sees it).
 * The render effect below already polls for {@code window.turnstile}, so it picks the API up
 * as soon as the script finishes loading, whether that is now or milliseconds from now.
 */
function ensureTurnstileScript() {
  if (typeof document === 'undefined') return;
  if (document.querySelector(`script[src="${TURNSTILE_SCRIPT_SRC}"]`)) return;
  const script = document.createElement('script');
  script.src = TURNSTILE_SCRIPT_SRC;
  script.async = true;
  script.defer = true;
  document.head.appendChild(script);
}

/**
 * Reusable Cloudflare Turnstile CAPTCHA widget.
 *
 * <p>Injects the Turnstile script on mount (if not already present), polls for
 * {@code window.turnstile} availability, then renders the widget into a container div.
 * Use a React {@code key} prop on this component to force a fresh widget (e.g. after
 * form failure).
 *
 * <p>Calls {@code onLoadFail} if the script does not load within 10 seconds,
 * or if {@code window.turnstile.render} throws (e.g. domain not whitelisted).
 */
export default function TurnstileWidget({ onVerify, onExpire, onLoadFail }) {
  const containerRef = useRef(null);
  const widgetIdRef = useRef(null);
  const onVerifyRef = useRef(onVerify);
  const onExpireRef = useRef(onExpire);
  const onLoadFailRef = useRef(onLoadFail);

  // Keep refs up to date without re-running the render effect below.
  // Written in an effect (not during render) so the latest callbacks are
  // captured for the async render/poll logic after each commit.
  useEffect(() => {
    onVerifyRef.current = onVerify;
    onExpireRef.current = onExpire;
    onLoadFailRef.current = onLoadFail;
  });

  useEffect(() => {
    if (!containerRef.current) return;

    ensureTurnstileScript();

    let cancelled = false;

    function renderWidget() {
      if (cancelled || !containerRef.current) return;
      if (widgetIdRef.current != null && window.turnstile) {
        window.turnstile.remove(widgetIdRef.current);
      }
      try {
        widgetIdRef.current = window.turnstile.render(containerRef.current, {
          sitekey: import.meta.env.VITE_TURNSTILE_SITE_KEY,
          theme: 'dark',
          callback: (token) => onVerifyRef.current(token),
          'expired-callback': () => onExpireRef.current(),
          'error-callback': () => onExpireRef.current(),
        });
      } catch {
        // render() threw — likely domain not whitelisted for this sitekey.
        // Signal the parent so it can show a fallback instead of a disabled button.
        if (!cancelled) onLoadFailRef.current?.();
      }
    }

    if (typeof window.turnstile !== 'undefined') {
      renderWidget();
    } else {
      let elapsed = 0;
      const interval = setInterval(() => {
        elapsed += 200;
        if (typeof window.turnstile !== 'undefined') {
          clearInterval(interval);
          renderWidget();
        } else if (elapsed >= 10000) {
          clearInterval(interval);
          if (!cancelled) onLoadFailRef.current?.();
        }
      }, 200);
      return () => {
        cancelled = true;
        clearInterval(interval);
        if (widgetIdRef.current != null && window.turnstile) {
          window.turnstile.remove(widgetIdRef.current);
          widgetIdRef.current = null;
        }
      };
    }

    return () => {
      cancelled = true;
      if (widgetIdRef.current != null && window.turnstile) {
        window.turnstile.remove(widgetIdRef.current);
        widgetIdRef.current = null;
      }
    };
  }, []);

  return <div ref={containerRef} data-testid="turnstile-widget" className="flex justify-center" />;
}

TurnstileWidget.propTypes = {
  onVerify: PropTypes.func.isRequired,
  onExpire: PropTypes.func.isRequired,
  onLoadFail: PropTypes.func,
};
