'use strict';

browser.runtime.onMessage.addListener(function(message) {
  return browser.runtime.sendNativeMessage('shellifyContentProtection', message || {});
});
