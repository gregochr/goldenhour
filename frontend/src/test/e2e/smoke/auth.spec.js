import { test, expect } from './fixture.js';

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

  test('a failed sign-in shows the error and stays on the form', async ({ page, hygiene }) => {
    // Provoked on purpose: the browser logs a rejected login as a failed resource.
    hygiene.expect({ kind: 'response', match: /^401 POST .*\/api\/auth\/login$/, reason: 'failed sign-in' });
    hygiene.expect({ kind: 'console', match: /status of 401/, reason: 'failed sign-in' });
    await page.goto('/');
    // NOT the admin: ten failed sign-ins lock an account out (429), and a rejected admin login here would
    // poison every other spec's login (it did, under --repeat-each=20). An unknown user takes the same
    // error path and counts against nobody.
    await page.getByTestId('login-username').fill('no-such-user-e2e');
    await page.getByTestId('login-password').fill('not-the-password');
    await expect(page.getByTestId('login-submit')).toBeEnabled();
    await page.getByTestId('login-submit').click();
    await expect(page.getByTestId('login-error')).toBeVisible();
    await expect(page.getByTestId('window-first-shell')).toHaveCount(0);
  });
});
