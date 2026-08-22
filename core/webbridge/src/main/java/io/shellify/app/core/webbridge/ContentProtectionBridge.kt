package io.shellify.app.core.webbridge

import io.shellify.app.domain.model.ContentProtectionSettings
import org.json.JSONArray
import org.json.JSONObject

/** Builds the local-only media protection script used by both browser engines. */
object ContentProtectionBridge {

    fun buildDocumentStartScript(settings: ContentProtectionSettings): String =
        SCRIPT_TEMPLATE.replace("__SHELLIFY_CONFIG__", settingsJson(settings))

    fun buildUpdateScript(settings: ContentProtectionSettings): String {
        val json = settingsJson(settings)
        return "(function(){var value=$json;var api=window['__shellifyContentProtection'];" +
            "if(api){api.update(value);}else{document.dispatchEvent(new CustomEvent('shellifyContentProtectionUpdate',{detail:value}));}})();"
    }

    private fun settingsJson(settings: ContentProtectionSettings): String = JSONObject().apply {
        put("enabled", settings.enabled)
        put("blurImages", settings.blurImages)
        put("blurVideos", settings.blurVideos)
        put("blurAmount", settings.blurAmount)
        put("grayscale", settings.grayscale)
        put("strictness", settings.strictness)
        put("blurMale", settings.blurMale)
        put("blurFemale", settings.blurFemale)
        put("startupBlur", settings.startupBlur)
        put("hoverReveal", settings.hoverReveal)
        put("whitelist", JSONArray(settings.whitelist))
    }.toString()

    private const val SCRIPT_TEMPLATE = """
(function() {
  'use strict';

  var KEY = '__shellifyContentProtection';
  var DATA_PROTECTED = 'data-shellify-protected';
  var DATA_PENDING = 'data-shellify-pending';
  var DATA_REGIONAL = 'data-shellify-regional';
  var DATA_ORIGINAL_STYLE = 'data-shellify-original-style';
  var DATA_HOVER_HOOK = 'data-shellify-hover-hook';
  var DATA_REVEALED = 'data-shellify-revealed';
  var currentConfig = __SHELLIFY_CONFIG__;
  var scanTimer = null;
  var observer = null;

  function isMaximumStrictness() {
    return currentConfig.strictness >= 0.999;
  }

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, Number(value) || 0));
  }

  function normalizeConfig(value) {
    value = value || {};
    return {
      enabled: value.enabled !== false,
      blurImages: value.blurImages !== false,
      blurVideos: value.blurVideos !== false,
      blurAmount: clamp(value.blurAmount, 0, 80),
      grayscale: value.grayscale !== false,
      strictness: clamp(value.strictness, 0, 1),
      blurMale: value.blurMale === true,
      blurFemale: value.blurFemale !== false,
      startupBlur: value.startupBlur !== false,
      hoverReveal: value.hoverReveal !== false,
      whitelist: Array.isArray(value.whitelist) ? value.whitelist : []
    };
  }

  function normalizedHost(value) {
    return String(value || '').trim().toLowerCase()
      .replace(/^https?:\/\//, '').split('/')[0].split(':')[0];
  }

  function isWhitelisted() {
    var host = String(location.hostname || '').toLowerCase();
    return currentConfig.whitelist.some(function(rule) {
      var normalized = normalizedHost(rule);
      return normalized && (host === normalized || host.endsWith('.' + normalized));
    });
  }

  function mediaKind(element) {
    return element && element.tagName && element.tagName.toLowerCase();
  }

  function shouldProcess(element) {
    var kind = mediaKind(element);
    return (kind === 'img' && currentConfig.blurImages) ||
      (kind === 'video' && currentConfig.blurVideos);
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

  function sourceText(element) {
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

  function keywordScore(text) {
    var words = [
      'adult', 'explicit', 'hentai', 'naked', 'nude', 'nsfw', 'porn', 'sex',
      'xxx', 'erotic', 'lingerie', 'breast', 'genital', 'onlyfans'
    ];
    var hits = words.filter(function(word) { return text.indexOf(word) >= 0; }).length;
    return Math.min(1, hits * 0.35);
  }

  function genderSignals(text) {
    return {
      male: /(^|[^a-z])(boy|male|man|men|guy|father|husband)([^a-z]|$)/.test(text),
      female: /(^|[^a-z])(femme|girl|female|woman|women|lady|mother|wife|sister|daughter|bride|actress)([^a-z]|$)/.test(text)
    };
  }

  function visualSkinScore(element) {
    var canvas = document.createElement('canvas');
    var context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) return 0;
    var width = element.videoWidth || element.naturalWidth || element.width;
    var height = element.videoHeight || element.naturalHeight || element.height;
    if (!width || !height) return 0;
    canvas.width = 32;
    canvas.height = 32;
    try {
      context.drawImage(element, 0, 0, 32, 32);
      var pixels = context.getImageData(0, 0, 32, 32).data;
      var skin = 0;
      var visible = 0;
      for (var index = 0; index < pixels.length; index += 16) {
        var red = pixels[index];
        var green = pixels[index + 1];
        var blue = pixels[index + 2];
        if (pixels[index + 3] < 32) continue;
        visible++;
        var warm = red > 60 && green > 30 && blue > 15 && red > green * 1.08 && green > blue * 1.15;
        var range = red - blue > 15 && red - green < 120;
        if (warm && range) skin++;
      }
      return visible ? skin / visible : 0;
    } catch (_) {
      return 0;
    }
  }

  function classify(element) {
    var text = sourceText(element);
    var textScore = keywordScore(text);
    var visualScore = visualSkinScore(element);
    var threshold = 0.72 - currentConfig.strictness * 0.38;
    var gender = genderSignals(text);
    var genderBlocked = (gender.male && currentConfig.blurMale) ||
      (gender.female && currentConfig.blurFemale);
    return {
      blur: isMaximumStrictness() || textScore >= 0.35 || visualScore >= threshold || genderBlocked,
      score: Math.max(textScore, visualScore),
      gender: gender.female ? 'female' : (gender.male ? 'male' : '')
    };
  }

  function smartDetector() {
    return window['__shellifySmartDetection'];
  }

  function rememberOriginalStyle(element) {
    if (element.hasAttribute(DATA_ORIGINAL_STYLE)) return;
    var style = element.getAttribute('style');
    element.setAttribute(DATA_ORIGINAL_STYLE, style === null ? '' : style);
    element.setAttribute('data-shellify-had-style', style === null ? '0' : '1');
  }

  function restoreStyle(element) {
    if (!element.hasAttribute(DATA_ORIGINAL_STYLE)) return;
    if (element.getAttribute('data-shellify-had-style') === '1') {
      element.setAttribute('style', element.getAttribute(DATA_ORIGINAL_STYLE) || '');
    } else {
      element.removeAttribute('style');
    }
    element.removeAttribute(DATA_ORIGINAL_STYLE);
    element.removeAttribute('data-shellify-had-style');
  }

  function setProtectedStyle(element, pending) {
    var smart = smartDetector();
    if (smart && smart.clear) smart.clear(element);
    rememberOriginalStyle(element);
    var amount = currentConfig.blurAmount;
    var filter = amount > 0 ? 'blur(' + amount + 'px)' : 'none';
    if (currentConfig.grayscale) filter += ' grayscale(1)';
    element.style.setProperty('filter', filter, 'important');
    element.style.setProperty('transition', 'filter 120ms ease', 'important');
    element.setAttribute(DATA_PROTECTED, '1');
    if (pending) element.setAttribute(DATA_PENDING, '1');
    else element.removeAttribute(DATA_PENDING);
  }

  function clearProtectedStyle(element) {
    var smart = smartDetector();
    if (smart && smart.clear) smart.clear(element);
    restoreStyle(element);
    element.removeAttribute(DATA_PROTECTED);
    element.removeAttribute(DATA_PENDING);
    element.removeAttribute(DATA_REVEALED);
    element.removeAttribute(DATA_REGIONAL);
  }

  function finishSmartProcess(element, result) {
    if (!result || result.pending || !document.documentElement.contains(element)) return;
    if (!shouldProcess(element) || isWhitelisted()) {
      clearProtectedStyle(element);
      return;
    }
    var smart = smartDetector();
    if (result.ready && result.regions && result.regions.length && smart && smart.apply &&
       smart.apply(element, currentConfig, result)) {
       restoreStyle(element);
       element.setAttribute(DATA_PROTECTED, '1');
       element.setAttribute(DATA_REGIONAL, '1');
       element.removeAttribute(DATA_REVEALED);
       element.removeAttribute(DATA_PENDING);
       return;
    }
    if (smart && smart.clear) smart.clear(element);
    element.removeAttribute(DATA_REGIONAL);
   var metadataBlocked = !result.ready && classify(element).blur;
    var unknownAtStrictness = result.unknownGender && currentConfig.strictness >= 0.75;
     if (metadataBlocked || unknownAtStrictness) setProtectedStyle(element, false);
    else clearProtectedStyle(element);
  }

  function addHoverReveal(element) {
    if (element.hasAttribute(DATA_HOVER_HOOK)) return;
    element.setAttribute(DATA_HOVER_HOOK, '1');
    element.addEventListener('pointerenter', function() {
      if (!currentConfig.hoverReveal || !element.hasAttribute(DATA_PROTECTED)) return;
      element.setAttribute(DATA_REVEALED, '1');
      var smart = smartDetector();
      if (element.hasAttribute(DATA_REGIONAL) && smart && smart.setRevealed) smart.setRevealed(element, true);
      else element.style.setProperty('filter', 'none', 'important');
    }, true);
    element.addEventListener('pointerleave', function() {
      if (!element.hasAttribute(DATA_REVEALED)) return;
      element.removeAttribute(DATA_REVEALED);
      var smart = smartDetector();
      if (element.hasAttribute(DATA_REGIONAL) && smart && smart.setRevealed) smart.setRevealed(element, false);
      else if (element.hasAttribute(DATA_PROTECTED)) setProtectedStyle(element, false);
    }, true);
  }

  function process(element) {
    if (!shouldProcess(element) || isWhitelisted()) {
      clearProtectedStyle(element);
      return;
    }
    addHoverReveal(element);
    var kind = mediaKind(element);
    var loading = (kind === 'img' && !element.complete) ||
      (kind === 'video' && element.readyState < 2);
    var smart = smartDetector();
    if (smart && smart.analyze) {
      if (currentConfig.startupBlur) setProtectedStyle(element, true);
      smart.analyze(element, currentConfig, function(result) {
        finishSmartProcess(element, result);
      });
      return;
    }
    if (loading && currentConfig.startupBlur) setProtectedStyle(element, true);
    var result = classify(element);
    if (result.blur) setProtectedStyle(element, false);
    else if (!(loading && currentConfig.startupBlur)) clearProtectedStyle(element);
  }

  function scan(root) {
    if (!currentConfig.enabled || isWhitelisted()) {
      document.querySelectorAll('img[data-shellify-protected],video[data-shellify-protected]')
        .forEach(clearProtectedStyle);
      return;
    }
    var elements = [];
    if (root && root.nodeType === 1 && (mediaKind(root) === 'img' || mediaKind(root) === 'video')) {
      elements.push(root);
    }
    var scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll('img,video').forEach(function(element) { elements.push(element); });
    elements.forEach(process);
  }

  function update(value) {
    currentConfig = normalizeConfig(value);
    scan(document);
  }

  document.addEventListener('shellifyContentProtectionUpdate', function(event) {
    if (event && event.detail) update(event.detail);
  }, true);
  window[KEY] = { update: update };
  currentConfig = normalizeConfig(currentConfig);
  scan(document);
  if (!observer && document.documentElement) {
    observer = new MutationObserver(function(records) {
      records.forEach(function(record) {
        record.addedNodes.forEach(function(node) { scan(node); });
      });
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }
  if (!scanTimer) scanTimer = window.setInterval(function() { scan(document); }, 1500);
})();
"""
}
