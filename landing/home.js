/* Phone-only enhancements for index.html. Does nothing above 720px; fully undoes itself when the
   viewport grows past it. Plain ES5, no dependencies. Every rule that hides content is scoped to
   html.js-phone in index.html, so without this script a phone sees every word. */
(function () {
  'use strict';
  if (!window.matchMedia) return;

  var QUERY = 'screen and (max-width:720px)';
  var mq = window.matchMedia(QUERY);
  var undo = null; // array of cleanup functions while in phone mode, else null

  function all(sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  }

  function smoothBehavior() {
    var rm = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)');
    return rm && rm.matches ? 'auto' : 'smooth';
  }

  /* Sticky section bar: mark the link of the section being read. */
  function sectionBar(cleanups) {
    var links = all('.secbar a');
    if (!links.length) return;
    var targets = [];
    links.forEach(function (a) {
      var el = document.getElementById(a.getAttribute('href').slice(1));
      if (el) targets.push({ link: a, el: el });
    });
    var watch = all('#field, #platforms, #story, .close').concat(targets.map(function (t) { return t.el; }));
    var HEADER = 112;

    function update() {
      var line = window.innerHeight * 0.4;
      var active = null;
      targets.forEach(function (t) {
        var r = t.el.getBoundingClientRect();
        if (r.top <= line && r.bottom > HEADER) active = t;
      });
      targets.forEach(function (t) {
        if (t === active) t.link.setAttribute('aria-current', 'true');
        else t.link.removeAttribute('aria-current');
      });
    }

    var io = null;
    if (window.IntersectionObserver) {
      io = new IntersectionObserver(update, { rootMargin: '-' + HEADER + 'px 0px -60% 0px', threshold: 0 });
      watch.forEach(function (el) { io.observe(el); });
    }
    update();
    cleanups.push(function () {
      if (io) io.disconnect();
      targets.forEach(function (t) { t.link.removeAttribute('aria-current'); });
    });
  }

  /* One swipeable, snapping row with a dots control beneath it. */
  function carousel(scroller, regionLabel, dotLabel, cleanups) {
    var cards = all(':scope > *', scroller);
    if (!cards.length) return;
    scroller.setAttribute('tabindex', '0');
    scroller.setAttribute('role', 'region');
    scroller.setAttribute('aria-label', regionLabel);

    var dead = false;
    var current = 0;
    var dotScroll = false; // a dot tap or focus move is scrolling: update dots only, fit on settle
    var settleTimer = 0;
    var frame = 0;

    var dots = document.createElement('div');
    dots.className = 'dots';
    var buttons = cards.map(function (card, i) {
      var b = document.createElement('button');
      b.type = 'button';
      b.setAttribute('tabindex', '-1');
      b.setAttribute('aria-label', dotLabel(i, cards.length, card));
      b.addEventListener('click', function () { goTo(i); });
      dots.appendChild(b);
      return b;
    });
    scroller.parentNode.insertBefore(dots, scroller.nextSibling);

    function fit() {
      if (dead) return;
      var cs = window.getComputedStyle(scroller);
      var pad = (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0);
      scroller.style.height = (cards[current].offsetHeight + pad) + 'px';
    }
    function setDots(n) {
      current = n;
      buttons.forEach(function (b, i) {
        if (i === n) b.setAttribute('aria-current', 'true');
        else b.removeAttribute('aria-current');
      });
    }
    function setCurrent(n) {
      setDots(n);
      fit();
    }
    function armSettle() {
      window.clearTimeout(settleTimer);
      settleTimer = window.setTimeout(function () {
        dotScroll = false;
        fit();
      }, 160);
    }
    function goTo(i) {
      if (dead) return;
      var card = cards[i];
      dotScroll = true;
      setDots(i);
      var left = card.getBoundingClientRect().left - scroller.getBoundingClientRect().left + scroller.scrollLeft;
      scroller.scrollTo({ left: left, behavior: smoothBehavior() });
      armSettle();
    }
    setCurrent(0);

    // The scroller clips vertically to the active card, so IntersectionObserver ratios of taller
    // cards stay low; track the card nearest the scroller's left edge on scroll instead.
    function track() {
      frame = 0;
      if (dead) return;
      var edge = scroller.getBoundingClientRect().left;
      var best = 0;
      var bestDist = Infinity;
      cards.forEach(function (c, i) {
        var d = Math.abs(c.getBoundingClientRect().left - edge);
        if (d < bestDist) { best = i; bestDist = d; }
      });
      if (dotScroll) { setDots(best); armSettle(); }
      else if (best !== current) setCurrent(best);
    }
    function onScroll() {
      if (frame) return;
      frame = window.requestAnimationFrame(track);
    }
    scroller.addEventListener('scroll', onScroll, { passive: true });

    // Keyboard focus moving into an off-screen card brings that card into view.
    function onFocusIn(e) {
      if (dead) return;
      for (var i = 0; i < cards.length; i++) {
        if (cards[i].contains(e.target)) { goTo(i); return; }
      }
    }
    scroller.addEventListener('focusin', onFocusIn);

    // The first measurement can run before the stylesheet and web fonts apply, so re-fit whenever
    // a card changes size, and again once the page and fonts have loaded.
    var ro = null;
    if (window.ResizeObserver) {
      ro = new ResizeObserver(fit);
      cards.forEach(function (c) { ro.observe(c); });
    }
    window.addEventListener('resize', fit);
    window.addEventListener('orientationchange', fit);
    window.addEventListener('load', fit);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(fit);
    var imgs = [];
    all('img', scroller).forEach(function (im) {
      if (!im.complete) { im.addEventListener('load', fit); imgs.push(im); }
    });

    cleanups.push(function () {
      dead = true;
      if (frame) window.cancelAnimationFrame(frame);
      window.clearTimeout(settleTimer);
      if (ro) ro.disconnect();
      scroller.removeEventListener('scroll', onScroll);
      scroller.removeEventListener('focusin', onFocusIn);
      window.removeEventListener('resize', fit);
      window.removeEventListener('orientationchange', fit);
      window.removeEventListener('load', fit);
      imgs.forEach(function (im) { im.removeEventListener('load', fit); });
      scroller.style.removeProperty('height');
      if (!scroller.getAttribute('style')) scroller.removeAttribute('style');
      if (dots.parentNode) dots.parentNode.removeChild(dots);
      scroller.removeAttribute('tabindex');
      scroller.removeAttribute('role');
      scroller.removeAttribute('aria-label');
    });
  }

  function cardTitle(card) {
    var h = card.querySelector('h3');
    if (!h) return '';
    var clone = h.cloneNode(true);
    all('.pro', clone).forEach(function (p) { p.parentNode.removeChild(p); });
    return clone.textContent.replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, '');
  }

  function features(cleanups) {
    var s = document.querySelector('.feats');
    if (!s) return;
    carousel(s, 'Features', function (i, n, card) {
      return 'Feature ' + (i + 1) + ' of ' + n + ': ' + cardTitle(card);
    }, cleanups);
  }

  function pricing(cleanups) {
    var s = document.querySelector('.prices');
    if (!s) return;
    carousel(s, 'Pricing plans', function (i, n, card) {
      var t = card.querySelector('.tier');
      return 'Plan ' + (i + 1) + ' of ' + n + ': ' + (t ? t.textContent.replace(/^\s+|\s+$/g, '') : '');
    }, cleanups);
  }

  /* FAQ teasers: question is a disclosure button, answer paragraphs open on tap. */
  function faq(cleanups) {
    all('#faq .qs .qa').forEach(function (qa, i) {
      var h = qa.querySelector('h3');
      var ps = all(':scope > p', qa);
      if (!h || !ps.length) return;
      var id = 'faq-a-' + (i + 1);
      var body = document.createElement('div');
      body.className = 'qa-a';
      body.id = id;
      body.hidden = true;
      qa.insertBefore(body, ps[0]);
      // Move everything from the first to the last answer paragraph (whitespace text included)
      // so that undoing restores the original markup exactly.
      var run = [];
      for (var n = ps[0]; n; n = n.nextSibling) {
        run.push(n);
        if (n === ps[ps.length - 1]) break;
      }
      run.forEach(function (n) { body.appendChild(n); });

      var kids = Array.prototype.slice.call(h.childNodes);
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.setAttribute('aria-expanded', 'false');
      btn.setAttribute('aria-controls', id);
      kids.forEach(function (k) { btn.appendChild(k); });
      h.appendChild(btn);
      btn.addEventListener('click', function () {
        var open = btn.getAttribute('aria-expanded') === 'true';
        btn.setAttribute('aria-expanded', open ? 'false' : 'true');
        body.hidden = open;
      });

      cleanups.push(function () {
        kids.forEach(function (k) { h.appendChild(k); });
        if (btn.parentNode) btn.parentNode.removeChild(btn);
        while (body.firstChild) qa.insertBefore(body.firstChild, body);
        if (body.parentNode) body.parentNode.removeChild(body);
      });
    });
  }

  /* From the field: steps 1 and 2 behind a toggle. */
  function field(cleanups) {
    var steps = document.querySelector('#field .steps');
    if (!steps) return;
    var hadId = !!steps.id;
    if (!hadId) steps.id = 'field-steps';
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn ghost sm steps-toggle';
    btn.setAttribute('aria-expanded', 'false');
    btn.setAttribute('aria-controls', steps.id);
    btn.textContent = 'See how the forecast called it';
    btn.addEventListener('click', function () {
      var open = btn.getAttribute('aria-expanded') !== 'true';
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      if (open) steps.classList.add('is-open');
      else steps.classList.remove('is-open');
    });
    steps.parentNode.insertBefore(btn, steps);
    cleanups.push(function () {
      if (btn.parentNode) btn.parentNode.removeChild(btn);
      steps.classList.remove('is-open');
      if (!hadId) steps.removeAttribute('id');
    });
  }

  function enter() {
    if (undo) return;
    var cleanups = [];
    undo = cleanups;
    document.documentElement.classList.add('js-phone');
    sectionBar(cleanups);
    features(cleanups);
    pricing(cleanups);
    faq(cleanups);
    field(cleanups);
  }

  function leave() {
    if (!undo) return;
    var cleanups = undo;
    undo = null;
    while (cleanups.length) cleanups.pop()();
    document.documentElement.classList.remove('js-phone');
  }

  function sync() {
    if (mq.matches) enter();
    else leave();
  }

  sync();
  if (mq.addEventListener) mq.addEventListener('change', sync);
  else if (mq.addListener) mq.addListener(sync);
})();
