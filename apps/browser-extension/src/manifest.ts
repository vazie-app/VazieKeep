export type BrowserTarget = 'chromium';

const backgroundByTarget = {
  chromium: { service_worker: 'background.js', type: 'module' },
} satisfies Record<BrowserTarget, chrome.runtime.ManifestV3['background']>;

const icons = {
  '16': 'icons/classic-16.png',
  '32': 'icons/classic-32.png',
  '48': 'icons/classic-48.png',
  '128': 'icons/classic-128.png',
};

export function createManifest(target: BrowserTarget, version: string): chrome.runtime.ManifestV3 {
  return {
    manifest_version: 3,
    name: 'Vazie Keep',
    description:
      'Password manager for the KeePass file you own. No account, no cloud: autofill, passkeys, TOTP and the full vault in your browser.',
    version,
    minimum_chrome_version: '120',
    icons,
    action: {
      default_title: 'Vazie Keep',
      default_popup: 'ui/popup.html',
      default_icon: icons,
    },
    side_panel: { default_path: 'ui/panel.html' },
    background: backgroundByTarget[target],
    permissions: [
      'offscreen',
      'alarms',
      'idle',
      'sidePanel',
      'contextMenus',
      'clipboardRead',
      'clipboardWrite',
      'unlimitedStorage',
    ],
    host_permissions: ['<all_urls>'],
    content_scripts: [
      {
        matches: ['<all_urls>'],
        js: ['content/passkeys-main.js'],
        run_at: 'document_start',
        all_frames: true,
        world: 'MAIN',
      },
      {
        matches: ['<all_urls>'],
        js: ['content/page.js'],
        run_at: 'document_start',
        all_frames: true,
      },
    ],
    commands: {
      'fill-login': {
        suggested_key: { default: 'Ctrl+Shift+L', mac: 'Command+Shift+L' },
        description: 'Fill the login on this page',
      },
      'lock-vault': {
        description: 'Lock Vazie Keep',
      },
    },
    content_security_policy: {
      extension_pages: "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'",
    },
  };
}
