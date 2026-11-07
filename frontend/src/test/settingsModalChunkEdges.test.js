import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

beforeEach(() => {
  vi.resetModules();
});

afterEach(() => {
  vi.doUnmock('../components/UserSettingsModal.jsx');
  vi.restoreAllMocks();
});

describe('warmSettingsModal', () => {
  it('starts loading the settings dialog chunk without anyone asking for it', async () => {
    const loaded = vi.fn();
    vi.doMock('../components/UserSettingsModal.jsx', () => {
      loaded();
      return { default: () => null };
    });
    const { warmSettingsModal } = await import('../utils/settingsModalChunk.js');
    expect(loaded).not.toHaveBeenCalled();
    warmSettingsModal();
    await vi.dynamicImportSettled();
    expect(loaded).toHaveBeenCalledTimes(1);
  });

  it('shares one module promise with the lazy loader', async () => {
    const loaded = vi.fn();
    vi.doMock('../components/UserSettingsModal.jsx', () => {
      loaded();
      return { default: () => null };
    });
    const { loadSettingsModal, warmSettingsModal } = await import('../utils/settingsModalChunk.js');
    warmSettingsModal();
    const mod = await loadSettingsModal();
    expect(typeof mod.default).toBe('function');
    expect(loaded).toHaveBeenCalledTimes(1);
  });

  it('swallows a failed load instead of leaving an unhandled rejection', async () => {
    const unhandled = vi.fn();
    process.on('unhandledRejection', unhandled);
    try {
      vi.doMock('../components/UserSettingsModal.jsx', () => {
        throw new Error('chunk 404');
      });
      const { warmSettingsModal } = await import('../utils/settingsModalChunk.js');
      expect(() => warmSettingsModal()).not.toThrow();
      await vi.dynamicImportSettled();
      await new Promise((resolve) => { setTimeout(resolve, 20); });
      expect(unhandled).not.toHaveBeenCalled();
    } finally {
      process.off('unhandledRejection', unhandled);
    }
  });
});
