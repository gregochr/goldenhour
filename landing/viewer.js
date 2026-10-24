(function () {
  'use strict';
  if (typeof HTMLDialogElement !== 'function') return;

  var dialog = document.createElement('dialog');
  dialog.className = 'viewer';
  dialog.setAttribute('aria-label', 'Image viewer');
  var closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'viewer-close';
  closeBtn.setAttribute('aria-label', 'Close image');
  closeBtn.textContent = '×';
  var img = document.createElement('img');
  dialog.appendChild(closeBtn);
  dialog.appendChild(img);
  document.body.appendChild(dialog);

  var opener = null;

  document.addEventListener('click', function (e) {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    var link = e.target.closest ? e.target.closest('a.zoom') : null;
    if (!link) return;
    e.preventDefault();
    var inner = link.querySelector('img');
    img.src = link.getAttribute('href');
    img.alt = inner ? inner.alt : '';
    opener = link;
    dialog.showModal();
  });

  dialog.addEventListener('click', function () {
    dialog.close();
  });

  dialog.addEventListener('close', function () {
    img.removeAttribute('src');
    if (opener) {
      opener.focus();
      opener = null;
    }
  });
})();
