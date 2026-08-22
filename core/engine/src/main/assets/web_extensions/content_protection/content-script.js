'use strict';

(function() {
  var DATA_PROTECTED = 'data-shellify-protected';
  var DATA_ORIGINAL_STYLE = 'data-shellify-original-style';
  var DATA_HAD_STYLE = 'data-shellify-had-style';
  var DATA_HOVER_HOOK = 'data-shellify-hover-hook';
  var DATA_REVEALED = 'data-shellify-revealed';
  var DATA_REGIONAL = 'data-shellify-regional';
  var SETTINGS_RETRY_DELAYS_MS = [250, 500, 1000, 2000, 4000, 8000];
  var observer;
  var scanTimer;
  var config;
  var startupStyle = document.createElement('style');
  startupStyle.textContent = 'img,video{filter:blur(20px) grayscale(1)!important;}';
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

  function score(element) {
    var value = text(element);
    var words = ['adult', 'explicit', 'hentai', 'naked', 'nude', 'nsfw', 'porn', 'sex', 'xxx', 'erotic', 'lingerie', 'breast', 'genital'];
    var hits = words.filter(function(word) { return value.indexOf(word) >= 0; }).length;
    var keywordScore = Math.min(1, hits * 0.35);
    var gender = genderSignals(value);
    var genderScore = (gender.male && config.blurMale) || (gender.female && config.blurFemale) ? 1 : 0;
    return isMaximumStrictness() ? 1 : Math.max(keywordScore, genderScore, visualScore(element));
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
    var canvas = document.createElement('canvas');
    canvas.width = 24;
    canvas.height = 24;
    var context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) return 0;
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
      return visible ? skin / visible : 0;
    } catch (_) {
      return 0;
    }
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
  }

  function protect(element, pending) {
    var smart = smartDetector();
    if (smart && smart.clear) smart.clear(element);
    remember(element);
    var filter = config.blurAmount > 0 ? 'blur(' + config.blurAmount + 'px)' : 'none';
    if (config.grayscale) filter += ' grayscale(1)';
    element.style.setProperty('filter', filter, 'important');
    element.setAttribute(DATA_PROTECTED, '1');
    if (pending) element.setAttribute('data-shellify-pending', '1');
    else element.removeAttribute('data-shellify-pending');
  }

  function finishSmartProcess(element, result) {
    if (!result || result.pending || !document.documentElement.contains(element)) return;
    if (!shouldProcess(element) || hostMatches()) {
      restore(element);
      return;
    }
    var smart = smartDetector();
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
       element.removeAttribute(DATA_REVEALED);
       element.removeAttribute('data-shellify-pending');
       return;
    }
    if (smart && smart.clear) smart.clear(element);
    element.removeAttribute(DATA_REGIONAL);
     var metadataBlocked = !result.ready && score(element) >= 0.72 - config.strictness * 0.38;
    var unknownAtStrictness = result.unknownGender && config.strictness >= 0.75;
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
        else element.style.setProperty('filter', 'none', 'important');
      }
    }, true);
    element.addEventListener('pointerleave', function() {
      if (!element.hasAttribute(DATA_REVEALED)) return;
      element.removeAttribute(DATA_REVEALED);
      var smart = smartDetector();
      if (element.hasAttribute(DATA_REGIONAL) && smart && smart.setRevealed) smart.setRevealed(element, false);
      else if (element.hasAttribute(DATA_PROTECTED)) protect(element, false);
    }, true);
  }

  function process(element) {
    if (!shouldProcess(element) || hostMatches()) {
      restore(element);
      return;
    }
    addHover(element);
    var loading = (kind(element) === 'img' && !element.complete) ||
      (kind(element) === 'video' && element.readyState < 2);
    var smart = smartDetector();
    if (smart && smart.analyze) {
      if (config.startupBlur) protect(element, true);
      smart.analyze(element, config, function(result) {
        finishSmartProcess(element, result);
      });
      return;
    }
    if (loading && config.startupBlur) protect(element, true);
    if (score(element) >= 0.72 - config.strictness * 0.38) protect(element, false);
    else if (!(loading && config.startupBlur)) restore(element);
  }

  function scan(root) {
    if (!config.enabled || hostMatches()) {
      document.querySelectorAll('img[data-shellify-protected],video[data-shellify-protected]').forEach(restore);
      return;
    }
    var elements = [];
    if (root && root.nodeType === 1 && (kind(root) === 'img' || kind(root) === 'video')) elements.push(root);
    (root && root.querySelectorAll ? root : document).querySelectorAll('img,video')
      .forEach(function(element) { elements.push(element); });
    elements.forEach(process);
  }

  function start(value) {
    config = normalize(value);
    startupStyle.remove();
    scan(document);
    if (!observer && document.documentElement) {
      observer = new MutationObserver(function(records) {
        records.forEach(function(record) {
          record.addedNodes.forEach(function(node) { scan(node); });
        });
      });
      observer.observe(document.documentElement, { childList: true, subtree: true });
    }
    if (!scanTimer) scanTimer = setInterval(function() { scan(document); }, 1500);
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
