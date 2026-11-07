import { test, expect, loginAsAdmin } from './fixture.js';

test.describe('Ask PhotoCast (stub engine)', () => {
  test('the box opens on an empty database', async ({ page }) => {
    await loginAsAdmin(page);
    await expect(page.getByTestId('window-first-shell')).toBeVisible();
    await expect(page.getByTestId('ask-field')).toBeVisible();
    await page.getByTestId('ask-field').click();
    await expect(page.getByTestId('ask-dock')).toBeVisible();
    await expect(page.getByTestId('ask-input')).toBeVisible();
    // Only the box and its empty state are asserted. On an empty database the stub has nothing to answer
    // from: a typed question returns 503 TYPED_UNAVAILABLE ("Typed questions are unavailable right now"),
    // which the dock renders gracefully but which would be a console error here, so none is sent.
    await expect(page.getByTestId('ask-allowance')).toBeVisible();
  });
});
