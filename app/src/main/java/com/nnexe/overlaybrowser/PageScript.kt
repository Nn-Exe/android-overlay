package com.nnexe.overlaybrowser

/**
 * Injected into every page. It does two jobs that a WebView inside an overlay can't do on its own:
 *
 * 1. Keyboard focus. The overlay window normally can't take focus, so the game underneath keeps
 *    the gamepad. When the user taps a text field, we ask the window to take focus so the
 *    keyboard can open, and give focus back when they leave the field. Fields a page focuses on
 *    its own (autofocus) are ignored, so loading a page never takes the gamepad from the game.
 * 2. <select> lists. WebView draws these as Activity dialogs, which can't appear over other apps.
 *    We block the built-in one and show our own list instead.
 * 3. Video. Reports the page's main video (size, playing) so the window can take its shape, and
 *    provides __ovlVideoMode(on) to show just the video player.
 */
const val PAGE_SCRIPT = """
(function () {
  if (window.__ovlInstalled) return;
  var B = window.__ovlBridge;
  if (!B) return;
  window.__ovlInstalled = true;

  var NON_TEXT = /^(button|checkbox|color|file|hidden|image|radio|range|reset|submit|date|datetime-local|month|time|week)$/i;
  function editable(el) {
    if (!el) return false;
    if (el.isContentEditable) return true;
    if (el.disabled || el.readOnly) return false;
    if (el.tagName === 'TEXTAREA') return true;
    if (el.tagName === 'INPUT') return !NON_TEXT.test(el.type || 'text');
    return false;
  }

  var lastTouch = 0;
  document.addEventListener('pointerdown', function () { lastTouch = Date.now(); }, true);
  document.addEventListener('focusin', function (e) {
    if (editable(e.target) && Date.now() - lastTouch < 1500) B.editStart();
  }, true);
  // A tap on a field that already has focus (e.g. autofocus) fires no focusin, so catch it here.
  // Only when the tap landed on that field: a tap elsewhere must not open the keyboard for it.
  document.addEventListener('click', function (e) {
    var el = document.activeElement;
    if (editable(el) && el.contains(e.target)) B.editStart();
  }, true);
  document.addEventListener('focusout', function () {
    setTimeout(function () { if (!editable(document.activeElement)) B.editEnd(); }, 300);
  }, true);

  var current = null;
  document.addEventListener('mousedown', function (e) {
    var s = e.target;
    if (!s || s.tagName !== 'SELECT' || s.disabled) return;
    e.preventDefault();
    s.focus();
    current = s;
    var items = [];
    for (var i = 0; i < s.options.length; i++) {
      var o = s.options[i];
      items.push([o.text || o.label || '', o.selected]);
    }
    B.select(JSON.stringify(items), !!s.multiple);
  }, true);

  window.__ovlApplySelect = function (picked) {
    if (!current) return;
    if (current.multiple) {
      for (var i = 0; i < current.options.length; i++) current.options[i].selected = picked.indexOf(i) >= 0;
    } else if (picked.length) {
      current.selectedIndex = picked[0];
    }
    current.dispatchEvent(new Event('input', { bubbles: true }));
    current.dispatchEvent(new Event('change', { bubbles: true }));
  };

  // The main video: the biggest one with a known size (or the one we're showing in video mode).
  function mainVideo() {
    var best = null, bestArea = 0, vids = document.getElementsByTagName('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (!v.videoWidth || !v.videoHeight) continue;
      var r = v.getBoundingClientRect(), a = r.width * r.height;
      if (v.closest('.__ovl-player')) a = Infinity;
      if (a > bestArea) { bestArea = a; best = v; }
    }
    return best;
  }

  // Tell the app about it: "width,height,playing,muted,% of page width". Sent only when it changes.
  var lastReport = '';
  function report() {
    var v = mainVideo(), s = '0,0,0,0,0';
    if (v) {
      var r = v.getBoundingClientRect();
      s = [v.videoWidth, v.videoHeight, (!v.paused && !v.ended) ? 1 : 0, (v.muted || v.volume === 0) ? 1 : 0,
        Math.round(100 * r.width / Math.max(1, window.innerWidth))].join(',');
    }
    if (document.documentElement.classList.contains('__ovl-video')) {
      // Sites like YouTube can swap their player element out; follow the video to the new one.
      var p = document.querySelector('.__ovl-player');
      if (v && (!p || (p !== v && !p.contains(v)))) window.__ovlVideoMode(true);
    }
    if (s !== lastReport) { lastReport = s; B.video(s); }
  }
  ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'resize', 'volumechange', 'emptied'].forEach(function (t) {
    document.addEventListener(t, report, true);
  });
  setInterval(report, 1000);

  // Video mode: the player fills the page and everything else is hidden. Nothing is moved or
  // removed, so the site keeps working, and switching off restores it exactly.
  var STYLE =
    'html.__ovl-video, html.__ovl-video body { overflow: hidden !important; background: #000 !important; }' +
    'html.__ovl-video body *:not(.__ovl-anc):not(.__ovl-player):not(.__ovl-player *) { visibility: hidden !important; }' +
    'html.__ovl-video .__ovl-anc { transform: none !important; filter: none !important; perspective: none !important;' +
    ' contain: none !important; will-change: auto !important; backdrop-filter: none !important; }' +
    'html.__ovl-video .__ovl-player { position: fixed !important; left: 0 !important; top: 0 !important;' +
    ' right: auto !important; bottom: auto !important; width: 100vw !important; height: 100vh !important;' +
    ' max-width: none !important; max-height: none !important; min-width: 0 !important; min-height: 0 !important;' +
    ' margin: 0 !important; padding: 0 !important; transform: none !important; z-index: 2147483647 !important;' +
    ' background: #000 !important; visibility: visible !important; }' +
    'html.__ovl-video video.__ovl-player, html.__ovl-video .__ovl-player video { object-fit: contain !important; }' +
    // Boxes between the player and the video, and the video itself, are often sized by the site's own
    // script (YouTube hard-codes pixel sizes); stretch them to the player so the picture fills it.
    'html.__ovl-video .__ovl-fill, html.__ovl-video .__ovl-vid { position: absolute !important; left: 0 !important;' +
    ' top: 0 !important; width: 100% !important; height: 100% !important; max-width: none !important;' +
    ' max-height: none !important; margin: 0 !important; padding: 0 !important; transform: none !important; }';
  var YT_PLAYERS = ['#player-container-id', '#player-container', 'ytm-player', '#movie_player'];

  window.__ovlVideoMode = function (on) {
    var html = document.documentElement;
    var marked = document.querySelectorAll('.__ovl-player, .__ovl-anc, .__ovl-fill, .__ovl-vid');
    for (var i = 0; i < marked.length; i++) marked[i].classList.remove('__ovl-player', '__ovl-anc', '__ovl-fill', '__ovl-vid');
    if (window.__ovlCtl) { window.__ovlCtl.v.controls = window.__ovlCtl.c; window.__ovlCtl = null; }
    html.classList.remove('__ovl-video');
    if (!on) { window.dispatchEvent(new Event('resize')); return false; }
    var v = mainVideo();
    if (!v) return false;
    // YouTube: its player box, which holds the controls too.
    var p = null;
    for (var k = 0; k < YT_PLAYERS.length && !p; k++) {
      var c = document.querySelector(YT_PLAYERS[k]);
      if (c && c.contains(v)) p = c;
    }
    if (!p) {
      // Anywhere else: the outermost box that's still about the video's size (the site's player).
      p = v;
      var vr = v.getBoundingClientRect();
      for (var e = v.parentElement; e && e !== document.body; e = e.parentElement) {
        var r = e.getBoundingClientRect();
        if (r.width > vr.width * 1.1 + 2 || r.height > vr.height * 1.15 + 2) break;
        p = e;
      }
    }
    // A player with no buttons of its own would leave no way to pause; borrow the browser's controls.
    if (!v.controls && (p === v || !p.querySelector('button, [role="button"], input[type="range"]'))) {
      window.__ovlCtl = { v: v, c: v.controls };
      v.controls = true;
    }
    p.classList.add('__ovl-player');
    for (var a = p.parentElement; a; a = a.parentElement) a.classList.add('__ovl-anc');
    if (p !== v) {
      for (var f = v.parentElement; f && f !== p; f = f.parentElement) f.classList.add('__ovl-fill');
      v.classList.add('__ovl-vid');
    }
    if (!document.getElementById('__ovl-style')) {
      var st = document.createElement('style');
      st.id = '__ovl-style';
      st.textContent = STYLE;
      (document.head || html).appendChild(st);
    }
    html.classList.add('__ovl-video');
    window.dispatchEvent(new Event('resize'));
    return true;
  };
  report();
})();
"""
