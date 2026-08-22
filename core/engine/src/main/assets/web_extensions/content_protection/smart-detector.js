'use strict';

(function() {
  var KEY = '__shellifySmartDetection';
  if (typeof window === 'undefined' || window[KEY]) return;

  var MODEL_PATH = '/__shellify_content_protection/models/';
  var DETECTOR_BACKENDS = ['humangl', 'webgl', 'cpu'];
  var DETECTOR_LOAD_TIMEOUT_MS = 45000;
  var DETECTION_TIMEOUT_MS = 12000;
  var VIDEO_CACHE_MS = 1200;
  var DETECTOR_ERROR_CACHE_MS = 5000;
  var IMAGE_CACHE_MS = 60000;
  var MAX_CAPTURE_DIMENSION = 768;
  var VIDEO_JOB_MAX_AGE_MS = 5000;
  var DETECTOR_RETRY_INITIAL_MS = 15000;
  var DETECTOR_RETRY_MAX_MS = 300000;
  var states = new WeakMap();
  var captureCanvases = new WeakMap();
  var tracked = [];
  var queue = [];
  var detectorPromise = null;
  var detector = null;
  var detectorBackend = '';
  var detectorError = '';
  var detectorLoading = false;
  var detectorFailureCount = 0;
  var detectorRetryAt = 0;
  var lastDetectionMs = 0;
  var detectorWarningShown = false;
  var draining = false;
  var overlayRoot = null;
  var listenersInstalled = false;
  var refreshScheduled = false;
  var regionalBlurSupported = null;

  function mediaKind(element) {
    return element && element.tagName ? element.tagName.toLowerCase() : '';
  }

  function isVideo(element) {
    return mediaKind(element) === 'video';
  }

  function mediaDimensions(element) {
    return {
      width: Number(element.videoWidth || element.naturalWidth || element.width || 0),
      height: Number(element.videoHeight || element.naturalHeight || element.height || 0)
    };
  }

  function mediaSource(element) {
    return String(element.currentSrc || element.src || element.poster || element.getAttribute('data-src') || '');
  }

  function sameOriginSource(source) {
    if (!source || source.indexOf('data:') === 0 || source.indexOf('blob:') === 0) return true;
    try {
      return new URL(source, location.href).origin === location.origin;
    } catch (_) {
      return true;
    }
  }

  function loadCrossOriginImage(element) {
    var source = mediaSource(element);
    if (!source || sameOriginSource(source)) {
      return Promise.resolve({
        input: element,
        width: mediaDimensions(element).width,
        height: mediaDimensions(element).height
      });
    }
    return new Promise(function(resolve, reject) {
      var image = new Image();
      image.crossOrigin = 'anonymous';
      image.decoding = 'async';
      image.onload = function() {
        resolve({
          input: image,
          width: image.naturalWidth || mediaDimensions(element).width,
          height: image.naturalHeight || mediaDimensions(element).height
        });
      };
      image.onerror = function() {
        reject(new Error('Cross-origin image is not readable'));
      };
      image.src = source;
    });
  }

  function captureVideoFrame(element) {
    var dimensions = mediaDimensions(element);
    var scale = Math.min(1, MAX_CAPTURE_DIMENSION / Math.max(dimensions.width, dimensions.height));
    var width = Math.max(1, Math.round(dimensions.width * scale));
    var height = Math.max(1, Math.round(dimensions.height * scale));
    var currentTime = Number(element.currentTime || 0);
    var cached = captureCanvases.get(element);
    var canvas = cached && cached.canvas;
    if (!canvas) {
      canvas = document.createElement('canvas');
      cached = { canvas: canvas, width: 0, height: 0, time: -1, context: null };
      captureCanvases.set(element, cached);
    }
    if (cached.width !== width) {
      canvas.width = width;
      cached.width = width;
    }
    if (cached.height !== height) {
      canvas.height = height;
      cached.height = height;
    }
    if (cached.time === currentTime && cached.context) {
      return Promise.resolve({ input: canvas, width: width, height: height });
    }
    var context = cached.context || canvas.getContext('2d', { willReadFrequently: true });
    if (!context) return Promise.reject(new Error('Video capture canvas is unavailable'));
    cached.context = context;
    try {
      context.drawImage(element, 0, 0, width, height);
      context.getImageData(0, 0, 1, 1);
    } catch (_) {
      return Promise.reject(new Error('Video frame is not readable'));
    }
    cached.time = currentTime;
    return Promise.resolve({ input: canvas, width: width, height: height });
  }

  function captureInput(element) {
    if (element.tagName.toLowerCase() === 'video') return captureVideoFrame(element);
    return loadCrossOriginImage(element);
  }

  function isReady(element) {
    var dimensions = mediaDimensions(element);
    if (!dimensions.width || !dimensions.height) return false;
    return !isVideo(element) || element.readyState >= 2;
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
    return !isVideo(element) || (element.paused !== true && element.ended !== true && isVisible(element));
  }

  function modelBasePath() {
    try {
      if (typeof browser !== 'undefined' && browser.runtime && browser.runtime.getURL) {
        return browser.runtime.getURL('models/');
      }
      if (typeof chrome !== 'undefined' && chrome.runtime && chrome.runtime.getURL) {
        return chrome.runtime.getURL('models/');
      }
    } catch (_) {
      // Fall through to the same-origin WebView asset route.
    }
    return String(location.origin || '') + MODEL_PATH;
  }

  function withTimeout(promise, timeoutMs, message) {
    return new Promise(function(resolve, reject) {
      var timer = setTimeout(function() {
        reject(new Error(message));
      }, timeoutMs);
      Promise.resolve(promise).then(function(value) {
        clearTimeout(timer);
        resolve(value);
      }, function(error) {
        clearTimeout(timer);
        reject(error);
      });
    });
  }

  function rememberDetectorError(error) {
    detectorError = error && error.message ? error.message : String(error || 'Unknown detector error');
    if (!detectorWarningShown && typeof console !== 'undefined' && console.warn) {
      detectorWarningShown = true;
      console.warn('Shellify content protection detector unavailable:', detectorError);
    }
  }

  function openDetectorCircuit(error) {
    var now = Date.now();
    if (detectorRetryAt > now) {
      if (error) error.detectorCircuit = true;
      return;
    }
    detectorFailureCount = Math.min(detectorFailureCount + 1, 8);
    var delay = Math.min(
      DETECTOR_RETRY_MAX_MS,
      DETECTOR_RETRY_INITIAL_MS * Math.pow(2, detectorFailureCount - 1)
    );
    detectorRetryAt = now + delay;
    detector = null;
    detectorBackend = '';
    detectorPromise = null;
    if (error) error.detectorCircuit = true;
    rememberDetectorError(error);
  }

  function noteDetectorSuccess() {
    detectorFailureCount = 0;
    detectorRetryAt = 0;
    detectorError = '';
  }

  function detectorCircuitError() {
    var error = new Error(detectorError || 'Detector retry temporarily disabled');
    error.detectorCircuit = true;
    return error;
  }

  function detectorConfig(backend) {
    return {
      backend: backend,
      async: true,
      warmup: 'none',
      debug: false,
      cacheModels: true,
      cacheSensitivity: 0.9,
      modelBasePath: modelBasePath(),
      filter: { enabled: false },
      face: {
        enabled: true,
        detector: {
          enabled: true,
          modelPath: 'blazeface.json',
          maxDetected: 2,
          minConfidence: 0.25,
          skipFrames: 0,
          skipTime: 0
        },
        mesh: { enabled: false },
        iris: { enabled: false },
        attention: { enabled: false },
        emotion: { enabled: false },
        description: {
          enabled: true,
          modelPath: 'faceres.json',
          minConfidence: 0.1,
          skipFrames: 0,
          skipTime: 0
        }
      },
      body: {
        enabled: false
      },
      hand: { enabled: false },
      gesture: { enabled: false },
      object: { enabled: false },
      segmentation: { enabled: false }
    };
  }

  function disposeTensor(instance, tensor) {
    if (!tensor || !instance || !instance.tf || typeof instance.tf.dispose !== 'function') return;
    try {
      instance.tf.dispose(tensor);
    } catch (_) {
      // The runtime may already have disposed the warmup input after a failed backend attempt.
    }
  }

  function warmupDetector(instance) {
    if (!instance.tf || typeof instance.tf.zeros !== 'function') return Promise.resolve(instance);
    var tensor = instance.tf.zeros([1, 224, 224, 3]);
    var detection = Promise.resolve(instance.detect(tensor)).then(function(result) {
      if (result && result.error) throw new Error(String(result.error));
      return result;
    });
    detection.then(function() { disposeTensor(instance, tensor); }, function() { disposeTensor(instance, tensor); });
    return withTimeout(detection, DETECTION_TIMEOUT_MS, 'Detector warmup timed out').then(function() { return instance; });
  }

  function loadDetector(backend) {
    var instance = new Human.Human(detectorConfig(backend));
    return withTimeout(instance.load(), DETECTOR_LOAD_TIMEOUT_MS, 'Detector model loading timed out')
      .then(function() {
        if (instance.tf && typeof instance.tf.enableProdMode === 'function') instance.tf.enableProdMode();
        return warmupDetector(instance);
      })
      .then(function() {
        detector = instance;
        detectorBackend = backend;
        detectorError = '';
        return instance;
      });
  }

  function createDetector() {
    if (typeof Human === 'undefined' || !Human.Human) {
      return Promise.reject(new Error('Human detector library is unavailable'));
    }
    detectorLoading = true;
    var index = 0;
    var attempt = function() {
      var backend = DETECTOR_BACKENDS[index++];
      if (!backend) {
        detectorLoading = false;
        return Promise.reject(new Error(detectorError || 'No detector backend available'));
      }
      return loadDetector(backend).catch(function(error) {
        rememberDetectorError(error);
        return attempt();
      });
    };
    return Promise.resolve().then(attempt).then(function(instance) {
      detectorLoading = false;
      return instance;
    }, function(error) {
      detectorLoading = false;
      return Promise.reject(error);
    });
  }

  function getDetector() {
    if (detector) return Promise.resolve(detector);
    if (detectorPromise) return detectorPromise;
    if (detectorRetryAt > Date.now()) return Promise.reject(detectorCircuitError());
    detectorPromise = Promise.resolve().then(function() {
      return createDetector();
    }).catch(function(error) {
      detectorPromise = null;
      if (!error || !error.detectorCircuit) openDetectorCircuit(error);
      return Promise.reject(error);
    });
    return detectorPromise;
  }

  function configSignature(config) {
    return [
      config.blurFemale ? 'f' : '-',
      config.blurMale ? 'm' : '-',
      Math.round(Number(config.strictness || 0) * 100),
      config.blurAmount,
      config.grayscale ? 'g' : '-'
    ].join('|');
  }

  function skippedResult(element) {
    var dimensions = mediaDimensions(element);
    return {
      ready: false,
      pending: false,
      skipped: true,
      regions: [],
      width: dimensions.width,
      height: dimensions.height
    };
  }

  function emptyResult(element) {
    var dimensions = mediaDimensions(element);
    return {
      ready: true,
      pending: false,
      error: false,
      unknownGender: false,
      regions: [],
      width: dimensions.width,
      height: dimensions.height
    };
  }

  function videoJobIsStale(job) {
    return isVideo(job.element) &&
      (!isReady(job.element) || !isVideoActive(job.element) || Date.now() - job.enqueuedAt > VIDEO_JOB_MAX_AGE_MS);
  }

  function updateQueuedJob(state, element, config, currentSignature) {
    state.signature = currentSignature;
    state.requestId += 1;
    if (!state.queuedJob) {
      state.queuedJob = {
        element: element,
        config: config,
        state: state,
        requestId: state.requestId,
        enqueuedAt: Date.now()
      };
      queue.push(state.queuedJob);
      return true;
    }
    state.queuedJob.config = config;
    state.queuedJob.requestId = state.requestId;
    state.queuedJob.enqueuedAt = Date.now();
    state.queuedJob.element = element;
    return true;
  }

  function getState(element) {
    var state = states.get(element);
    if (!state) {
      state = {
        pending: false,
        callbacks: [],
        signature: '',
        completedAt: 0,
        result: null,
        overlays: [],
        regions: [],
        sourceWidth: 0,
        sourceHeight: 0,
        revealed: false,
        requestId: 0,
        configSignature: '',
        queuedJob: null,
        runningJob: null,
        appliedResult: null,
        appliedConfigSignature: ''
      };
      states.set(element, state);
    }
    return state;
  }

  function signature(element, config) {
    return [
      mediaSource(element),
      element.videoWidth || element.naturalWidth || 0,
      element.videoHeight || element.naturalHeight || 0,
      configSignature(config),
      isVideo(element) ? Math.floor(Number(element.currentTime || 0)) : ''
    ].join('|');
  }

  function number(value, fallback) {
    var result = Number(value);
    return isFinite(result) ? result : fallback;
  }

  function validBox(box) {
    return Array.isArray(box) && box.length >= 4 &&
      isFinite(Number(box[0])) && isFinite(Number(box[1])) &&
      isFinite(Number(box[2])) && isFinite(Number(box[3])) &&
      Number(box[2]) > 0 && Number(box[3]) > 0;
  }

  function expandBox(box, factor) {
    var x = Number(box[0]);
    var y = Number(box[1]);
    var width = Number(box[2]);
    var height = Number(box[3]);
    return [x - width * factor, y - height * factor, width * (1 + factor * 2), height * (1 + factor * 2)];
  }

  function isTargetFace(face, config) {
    var score = number(face.genderScore, 0);
    var threshold = Math.max(0.2, Math.min(0.5, 0.2 + number(config.strictness, 0) * 0.15));
    return (face.gender === 'female' && config.blurFemale && score >= threshold) ||
      (face.gender === 'male' && config.blurMale && score >= threshold);
  }

  function rescaleBoxes(result, inputWidth, inputHeight, outputWidth, outputHeight) {
    if (!result || !inputWidth || !inputHeight ||
        (inputWidth === outputWidth && inputHeight === outputHeight)) return;
    var scaleX = outputWidth / inputWidth;
    var scaleY = outputHeight / inputHeight;
    var detections = Array.isArray(result.face) ? result.face : [];
    detections.forEach(function(detection) {
      if (!validBox(detection.box)) return;
      detection.box = [
        Number(detection.box[0]) * scaleX,
        Number(detection.box[1]) * scaleY,
        Number(detection.box[2]) * scaleX,
        Number(detection.box[3]) * scaleY
      ];
    });
  }

  function detect(element, config) {
    var dimensions = mediaDimensions(element);
    if (!dimensions.width || !dimensions.height) {
      return Promise.resolve(skippedResult(element));
    }
    return getDetector().then(function(instance) {
      var startedAt = Date.now();
      return captureInput(element).then(function(capture) {
        var detection;
        try {
          detection = instance.detect(capture.input);
        } catch (error) {
          error.detectorFailure = true;
          throw error;
        }
        var timeout = isVideo(element) ? DETECTION_TIMEOUT_MS : DETECTION_TIMEOUT_MS * 2;
        return withTimeout(detection, timeout, 'Detector inference timed out').then(function(result) {
          if (result && result.error) {
            var resultError = new Error(String(result.error));
            resultError.detectorFailure = true;
            throw resultError;
          }
          noteDetectorSuccess();
          lastDetectionMs = Date.now() - startedAt;
          rescaleBoxes(result, capture.width, capture.height, dimensions.width, dimensions.height);
          return result;
        }, function(error) {
          error.detectorFailure = true;
          throw error;
        });
      }, function(error) {
        error.captureFailure = true;
        throw error;
      });
    }).then(function(result) {
      var faces = Array.isArray(result && result.face) ? result.face : [];
      var targetFaces = faces.filter(function(face) {
        return validBox(face.box) && isTargetFace(face, config);
      });
      var regions = targetFaces.map(function(face) { return expandBox(face.box, 0.1); });
      var unknownFaces = faces.filter(function(face) {
        return validBox(face.box) && (!face.gender || face.gender === 'unknown' || number(face.genderScore, 0) < 0.2);
      });
      var unknownGender = (config.blurFemale || config.blurMale) && unknownFaces.length > 0;
      if (unknownGender && Number(config.strictness || 0) >= 0.75) {
        unknownFaces.forEach(function(face) { regions.push(expandBox(face.box, 0.1)); });
      }
      return {
        ready: true,
        pending: false,
        error: false,
        unknownGender: unknownGender,
        regions: regions,
        width: dimensions.width,
        height: dimensions.height
      };
    }).catch(function(error) {
      if (!error || (!error.captureFailure && !error.detectorCircuit)) openDetectorCircuit(error);
      if (!error || !error.captureFailure) rememberDetectorError(error);
      return {
        ready: false,
        pending: false,
        error: true,
        message: error && error.message ? error.message : detectorError,
        regions: []
      };
    });
  }

  function deliver(state, result, job) {
    if (job && job.requestId !== state.requestId) return;
    state.pending = false;
    state.completedAt = Date.now();
    if (!(result && result.skipped && state.result && state.result.ready)) state.result = result;
    var callbacks = state.callbacks.slice();
    state.callbacks.length = 0;
    callbacks.forEach(function(callback) { callback(result); });
  }

  function drain() {
    if (draining) return;
    draining = true;
    var next = function() {
      var job = queue.shift();
      if (!job) {
        draining = false;
        return;
      }
      if (job.state.queuedJob === job) job.state.queuedJob = null;
      if (job.requestId !== job.state.requestId || videoJobIsStale(job)) {
        deliver(job.state, skippedResult(job.element), job);
        next();
        return;
      }
      job.state.runningJob = job;
      detect(job.element, job.config).then(function(result) {
        if (job.state.runningJob === job) job.state.runningJob = null;
        deliver(job.state, result, job);
        next();
      }, function(error) {
        if (job.state.runningJob === job) job.state.runningJob = null;
        deliver(job.state, {
          ready: false,
          pending: false,
          error: true,
          message: error && error.message ? error.message : 'Detector failed',
          regions: []
        }, job);
        next();
      });
    };
    next();
  }

  function analyze(element, config, callback) {
    var state = getState(element);
    var currentConfigSignature = configSignature(config);
    var configChanged = state.configSignature && state.configSignature !== currentConfigSignature;
    state.configSignature = currentConfigSignature;
    if (!isReady(element)) {
      if (isVideo(element)) callback(configChanged && state.result ? emptyResult(element) : skippedResult(element));
      else callback({ ready: false, pending: true, regions: [] });
      return;
    }
    var currentSignature = signature(element, config);
    var video = isVideo(element);
    var maxAge = state.result && state.result.error ? DETECTOR_ERROR_CACHE_MS : (video ? VIDEO_CACHE_MS : IMAGE_CACHE_MS);
    if (!state.pending && state.signature === currentSignature && state.result &&
      Date.now() - state.completedAt < maxAge) {
      callback(video && !isVideoActive(element) ? skippedResult(element) : state.result);
      return;
    }
    if (video && !isVideoActive(element)) {
      if (configChanged && state.result) {
        state.signature = currentSignature;
        state.completedAt = Date.now();
        state.result = emptyResult(element);
        callback(state.result);
      } else {
        callback(skippedResult(element));
      }
      return;
    }
    if (state.pending) {
      state.callbacks = [callback];
      if (state.signature !== currentSignature) updateQueuedJob(state, element, config, currentSignature);
      return;
    }
    state.pending = true;
    state.signature = currentSignature;
    state.requestId += 1;
    state.callbacks = [callback];
    state.queuedJob = {
      element: element,
      config: config,
      state: state,
      requestId: state.requestId,
      enqueuedAt: Date.now()
    };
    queue.push(state.queuedJob);
    drain();
  }

  function supportsRegionalBlur() {
    if (regionalBlurSupported !== null) return regionalBlurSupported;
    if (!window.CSS || typeof window.CSS.supports !== 'function') {
      regionalBlurSupported = true;
      return regionalBlurSupported;
    }
    regionalBlurSupported = window.CSS.supports('backdrop-filter', 'blur(1px)') ||
      window.CSS.supports('-webkit-backdrop-filter', 'blur(1px)');
    return regionalBlurSupported;
  }

  function getOverlayRoot() {
    if (overlayRoot && overlayRoot.parentNode) return overlayRoot;
    if (!document.body) return null;
    overlayRoot = document.createElement('div');
    overlayRoot.setAttribute('data-shellify-smart-overlay-root', '1');
    overlayRoot.style.setProperty('position', 'fixed', 'important');
    overlayRoot.style.setProperty('left', '0', 'important');
    overlayRoot.style.setProperty('top', '0', 'important');
    overlayRoot.style.setProperty('width', '100vw', 'important');
    overlayRoot.style.setProperty('height', '100vh', 'important');
    overlayRoot.style.setProperty('pointer-events', 'none', 'important');
    overlayRoot.style.setProperty('z-index', '2147483646', 'important');
    overlayRoot.style.setProperty('overflow', 'hidden', 'important');
    document.body.appendChild(overlayRoot);
    return overlayRoot;
  }

  function parsePosition(value, freeSpace) {
    var text = String(value || '50%').trim();
    if (text.endsWith('%')) return freeSpace * Math.max(0, Math.min(1, parseFloat(text) / 100));
    var pixels = parseFloat(text);
    return isFinite(pixels) ? pixels : freeSpace / 2;
  }

  function mapBox(element, box, sourceWidth, sourceHeight) {
    var rect = element.getBoundingClientRect();
    if (!rect.width || !rect.height || !sourceWidth || !sourceHeight) return null;
    var style = window.getComputedStyle(element);
    var fit = String(style.objectFit || 'fill').toLowerCase();
    var scaleX = rect.width / sourceWidth;
    var scaleY = rect.height / sourceHeight;
    var contentWidth = rect.width;
    var contentHeight = rect.height;
    var offsetX = 0;
    var offsetY = 0;
    if (fit === 'contain' || fit === 'cover' || fit === 'scale-down') {
      var uniform = fit === 'cover' ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
      if (fit === 'scale-down') uniform = Math.min(1, uniform);
      scaleX = uniform;
      scaleY = uniform;
      contentWidth = sourceWidth * uniform;
      contentHeight = sourceHeight * uniform;
      var positions = String(style.objectPosition || '50% 50%').split(/\s+/);
      offsetX = parsePosition(positions[0], rect.width - contentWidth);
      offsetY = parsePosition(positions[1] || positions[0], rect.height - contentHeight);
    }
    var left = rect.left + offsetX + Number(box[0]) * scaleX;
    var top = rect.top + offsetY + Number(box[1]) * scaleY;
    var right = left + Number(box[2]) * scaleX;
    var bottom = top + Number(box[3]) * scaleY;
    left = Math.max(rect.left, left);
    top = Math.max(rect.top, top);
    right = Math.min(rect.right, right);
    bottom = Math.min(rect.bottom, bottom);
    if (right <= left || bottom <= top) return null;
    return { left: left, top: top, width: right - left, height: bottom - top };
  }

  function refresh(element, config) {
    var state = states.get(element);
    if (!state || !state.overlays.length) return;
    var sourceWidth = state.sourceWidth;
    var sourceHeight = state.sourceHeight;
    var filter = number(config.blurAmount, 0) > 0 ? 'blur(' + number(config.blurAmount, 0) + 'px)' : 'none';
    if (config.grayscale) filter += ' grayscale(1)';
    state.overlays.forEach(function(overlay, index) {
      var mapped = mapBox(element, state.regions[index], sourceWidth, sourceHeight);
      if (!mapped) {
        overlay.style.display = 'none';
        return;
      }
      overlay.style.display = state.revealed ? 'none' : 'block';
      overlay.style.setProperty('left', mapped.left + 'px', 'important');
      overlay.style.setProperty('top', mapped.top + 'px', 'important');
      overlay.style.setProperty('width', mapped.width + 'px', 'important');
      overlay.style.setProperty('height', mapped.height + 'px', 'important');
      if (state.opaque) {
        overlay.style.setProperty('background', 'rgb(0, 0, 0)', 'important');
        overlay.style.setProperty('backdrop-filter', 'none', 'important');
        overlay.style.setProperty('-webkit-backdrop-filter', 'none', 'important');
      } else {
        overlay.style.setProperty('background', 'transparent', 'important');
        overlay.style.setProperty('backdrop-filter', filter, 'important');
        overlay.style.setProperty('-webkit-backdrop-filter', filter, 'important');
      }
    });
  }

  function refreshAll() {
    var alive = [];
    tracked.forEach(function(element) {
      if (!document.documentElement.contains(element)) {
        clear(element);
        return;
      }
      var state = states.get(element);
      if (state && state.config) refresh(element, state.config);
      alive.push(element);
    });
    tracked = alive;
  }

  function scheduleRefreshAll() {
    if (refreshScheduled) return;
    refreshScheduled = true;
    var refresh = function() {
      refreshScheduled = false;
      refreshAll();
    };
    if (typeof window.requestAnimationFrame === 'function') window.requestAnimationFrame(refresh);
    else setTimeout(refresh, 16);
  }

  function apply(element, config, result) {
    var root = getOverlayRoot();
    if (!root || !result || !result.regions || !result.regions.length) return false;
    var state = getState(element);
    var currentConfigSignature = configSignature(config);
    var reusable = state.appliedResult === result && state.appliedConfigSignature === currentConfigSignature &&
      state.overlays.length === result.regions.length && state.overlays.every(function(overlay) {
        return overlay.parentNode === root;
      });
    if (reusable) {
      state.config = config;
      refresh(element, config);
      return true;
    }
    state.overlays.forEach(function(overlay) { overlay.remove(); });
    state.overlays = [];
    state.regions = result.regions;
    state.sourceWidth = result.width;
    state.sourceHeight = result.height;
    state.config = config;
    // Gecko can black out a video when a backdrop-filter enters its compositor path.
    state.opaque = isVideo(element) || !supportsRegionalBlur();
    state.appliedResult = result;
    state.appliedConfigSignature = currentConfigSignature;
    state.revealed = false;
    result.regions.forEach(function() {
      var overlay = document.createElement('div');
      overlay.setAttribute('data-shellify-smart-overlay', '1');
      overlay.style.setProperty('position', 'absolute', 'important');
      overlay.style.setProperty('pointer-events', 'none', 'important');
      overlay.style.setProperty('background', 'transparent', 'important');
      root.appendChild(overlay);
      state.overlays.push(overlay);
    });
    if (tracked.indexOf(element) < 0) tracked.push(element);
    refresh(element, config);
    if (!listenersInstalled) {
      listenersInstalled = true;
      window.addEventListener('scroll', scheduleRefreshAll, true);
      window.addEventListener('resize', scheduleRefreshAll, true);
      window.addEventListener('orientationchange', scheduleRefreshAll, true);
    }
    return true;
  }

  function clear(element) {
    var state = states.get(element);
    if (!state) return;
    state.overlays.forEach(function(overlay) { overlay.remove(); });
    state.overlays = [];
    state.regions = [];
    state.revealed = false;
    state.appliedResult = null;
    state.appliedConfigSignature = '';
    tracked = tracked.filter(function(item) { return item !== element; });
  }

  function setRevealed(element, revealed) {
    var state = states.get(element);
    if (!state) return;
    state.revealed = Boolean(revealed);
    refresh(element, state.config || {});
  }

  window[KEY] = {
    analyze: analyze,
    apply: apply,
    clear: clear,
    setRevealed: setRevealed,
    status: function() {
      return {
        ready: Boolean(detector),
        loading: detectorLoading,
        backend: detectorBackend,
        error: detectorError,
        queueLength: queue.length,
        lastDetectionMs: lastDetectionMs
      };
    }
  };
})();
