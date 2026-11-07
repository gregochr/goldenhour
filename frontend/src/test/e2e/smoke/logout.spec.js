import { test, expect } from './fixture.js';

/**
 * Runs in the `logout` project, AFTER the whole `smoke` project, on one worker (see
 * playwright.smoke.config.js): signing out bumps the local admin's account-wide credentials version, which
 * rejects every other token the admin holds. Beside another spec it would 401 that spec's requests.
 */
test.describe('Login and logout', () => {
  test('the form renders, accepts input, and the admin signs in and out cleanly', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('login-username')).toBeVisible();
    await expect(page.getByTestId('login-password')).toBeVisible();

    await page.getByTestId('login-username').fill('admin');
    await page.getByTestId('login-password').fill('golden2026');
    // The Turnstile stub verifies at once, which enables the button.
    await expect(page.getByTestId('login-submit')).toBeEnabled();

    const loggedIn = page.waitForResponse((r) => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST');
    await page.getByTestId('login-submit').click();
    expect((await loggedIn).status()).toBe(200);

    await expect(page.getByTestId('window-first-shell')).toBeVisible();
    await expect(page.getByTestId('login-username')).toHaveCount(0);

    await page.getByTestId('window-first-signout').click();
    await expect(page.getByTestId('login-username')).toBeVisible();
    await expect(page.getByTestId('window-first-shell')).toHaveCount(0);
  });
});
