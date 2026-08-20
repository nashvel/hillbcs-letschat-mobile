/**
 * Fallback screen behaviour.
 *
 * Reached only when the WebView could not load the remote app, so it cannot
 * assume anything about the bundle: no framework, no imports beyond Capacitor's
 * injected globals, which may themselves be absent.
 */

const retryButton = document.getElementById('retry');
const message = document.getElementById('message');

/** The address Capacitor was told to load, so retrying goes to the real app. */
function appUrl() {
  const injected = window.Capacitor?.getServerUrl?.();
  if (typeof injected === 'string' && injected.trim() !== '') {
    return injected;
  }
  // Capacitor exposes the configured server URL on the native config in most
  // versions; fall back to reloading whatever this document was.
  const configured = window.Capacitor?.config?.server?.url;
  return typeof configured === 'string' && configured.trim() !== '' ? configured : null;
}

function retry() {
  const target = appUrl();
  if (target) {
    window.location.replace(target);
    return;
  }
  window.location.reload();
}

retryButton?.addEventListener('click', retry);

// Coming back online is the common case, and noticing it saves a tap.
window.addEventListener('online', retry);

if (message && navigator.onLine === false) {
  message.textContent = "You're offline.";
}
