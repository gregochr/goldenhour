import { vi } from 'vitest';

/**
 * A stand-in for `context/GuideContext.jsx` for the tests of the G3 doors: every door calls this one spy, so a
 * test asserts what a press opens without mounting the panel.
 *
 * Use it from a test file as
 * {@code vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);}
 * and import {@code mockOpenGuide} to assert on. The state fields are the closed, no-provider values, so a
 * component that reads {@code guideModal} or {@code open} sees a guide that is not there.
 */
export const mockOpenGuide = vi.fn();

export const guideContextMock = {
  useGuide: () => ({
    open: false,
    presentation: null,
    page: null,
    hash: '',
    nonce: 0,
    focusKey: 0,
    tracked: null,
    guideModal: false,
    lastClose: { seq: 0, restoreFocus: true, doorInDock: false },
    openGuide: mockOpenGuide,
    closeGuide: () => {},
    trackGuide: () => {},
  }),
  GuideProvider: ({ children }) => children,
};
