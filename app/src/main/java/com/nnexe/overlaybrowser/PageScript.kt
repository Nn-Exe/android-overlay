package com.nnexe.overlaybrowser

/**
 * Injected into every page. It does three jobs that a WebView inside an overlay can't do on its own:
 *
 * 1. Keyboard focus. The overlay window normally can't take focus, so the game underneath keeps
 *    the gamepad. When the user taps a text field, we ask the window to take focus so the
 *    keyboard can open, and give focus back when they leave the field. Fields a page focuses on
 *    its own (autofocus) are ignored, so loading a page never takes the gamepad from the game.
 * 2. <select> lists. WebView draws these as Activity dialogs, which can't appear over other apps.
 *    We block the built-in one and show our own list instead.
 * 3. Video & fullscreen. Reports the page's main video (size, playing) so the window can take its
 *    shape, provides __ovlVideoMode(on) to show just the video player, and handles HTML5 fullscreen
 *    in-place inside the WebView so Chromium doesn't swap to a separate FullScreenView surface.
 */
const val PAGE_SCRIPT = """
(function () {
  if (window.__ovlInstalled) return;
  var B = window.__ovlBridge;
  if (!B) return;
  window.__ovlInstalled = true;

  // The overlay window uses FLAG_NOT_FOCUSABLE so games keep controller input, which makes WebView
  // report document.hasFocus() === false. Video and ad players can throttle or pause when unfocused.
  try {
    Document.prototype.hasFocus = function () { return true; };
    Object.defineProperty(document, 'hidden', { get: function () { return false; }, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
  } catch (_) {}

  var ovlMuted = false;
  var trackedMedia = [];
  function trackMedia(el) {
    if (el && trackedMedia.indexOf(el) < 0) trackedMedia.push(el);
  }
  var muteDesc = null;
  try {
    muteDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted');
    if (muteDesc && muteDesc.get && muteDesc.set) {
      Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
        get: function () {
          return ovlMuted ? true : muteDesc.get.call(this);
        },
        set: function (v) {
          this.__ovlSiteMuted = !!v;
          trackMedia(this);
          muteDesc.set.call(this, ovlMuted ? true : !!v);
        },
        configurable: true
      });
    }
    var origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      trackMedia(this);
      if (ovlMuted) {
        if (muteDesc && muteDesc.set) muteDesc.set.call(this, true);
        else this.muted = true;
      }
      return origPlay.apply(this, arguments);
    };
  } catch (_) {}

  function applyMuteAll() {
    var els = document.querySelectorAll('video, audio');
    for (var i = 0; i < els.length; i++) trackMedia(els[i]);
    for (var j = trackedMedia.length - 1; j >= 0; j--) {
      var m = trackedMedia[j];
      if (!m) { trackedMedia.splice(j, 1); continue; }
      try {
        if (ovlMuted) {
          if (m.__ovlSiteMuted === undefined) m.__ovlSiteMuted = !!(muteDesc && muteDesc.get ? muteDesc.get.call(m) : m.muted);
          if (muteDesc && muteDesc.set) muteDesc.set.call(m, true);
          else m.muted = true;
        } else {
          var want = !!m.__ovlSiteMuted;
          if (muteDesc && muteDesc.set) muteDesc.set.call(m, want);
          else m.muted = want;
        }
      } catch (_) {}
    }
  }
  window.__ovlSetMuted = function (m) {
    ovlMuted = !!m;
    applyMuteAll();
  };
  document.addEventListener('play', function (e) {
    if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
      trackMedia(e.target);
      if (ovlMuted) applyMuteAll();
    }
  }, true);
  document.addEventListener('volumechange', function (e) {
    if (ovlMuted && e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
      try {
        if (muteDesc && muteDesc.set && !muteDesc.get.call(e.target)) muteDesc.set.call(e.target, true);
      } catch (_) {}
    }
  }, true);

  // Brave-style ad blocking & YouTube ad stripping/skipping.
  var ovlBlockAds = true;
  var AD_STYLE =
    'ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ytd-banner-promo-renderer, ytd-statement-banner-renderer,' +
    'ytd-display-ad-renderer, ytd-promoted-sparkles-web-renderer, ytd-promoted-video-renderer, ytd-compact-promoted-video-renderer,' +
    'ytd-player-legacy-desktop-watch-ads-renderer, ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"],' +
    'ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ytm-companion-ad-renderer, ytm-CompanionAdRenderer,' +
    '#masthead-ad, #player-ads, .ytp-ad-overlay-container, .ytp-ad-message-container, .ytp-ad-image-overlay,' +
    'ytd-merch-shelf-renderer, ytm-merch-shelf-renderer, ins.adsbygoogle, [id^="google_ads_"], [id^="div-gpt-ad"] {' +
    ' display: none !important; }';

  function syncAdStyle() {
    var existing = document.getElementById('__ovl-ad-style');
    if (ovlBlockAds) {
      if (!existing) {
        var st = document.createElement('style');
        st.id = '__ovl-ad-style';
        st.textContent = AD_STYLE;
        (document.head || document.documentElement).appendChild(st);
      }
    } else if (existing) {
      existing.remove();
    }
  }

  function stripYtAds(obj) {
    if (!ovlBlockAds || !obj || typeof obj !== 'object') return obj;
    try {
      delete obj.adPlacements;
      delete obj.playerAds;
      delete obj.adSlots;
      delete obj.adBreakHeartbeatParams;
      if (obj.playerResponse && typeof obj.playerResponse === 'object') {
        delete obj.playerResponse.adPlacements;
        delete obj.playerResponse.playerAds;
        delete obj.playerResponse.adSlots;
        delete obj.playerResponse.adBreakHeartbeatParams;
      }
    } catch (_) {}
    return obj;
  }

  if (/youtube\.com|youtu\.be/i.test(location.hostname)) {
    try {
      var origJsonParse = JSON.parse;
      JSON.parse = function () {
        var res = origJsonParse.apply(this, arguments);
        if (ovlBlockAds && res && typeof res === 'object' &&
            (res.adPlacements || res.playerAds || res.adSlots || res.adBreakHeartbeatParams || res.playerResponse)) {
          stripYtAds(res);
        }
        return res;
      };
      if (window.Response && Response.prototype.json) {
        var origResJson = Response.prototype.json;
        Response.prototype.json = function () {
          return origResJson.apply(this, arguments).then(function (data) {
            return stripYtAds(data);
          });
        };
      }
      var ytInit = window.ytInitialPlayerResponse;
      if (ytInit) stripYtAds(ytInit);
      Object.defineProperty(window, 'ytInitialPlayerResponse', {
        get: function () { return ytInit; },
        set: function (v) { ytInit = stripYtAds(v); },
        configurable: true
      });
    } catch (_) {}
  }

  function skipActiveYtAd() {
    if (!ovlBlockAds || !/youtube\.com|youtu\.be/i.test(location.hostname)) return;
    var skipBtns = document.querySelectorAll(
      '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .videoAdUiSkipButton, .ytp-ad-overlay-close-button'
    );
    for (var i = 0; i < skipBtns.length; i++) {
      try { skipBtns[i].click(); } catch (_) {}
    }
    var adPlayer = document.querySelector('.ad-showing, .ad-interrupting');
    if (adPlayer) {
      var v = adPlayer.querySelector('video') || document.querySelector('video.html5-main-video');
      if (v) {
        try {
          if (muteDesc && muteDesc.set) muteDesc.set.call(v, true);
          else v.muted = true;
          if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.1) {
            v.currentTime = v.duration;
          } else {
            v.playbackRate = 16;
          }
        } catch (_) {}
      }
    }
  }

  window.__ovlSetBlockAds = function (on) {
    ovlBlockAds = !!on;
    syncAdStyle();
    if (ovlBlockAds) skipActiveYtAd();
  };
  syncAdStyle();
  ['play', 'playing', 'loadedmetadata', 'timeupdate'].forEach(function (t) {
    document.addEventListener(t, skipActiveYtAd, true);
  });

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
    var t = e.target;
    if (t && t.tagName === 'INPUT' && /^file$/i.test(t.type || '')) {
      var isDir = t.hasAttribute('webkitdirectory') || t.hasAttribute('directory') || !!t.webkitdirectory;
      if (B.fileHint) B.fileHint(isDir, !!t.multiple);
    }
    var el = document.activeElement;
    if (editable(el) && el.contains(t)) B.editStart();
  }, true);

  // Polyfill File System Access API (showOpenFilePicker / showDirectoryPicker) for Android WebView
  // so web apps/emulators that pick files or folders via modern APIs open our file/folder chooser.
  function makeFileHandle(file) {
    return {
      kind: 'file',
      name: file.name,
      getFile: function () { return Promise.resolve(file); },
      queryPermission: function () { return Promise.resolve('granted'); },
      requestPermission: function () { return Promise.resolve('granted'); },
      isSameEntry: function (other) { return Promise.resolve(other && other.name === file.name); }
    };
  }
  function pickViaInput(isDir, multiple) {
    return new Promise(function (resolve, reject) {
      var inp = document.createElement('input');
      inp.type = 'file';
      if (multiple || isDir) inp.multiple = true;
      if (isDir) {
        inp.setAttribute('webkitdirectory', '');
        inp.setAttribute('directory', '');
        inp.webkitdirectory = true;
      }
      inp.style.display = 'none';
      (document.body || document.documentElement).appendChild(inp);
      if (B.fileHint) B.fileHint(!!isDir, !!(multiple || isDir));
      var done = false;
      function cleanup() {
        if (inp.parentNode) inp.parentNode.removeChild(inp);
      }
      inp.addEventListener('change', function () {
        if (done) return;
        done = true;
        var files = Array.prototype.slice.call(inp.files || []);
        cleanup();
        if (!files.length) {
          reject(new DOMException('The user aborted a request.', 'AbortError'));
        } else {
          resolve(files);
        }
      });
      inp.addEventListener('cancel', function () {
        if (done) return;
        done = true;
        cleanup();
        reject(new DOMException('The user aborted a request.', 'AbortError'));
      });
      inp.click();
    });
  }
  if (!window.showOpenFilePicker) {
    window.showOpenFilePicker = function (opts) {
      var multi = !!(opts && opts.multiple);
      return pickViaInput(false, multi).then(function (files) {
        return files.map(makeFileHandle);
      });
    };
  }
  if (!window.showDirectoryPicker) {
    window.showDirectoryPicker = function () {
      return pickViaInput(true, true).then(function (files) {
        var handles = files.map(makeFileHandle);
        var dirHandle = {
          kind: 'directory',
          name: 'Selected Folder',
          queryPermission: function () { return Promise.resolve('granted'); },
          requestPermission: function () { return Promise.resolve('granted'); },
          getFileHandle: function (name) {
            for (var i = 0; i < handles.length; i++) {
              if (handles[i].name === name) return Promise.resolve(handles[i]);
            }
            return Promise.reject(new DOMException('File not found: ' + name, 'NotFoundError'));
          },
          getDirectoryHandle: function () {
            return Promise.resolve(dirHandle);
          },
          values: function () {
            var idx = 0;
            return {
              next: function () {
                if (idx < handles.length) return Promise.resolve({ value: handles[idx++], done: false });
                return Promise.resolve({ value: undefined, done: true });
              },
              [Symbol.asyncIterator]: function () { return this; }
            };
          },
          entries: function () {
            var idx = 0;
            return {
              next: function () {
                if (idx < handles.length) {
                  var h = handles[idx++];
                  return Promise.resolve({ value: [h.name, h], done: false });
                }
                return Promise.resolve({ value: undefined, done: true });
              },
              [Symbol.asyncIterator]: function () { return this; }
            };
          },
          keys: function () {
            var idx = 0;
            return {
              next: function () {
                if (idx < handles.length) return Promise.resolve({ value: handles[idx++].name, done: false });
                return Promise.resolve({ value: undefined, done: true });
              },
              [Symbol.asyncIterator]: function () { return this; }
            };
          }
        };
        dirHandle[Symbol.asyncIterator] = dirHandle.entries;
        return dirHandle;
      });
    };
  }
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

  // The main video: prefer a currently playing video (e.g. an active ad over a paused content video),
  // then one inside our active player, then the largest by resolution.
  function mainVideo() {
    var vids = document.getElementsByTagName('video');
    if (vids.length === 1) {
      var only = vids[0];
      return (only.videoWidth && only.videoHeight) ? only : null;
    }
    var best = null, bestScore = -1;
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (!v.videoWidth || !v.videoHeight) continue;
      var score = Math.max(1, v.videoWidth * v.videoHeight);
      if (v.closest('[data-ovl-player]')) score += 1e9;
      if (!v.paused && !v.ended) score += 1e12;
      if (score > bestScore) { bestScore = score; best = v; }
    }
    return best;
  }

  function syncFill(p, v) {
    if (!p || !v || p === v || !p.contains(v)) return;
    for (var f = v.parentElement; f && f !== p; f = f.parentElement) {
      if (!f.hasAttribute('data-ovl-fill')) f.setAttribute('data-ovl-fill', '');
    }
    if (!v.hasAttribute('data-ovl-vid')) v.setAttribute('data-ovl-vid', '');
  }

  // Tell the app about it: "width,height,playing". Sent only when it changes.
  var lastReport = '', lastVid = null;
  function report() {
    var v = mainVideo(), s = '0,0,0';
    if (v) {
      s = [v.videoWidth, v.videoHeight, (!v.paused && !v.ended) ? 1 : 0].join(',');
    }
    if (document.documentElement.hasAttribute('data-ovl-video') && v) {
      // Sites can swap their player or video element out; follow it without tearing down video mode.
      var p = document.querySelector('[data-ovl-player]');
      if (!p || (p !== v && !p.contains(v))) {
        window.__ovlVideoMode(true);
      } else if (v !== lastVid || s !== lastReport) {
        syncFill(p, v);
      }
    }
    lastVid = v;
    if (s !== lastReport) { lastReport = s; B.video(s); }
  }
  ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'durationchange', 'resize', 'emptied'].forEach(function (t) {
    document.addEventListener(t, report, true);
  });
  setInterval(function () {
    if (lastVid && lastVid.isConnected && !lastVid.paused && !lastVid.ended &&
        lastReport === [lastVid.videoWidth, lastVid.videoHeight, 1].join(',')) return;
    report();
  }, 1500);

  // Video mode: the player fills the page and sibling branches are hidden. Uses data-ovl-* attributes
  // instead of CSS classes because players like YouTube overwrite element.className during ads and
  // fullscreen updates, which would otherwise strip the class and hide/pause the video every second.
  var STYLE =
    'html[data-ovl-video], html[data-ovl-video] body { overflow: hidden !important; background: #000 !important; }' +
    'html[data-ovl-video] [data-ovl-anc] > :not([data-ovl-anc]):not([data-ovl-player]) {' +
    ' visibility: hidden !important; pointer-events: none !important; }' +
    'html[data-ovl-video] [data-ovl-anc] { transform: none !important; filter: none !important; perspective: none !important;' +
    ' contain: none !important; will-change: auto !important; backdrop-filter: none !important; }' +
    'html[data-ovl-video] [data-ovl-player] { position: fixed !important; left: 0 !important; top: 0 !important;' +
    ' right: 0 !important; bottom: 0 !important; width: 100vw !important; height: 100vh !important;' +
    ' max-width: none !important; max-height: none !important; min-width: 0 !important; min-height: 0 !important;' +
    ' margin: 0 !important; padding: 0 !important; transform: none !important; z-index: 2147483647 !important;' +
    ' background: #000 !important; visibility: visible !important; }' +
    'html[data-ovl-video] video[data-ovl-player], html[data-ovl-video] [data-ovl-player] video { object-fit: contain !important; }' +
    // Boxes between the player and the video, and the video itself, are often sized by the site's own
    // script (YouTube hard-codes pixel sizes); stretch them to the player so the picture fills it.
    'html[data-ovl-video] [data-ovl-fill], html[data-ovl-video] [data-ovl-vid],' +
    'html[data-ovl-video] [data-ovl-player] .html5-video-container,' +
    'html[data-ovl-video] [data-ovl-player] video.html5-main-video { position: absolute !important; left: 0 !important;' +
    ' top: 0 !important; width: 100% !important; height: 100% !important; max-width: none !important;' +
    ' max-height: none !important; margin: 0 !important; padding: 0 !important; transform: none !important; }' +
    'html[data-ovl-video] #cinematics, html[data-ovl-video] #player-cinematics-container, html[data-ovl-video] ytm-cinematics {' +
    ' display: none !important; }';
  var YT_PLAYERS = ['#player-container-id', '#player-container', 'ytm-player', '#movie_player'];

  window.__ovlVideoMode = function (on) {
    var html = document.documentElement;
    var oldP = document.querySelector('[data-ovl-player]');
    var marked = document.querySelectorAll('[data-ovl-player], [data-ovl-anc], [data-ovl-fill], [data-ovl-vid]');
    for (var i = 0; i < marked.length; i++) {
      marked[i].removeAttribute('data-ovl-player');
      marked[i].removeAttribute('data-ovl-anc');
      marked[i].removeAttribute('data-ovl-fill');
      marked[i].removeAttribute('data-ovl-vid');
    }
    if (window.__ovlCtl) { window.__ovlCtl.v.controls = window.__ovlCtl.c; window.__ovlCtl = null; }
    if (!on) {
      html.removeAttribute('data-ovl-video');
      window.dispatchEvent(new Event('resize'));
      return false;
    }
    var v = mainVideo();
    if (!v) {
      html.removeAttribute('data-ovl-video');
      return false;
    }
    // YouTube: its player box, which holds the controls too.
    var p = null;
    for (var k = 0; k < YT_PLAYERS.length && !p; k++) {
      var c = v.closest(YT_PLAYERS[k]) || document.querySelector(YT_PLAYERS[k]);
      if (c && (c === v || c.contains(v))) p = c;
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
    p.setAttribute('data-ovl-player', '');
    for (var a = p.parentElement; a && a !== html; a = a.parentElement) a.setAttribute('data-ovl-anc', '');
    syncFill(p, v);
    if (!document.getElementById('__ovl-style')) {
      var st = document.createElement('style');
      st.id = '__ovl-style';
      st.textContent = STYLE;
      (document.head || html).appendChild(st);
    }
    var wasVideo = html.hasAttribute('data-ovl-video');
    html.setAttribute('data-ovl-video', '');
    if (!wasVideo || p !== oldP) window.dispatchEvent(new Event('resize'));
    return true;
  };

  // Handle HTML5 fullscreen inside the existing WebView rather than letting Chromium create a separate
  // FullScreenView surface (onShowCustomView), which stutters inside non-focusable overlay windows.
  var fsEl = null;
  function fireFsChange(target) {
    var t = (target && target.isConnected) ? target : document;
    t.dispatchEvent(new Event('fullscreenchange', { bubbles: true }));
    t.dispatchEvent(new Event('webkitfullscreenchange', { bubbles: true }));
  }
  function reqFs() {
    fsEl = this === document.documentElement || this === document.body ? ( document.querySelector('[data-ovl-player]') || this ) : this;
    window.__ovlVideoMode(true);
    B.fullscreen(true);
    fireFsChange(fsEl);
    return Promise.resolve();
  }
  function exitFs() {
    var old = fsEl;
    fsEl = null;
    B.fullscreen(false);
    fireFsChange(old);
    return Promise.resolve();
  }
  window.__ovlExitFullscreen = function () {
    if (fsEl) exitFs();
  };
  try {
    Element.prototype.requestFullscreen = reqFs;
    Element.prototype.webkitRequestFullscreen = reqFs;
    Element.prototype.webkitRequestFullScreen = reqFs;
    Document.prototype.exitFullscreen = exitFs;
    Document.prototype.webkitExitFullscreen = exitFs;
    Document.prototype.webkitCancelFullScreen = exitFs;
    Object.defineProperty(Document.prototype, 'fullscreenElement', { get: function () { return fsEl; }, configurable: true });
    Object.defineProperty(Document.prototype, 'webkitFullscreenElement', { get: function () { return fsEl; }, configurable: true });
    Object.defineProperty(Document.prototype, 'webkitCurrentFullScreenElement', { get: function () { return fsEl; }, configurable: true });
    Object.defineProperty(Document.prototype, 'fullscreenEnabled', { get: function () { return true; }, configurable: true });
    Object.defineProperty(Document.prototype, 'webkitFullscreenEnabled', { get: function () { return true; }, configurable: true });
    Object.defineProperty(Document.prototype, 'fullscreen', { get: function () { return !!fsEl; }, configurable: true });
    Object.defineProperty(Document.prototype, 'webkitIsFullScreen', { get: function () { return !!fsEl; }, configurable: true });
  } catch (_) {}

  report();
})();
"""
