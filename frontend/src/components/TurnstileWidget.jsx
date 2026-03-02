import React, { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';

/**
 * Reusable Cloudflare Turnstile CAPTCHA widget.
 *
 * <p>Polls for {@code window.turnstile} availability (async script load),
 * then renders the widget into a container div. Use a React {@code key} prop
 * on this component to force a fresh widget (e.g. after form failure).
 */
export default function TurnstileWidget({ onVerify, onExpire }) {
  const containerRef = useRef(null);
  const widgetIdRef = useRef(null);

  useEffect(() => {
    if (!containerRef.current) return;

    let cancelled = false;

    function renderWidget() {
      if (cancelled || !containerRef.current) return;
      if (widgetIdRef.current != null && window.turnstile) {
        window.turnstile.remove(widgetIdRef.current);
      }
      widgetIdRef.current = window.turnstile.render(containerRef.current, {
        sitekey: import.meta.env.VITE_TURNSTILE_SITE_KEY,
        theme: 'dark',
        callback: (token) => onVerify(token),
        'expired-callback': () => onExpire(),
        'error-callback': () => onExpire(),
      });
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
  }, [onVerify, onExpire]);

  return <div ref={containerRef} data-testid="turnstile-widget" className="flex justify-center" />;
}

TurnstileWidget.propTypes = {
  onVerify: PropTypes.func.isRequired,
  onExpire: PropTypes.func.isRequired,
};
