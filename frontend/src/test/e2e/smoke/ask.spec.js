import { test, expect, loginAndWaitForForecast } from './fixture.js';

test.describe('Ask PhotoCast (stub engine)', () => {
  test('the box opens and shows its allowance', async ({ page }) => {
    await loginAndWaitForForecast(page);
    await expect(page.getByTestId('ask-field')).toBeVisible();
    await page.getByTestId('ask-field').click();
    await expect(page.getByTestId('ask-dock')).toBeVisible();
    await expect(page.getByTestId('ask-input')).toBeVisible();
    // Only the box and its allowance are asserted, on the seeded fixture as before. Nothing runs
    // AskReadyPrecompute here, so no Ready answers exist; and a typed question would go to the stub engine,
    // which has nothing it can answer from, so none is sent (a 503 TYPED_UNAVAILABLE would be a console error).
    await expect(page.getByTestId('ask-allowance')).toBeVisible();
  });
});
