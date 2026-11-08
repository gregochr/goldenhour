import http from 'node:http';

/**
 * A tiny stand-in for the production site, used ONLY by deploy-smoke.spec.js to show that
 * scripts/post-deploy-smoke.sh fails (and with which exit code) when the thing it probes is wrong.
 *
 * It answers just enough of the app for the script to pass by default, and each knob in `scenario` breaks
 * exactly one thing. It never forwards anything anywhere and listens on 127.0.0.1 on a free port.
 *
 * @param {object} scenario which single thing to break; see the fields read below
 * @returns {Promise<{url: string, state: {loginAttempts: number, logouts: number}, close: () => Promise<void>}>} the running stub
 */
export async function startStubSite(scenario = {}) {
  const state = { loginAttempts: 0, logouts: 0 };
  const csp = "default-src 'self'";

  const html = '<!doctype html><html><head><title>stub</title>'
    + '<link rel="stylesheet" href="/assets/index-stub.css">'
    + '<script type="module" src="/assets/index-stub.js"></script></head><body></body></html>';

  const json = (res, status, body, extra = {}) => {
    res.writeHead(status, { 'content-type': 'application/json', ...extra });
    res.end(JSON.stringify(body));
  };

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://stub');
    const authed = (req.headers.authorization || '').startsWith('Bearer ');
    const drain = (cb) => {
      req.resume();
      req.on('end', cb);
    };

    if (scenario.challenge) {
      // What Cloudflare answers to a client it wants to challenge.
      res.writeHead(403, { 'content-type': 'text/html', 'cf-mitigated': 'challenge' });
      res.end('<html><title>Just a moment...</title></html>');
      return;
    }
    if (scenario.edge === 'waf403') {
      // A WAF block page: no cf-mitigated header, only the page text.
      res.writeHead(403, { 'content-type': 'text/html' });
      res.end('<html><title>Attention Required! | Cloudflare</title></html>');
      return;
    }
    if (scenario.edge === 'cf1015') {
      res.writeHead(429, { 'content-type': 'text/plain' });
      res.end('error code: 1015');
      return;
    }
    if (scenario.edge === 'errorDetails') {
      res.writeHead(403, { 'content-type': 'text/plain', 'cf-error-details': 'blocked' });
      res.end('forbidden');
      return;
    }
    if (scenario.truncate && req.method === 'GET' && url.pathname === '/') {
      // Promises 5000 bytes, sends 10, then drops the connection: curl exits 18.
      res.writeHead(200, { 'content-type': 'text/html', 'content-length': '5000', 'content-security-policy': csp });
      res.write('<!doctype h');
      setTimeout(() => res.destroy(), 50);
      return;
    }

    if (req.method === 'GET' && url.pathname === '/') {
      const headers = { 'content-type': 'text/html', 'cache-control': 'no-cache' };
      if (!scenario.noCsp) headers['content-security-policy'] = csp;
      res.writeHead(200, headers);
      res.end(html);
      return;
    }
    if (url.pathname === '/assets/index-stub.js' || url.pathname === '/assets/index-stub.css') {
      res.writeHead(200, {
        'content-type': url.pathname.endsWith('.js') ? 'text/javascript' : 'text/css',
        'cache-control': 'public, max-age=31536000, immutable',
      });
      res.end('/* stub */');
      return;
    }
    if (scenario.legacyHealth) {
      // An image from before the db-only health group: /container is 404, only the aggregate exists.
      if (url.pathname === '/actuator/health/container') return json(res, 404, { error: 'Not found' });
      if (url.pathname === '/actuator/health') {
        return scenario.legacyHealth === 'down'
          ? json(res, 503, { status: 'DOWN' })
          : json(res, 200, { status: 'UP' });
      }
    }
    if (url.pathname === '/actuator/health/container') {
      json(res, scenario.healthStatus || 200, { status: scenario.healthStatus ? 'DOWN' : 'UP' });
      return;
    }
    if (url.pathname === '/api/briefing') {
      if (!authed) return json(res, 401, { error: 'Unauthorized' });
      if (scenario.briefing204) {
        res.writeHead(204);
        res.end();
        return;
      }
      const slot = { locationName: 'Stub Beach' };
      if (!scenario.nullRatings) slot.claudeRating = 4;
      if (scenario.gated) slot.gated = true;
      // `onlyUnregioned` puts the one slot in unregioned[], where the old check never looked.
      const summary = scenario.onlyUnregioned
        ? { regions: [], unregioned: [slot] }
        : { regions: [{ slots: [slot] }], unregioned: [] };
      return json(res, 200, {
        generatedAt: '2026-11-08T07:00:00',
        days: scenario.noDays ? [] : [{ date: '2026-11-08', eventSummaries: [summary] }],
      });
    }
    if (url.pathname === '/api/user/settings') {
      return authed ? json(res, 200, { ok: true }) : json(res, 401, { error: 'Unauthorized' });
    }
    if (req.method === 'POST' && url.pathname === '/api/auth/login') {
      drain(() => {
        state.loginAttempts += 1;
        if (scenario.loginEdge) {
          res.writeHead(429, { 'content-type': 'text/plain' });
          res.end('error code: 1015');
          return;
        }
        if (scenario.loginSeq) {
          // Statuses in order; the last one repeats.
          const status = scenario.loginSeq[Math.min(state.loginAttempts, scenario.loginSeq.length) - 1];
          return json(res, status, { error: 'stub' });
        }
        if (scenario.login === '429') return json(res, 429, { error: 'Too many attempts' });
        return json(res, 200, {
          accessToken: 'stub-access-token',
          refreshToken: 'stub-refresh-token',
          passwordChangeRequired: scenario.login === 'passwordChangeRequired',
        });
      });
      return;
    }
    if (req.method === 'POST' && url.pathname === '/api/auth/logout') {
      drain(() => {
        state.logouts += 1;
        json(res, 200, { message: 'Logged out' });
      });
      return;
    }
    json(res, 404, { error: 'Not found' });
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    url: `http://127.0.0.1:${port}`,
    state,
    close: () => new Promise((resolve) => {
      server.closeAllConnections?.();
      server.close(() => resolve());
    }),
  };
}
