'use strict';

(function() {
  var DATA_PROTECTED = 'data-shellify-protected';
  var DATA_ORIGINAL_STYLE = 'data-shellify-original-style';
  var DATA_HAD_STYLE = 'data-shellify-had-style';
  var DATA_HOVER_HOOK = 'data-shellify-hover-hook';
  var DATA_REVEALED = 'data-shellify-revealed';
  var DATA_REGIONAL = 'data-shellify-regional';
  var DATA_PENDING = 'data-shellify-pending';
  var SETTINGS_RETRY_DELAYS_MS = [250, 500, 1000, 2000, 4000, 8000];
  var observer;
  var visibilityObserver;
  var scanTimer;
  var scanFlushTimer;
  var pendingScanRoots = [];
  var mediaElements = [];
  var initialized = false;
  var config;
  var visualStates = new WeakMap();
  var scoreStates = new WeakMap();
  var startupStyle = document.createElement('style');
  startupStyle.textContent = 'img{filter:blur(20px) grayscale(1)!important;}';
  (document.documentElement || document).appendChild(startupStyle);

  function isMaximumStrictness() {
    return config.strictness >= 0.999;
  }

  function normalize(value) {
    value = value || {};
    return {
      enabled: value.enabled !== false,
      blurImages: value.blurImages !== false,
      blurVideos: value.blurVideos !== false,
      blurAmount: Math.max(0, Math.min(80, Number(value.blurAmount) || 0)),
      grayscale: value.grayscale !== false,
      strictness: Math.max(0, Math.min(1, Number(value.strictness) || 0)),
      blurMale: value.blurMale === true,
      blurFemale: value.blurFemale !== false,
      startupBlur: value.startupBlur !== false,
      hoverReveal: value.hoverReveal !== false,
      whitelist: Array.isArray(value.whitelist) ? value.whitelist : []
    };
  }

  function hostMatches() {
    var host = String(location.hostname || '').toLowerCase();
    return config.whitelist.some(function(rule) {
      var value = String(rule || '').trim().toLowerCase()
        .replace(/^https?:\/\//, '').split('/')[0].split(':')[0];
      return value && (host === value || host.endsWith('.' + value));
    });
  }

  function kind(element) {
    return element.tagName.toLowerCase();
  }

  function normalizedText(value) {
    var text = String(value || '');
    try {
      text = decodeURIComponent(text);
    } catch (_) {
      // Keep the original value when a page contains malformed URL encoding.
    }
    return text.toLowerCase().replace(/[-_./?=&#:]+/g, ' ');
  }

  function text(element) {
    var values = [
      element.alt,
      element.title,
      element.getAttribute('aria-label'),
      element.getAttribute('data-alt'),
      element.getAttribute('data-caption'),
      element.getAttribute('data-src'),
      element.getAttribute('data-srcset'),
      element.currentSrc,
      element.src,
      element.srcset,
      element.poster,
      element.getAttribute('data-poster')
    ];
    if (element.querySelectorAll) {
      element.querySelectorAll('source').forEach(function(source) {
        values.push(source.currentSrc, source.src, source.srcset, source.getAttribute('data-src'));
      });
    }
    var picture = element.closest && element.closest('picture');
    if (picture && picture.querySelectorAll) {
      picture.querySelectorAll('source').forEach(function(source) {
        values.push(source.currentSrc, source.src, source.srcset, source.getAttribute('data-src'), source.getAttribute('data-srcset'));
      });
    }
    var context = element.closest && element.closest('figure,[role="img"]');
    if (context) values.push(context.textContent);
    return values.filter(Boolean).map(normalizedText).join(' ');
  }

  function isVisible(element) {
    if (document.hidden === true) return false;
    if (!element || typeof element.getBoundingClientRect !== 'function') return true;
    var rect = element.getBoundingClientRect();
    if (!rect || !rect.width || !rect.height) return false;
    var documentElement = document.documentElement || {};
    var viewportWidth = Number(window.innerWidth || documentElement.clientWidth || 0);
    var viewportHeight = Number(window.innerHeight || documentElement.clientHeight || 0);
    if (!viewportWidth || !viewportHeight) return true;
    return rect.right > 0 && rect.bottom > 0 && rect.left < viewportWidth && rect.top < viewportHeight;
  }

  function isVideoActive(element) {
    return kind(element) !== 'video' ||
      (element.paused !== true && element.ended !== true && isVisible(element));
  }

  function score(element) {
    var metadataSignature = [
      element.currentSrc,
      element.src,
      element.poster,
      element.alt,
      element.title,
      element.getAttribute('data-src'),
      element.getAttribute('data-poster')
    ].join('|');
    var cached = scoreStates.get(element);
    var maxAge = kind(element) === 'video' ? 5000 : 60000;
    var maximum = isMaximumStrictness();
    if (!cached || cached.signature !== metadataSignature || cached.maximum !== maximum || Date.now() - cached.completedAt >= maxAge) {
      var value = text(element);
      var words = ['adult', 'explicit', 'hentai', 'naked', 'nude', 'nsfw', 'porn', 'sex', 'xxx', 'erotic', 'lingerie', 'breast', 'genital'];
      var hits = words.filter(function(word) { return value.indexOf(word) >= 0; }).length;
      var gender = genderSignals(value);
      cached = {
        signature: metadataSignature,
        completedAt: Date.now(),
        maximum: maximum,
        keywordScore: Math.min(1, hits * 0.35),
        male: gender.male,
        female: gender.female,
        visual: maximum ? 0 : visualScore(element)
      };
      scoreStates.set(element, cached);
    }
    var genderScore = (cached.male && config.blurMale) || (cached.female && config.blurFemale) ? 1 : 0;
    return maximum ? 1 : Math.max(cached.keywordScore, genderScore, cached.visual);
  }

  function smartDetector() {
    return window['__shellifySmartDetection'];
  }

  function genderSignals(value) {
    return {
      male: /(^|[^a-z])(boy|male|man|men|guy|father|husband)([^a-z]|$)/.test(value),
      female: /(^|[^a-z])(femme|girl|female|woman|women|lady|mother|wife|sister|daughter|bride|actress)([^a-z]|$)/.test(value)
    };
  }

  function visualScore(element) {
    var width = element.videoWidth || element.naturalWidth || element.width;
    var height = element.videoHeight || element.naturalHeight || element.height;
    if (!width || !height) return 0;
    if (kind(element) === 'video' && !isVideoActive(element)) return 0;
    var signature = [width, height, Math.floor(Number(element.currentTime || 0))].join('|');
    var cached = visualStates.get(element);
    if (cached && cached.signature === signature && Date.now() - cached.completedAt < (kind(element) === 'video' ? 2000 : 60000)) {
      return cached.value;
    }
    var canvas = cached && cached.canvas;
    if (!canvas) {
      canvas = document.createElement('canvas');
      cached = { canvas: canvas, context: null, signature: '', completedAt: 0, value: 0 };
      visualStates.set(element, cached);
    }
    canvas.width = 24;
    canvas.height = 24;
    var context = cached.context || canvas.getContext('2d', { willReadFrequently: true });
    if (!context) return 0;
    cached.context = context;
    var value = 0;
    try {
      context.drawImage(element, 0, 0, 24, 24);
      var pixels = context.getImageData(0, 0, 24, 24).data;
      var skin = 0;
      var visible = 0;
      for (var index = 0; index < pixels.length; index += 16) {
        if (pixels[index + 3] < 32) continue;
        visible++;
        var red = pixels[index];
        var green = pixels[index + 1];
        var blue = pixels[index + 2];
        if (red > 60 && green > 30 && blue > 15 && red > green * 1.08 && green > blue * 1.15 && red - blue > 15) skin++;
      }
      value = visible ? skin / visible : 0;
    } catch (_) {
      value = 0;
    }
    cached.signature = signature;
    cached.completedAt = Date.now();
    cached.value = value;
    return value;
  }

  function shouldProcess(element) {
    return (kind(element) === 'img' && config.blurImages) ||
      (kind(element) === 'video' && config.blurVideos);
  }

  function remember(element) {
    if (element.hasAttribute(DATA_ORIGINAL_STYLE)) return;
    var style = element.getAttribute('style');
    element.setAttribute(DATA_ORIGINAL_STYLE, style || '');
    element.setAttribute(DATA_HAD_STYLE, style === null ? '0' : '1');
  }

  function restore(element) {
    var smart = smartDetector();
    if (smart && smart.clear) smart.clear(element);
    if (element.hasAttribute(DATA_ORIGINAL_STYLE)) {
      if (element.getAttribute(DATA_HAD_STYLE) === '1') element.setAttribute('style', element.getAttribute(DATA_ORIGINAL_STYLE) || '');
      else element.removeAttribute('style');
      element.removeAttribute(DATA_ORIGINAL_STYLE);
      element.removeAttribute(DATA_HAD_STYLE);
    }
    element.removeAttribute(DATA_PROTECTED);
    element.removeAttribute(DATA_REVEALED);
    element.removeAttribute(DATA_REGIONAL);
    element.removeAttribute(DATA_PENDING);
  }

  function protect(element, pending) {
    if (kind(element) === 'video') {
      element.removeAttribute(DATA_PENDING);
      element.removeAttribute(DATA_PROTECTED);
      element.removeAttribute(DATA_REVEALED);
      return;
    }
    var smart = smartDetector();
    if (smart && smart.clear) smart.clear(element);
    remember(element);
    var filter = config.blurAmount > 0 ? 'blur(' + config.blurAmount + 'px)' : 'none';
    if (config.grayscale) filter += ' grayscale(1)';
    element.style.setProperty('filter', filter, 'important');
    element.setAttribute(DATA_PROTECTED, '1');
    if (pending) element.setAttribute(DATA_PENDING, '1');
    else element.removeAttribute(DATA_PENDING);
  }

  function finishSmartProcess(element, result) {
    if (!result || result.pending || !document.documentElement.contains(element)) return;
    if (result.skipped) {
      element.removeAttribute(DATA_PENDING);
      return;
    }
    if (!shouldProcess(element) || hostMatches()) {
      restore(element);
      return;
    }
    if (result.error && kind(element) === 'video' && element.hasAttribute(DATA_REGIONAL)) {
      element.removeAttribute(DATA_PENDING);
      return;
    }
    var smart = smartDetector();
    var wasRevealed = element.hasAttribute(DATA_REVEALED);
    if (result.ready && result.regions && result.regions.length && smart && smart.apply &&
        smart.apply(element, config, result)) {
      if (element.hasAttribute(DATA_ORIGINAL_STYLE)) {
        if (element.getAttribute(DATA_HAD_STYLE) === '1') {
          element.setAttribute('style', element.getAttribute(DATA_ORIGINAL_STYLE) || '');
        } else {
          element.removeAttribute('style');
        }
        element.removeAttribute(DATA_ORIGINAL_STYLE);
        element.removeAttribute(DATA_HAD_STYLE);
      }
      element.setAttribute(DATA_PROTECTED, '1');
      element.setAttribute(DATA_REGIONAL, '1');
      if (wasRevealed && smart.setRevealed) smart.setRevealed(element, true);
      else if (!wasRevealed) element.removeAttribute(DATA_REVEALED);
      element.removeAttribute(DATA_PENDING);
      return;
    }
    if (smart && smart.clear) smart.clear(element);
    element.removeAttribute(DATA_REGIONAL);
    var metadataBlocked = kind(element) === 'img' && !result.ready && score(element) >= 0.72 - config.strictness * 0.38;
    var unknownAtStrictness = kind(element) === 'img' && result.unknownGender && config.strictness >= 0.75;
    if (metadataBlocked || unknownAtStrictness) protect(element, false);
    else restore(element);
  }

  function addHover(element) {
    if (element.hasAttribute(DATA_HOVER_HOOK)) return;
    element.setAttribute(DATA_HOVER_HOOK, '1');
    element.addEventListener('pointerenter', function() {
      if (config.hoverReveal && element.hasAttribute(DATA_PROTECTED)) {
        element.setAttribute(DATA_REVEALED, '1');
        var smart = smartDetector();
        if (element.hasAttribute(DATA_REGIONAL) && smart && smart.setRevealed) smart.setRevealed(element, true);
        else if (kind(element) === 'img') element.style.setProperty('filter', 'none', 'important');
      }
    }, true);
    element.addEventListener('pointerleave', function() {
      if (!element.hasAttribute(DATA_REVEALED)) return;
      element.removeAttribute(DATA_REVEALED);
      var smart = smartDetector();
      if (element.hasAttribute(DATA_REGIONAL) && smart && smart.setRevealed) smart.setRevealed(element, false);
      else if (element.hasAttribute(DATA_PROTECTED) && kind(element) === 'img') protect(element, false);
    }, true);
  }

  function process(element) {
    if (!shouldProcess(element) || hostMatches()) {
      restore(element);
      return;
    }
    if (kind(element) === 'img' && !isVisible(element)) {
      element.removeAttribute(DATA_PENDING);
      if (!element.hasAttribute(DATA_REGIONAL)) restore(element);
      return;
    }
    if (kind(element) === 'video' && !isVideoActive(element)) {
      element.removeAttribute(DATA_PENDING);
      return;
    }
    addHover(element);
    var loading = (kind(element) === 'img' && !element.complete) ||
      (kind(element) === 'video' && element.readyState < 2);
    var smart = smartDetector();
    if (smart && smart.analyze) {
      if (kind(element) === 'img' && config.startupBlur && !element.hasAttribute(DATA_PROTECTED)) protect(element, true);
      else element.removeAttribute(DATA_PENDING);
      smart.analyze(element, config, function(result) {
        finishSmartProcess(element, result);
      });
      return;
    }
    if (kind(element) === 'img' && loading && config.startupBlur) protect(element, true);
    if (kind(element) === 'img' && score(element) >= 0.72 - config.strictness * 0.38) protect(element, false);
    else if (kind(element) === 'img' && !(loading && config.startupBlur)) restore(element);
    else if (kind(element) === 'video') restore(element);
  }

  function isMedia(element) {
    return element && element.nodeType === 1 && (kind(element) === 'img' || kind(element) === 'video');
  }

  function rememberMedia(element) {
    if (mediaElements.indexOf(element) < 0) mediaElements.push(element);
    if (visibilityObserver) visibilityObserver.observe(element);
  }

  function collectMedia(root) {
    var elements = [];
    if (!root || (root.nodeType !== 1 && root.nodeType !== 9 && root.nodeType !== 11)) return elements;
    if (isMedia(root)) elements.push(root);
    if (root.querySelectorAll) {
      root.querySelectorAll('img,video').forEach(function(element) { elements.push(element); });
    }
    return elements;
  }

  function pruneMedia() {
    var active = [];
    mediaElements.forEach(function(element) {
      if (document.documentElement && document.documentElement.contains(element)) active.push(element);
      else {
        if (visibilityObserver) visibilityObserver.unobserve(element);
        restore(element);
      }
    });
    mediaElements = active;
  }

  function scanTracked() {
    pruneMedia();
    if (!config.enabled || hostMatches()) {
      mediaElements.forEach(restore);
      return;
    }
    mediaElements.forEach(process);
  }

  function scan(root) {
    var elements = collectMedia(root);
    elements.forEach(rememberMedia);
    if (!config.enabled || hostMatches()) {
      mediaElements.forEach(restore);
      return;
    }
    elements.forEach(process);
  }

  function flushScans() {
    scanFlushTimer = null;
    var roots = pendingScanRoots.slice();
    pendingScanRoots.length = 0;
    roots.forEach(scan);
  }

  function queueScan(root) {
    if (!root || (root.nodeType !== 1 && root.nodeType !== 11)) return;
    if (pendingScanRoots.indexOf(root) < 0) pendingScanRoots.push(root);
    if (!scanFlushTimer) scanFlushTimer = setTimeout(flushScans, 0);
  }

  function ensureVisibilityObserver() {
    if (visibilityObserver || typeof IntersectionObserver !== 'function') return;
    visibilityObserver = new IntersectionObserver(function(entries) {
      entries.forEach(function(entry) {
        if (entry.isIntersecting) process(entry.target);
      });
    });
  }

  function start(value) {
    config = normalize(value);
    startupStyle.remove();
    ensureVisibilityObserver();
    if (!initialized) {
      scan(document);
      initialized = true;
    } else {
      scanTracked();
    }
    if (!observer && document.documentElement) {
      observer = new MutationObserver(function(records) {
        records.forEach(function(record) {
          record.addedNodes.forEach(function(node) { queueScan(node); });
        });
      });
      observer.observe(document.documentElement, { childList: true, subtree: true });
    }
    if (!scanTimer) scanTimer = setInterval(scanTracked, 2000);
  }

  document.addEventListener('shellifyContentProtectionUpdate', function(event) {
    if (event && event.detail) start(event.detail);
  }, true);

  function requestSettings(attempt) {
    browser.runtime.sendMessage({ type: 'settings' }).then(start).catch(function() {
      if (attempt < SETTINGS_RETRY_DELAYS_MS.length) {
        setTimeout(function() { requestSettings(attempt + 1); }, SETTINGS_RETRY_DELAYS_MS[attempt]);
      } else {
        start({ enabled: false });
      }
    });
  }

  requestSettings(0);
})();
