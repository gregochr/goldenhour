import { test, expect } from './fixture.js';

test.describe('Login form', () => {
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
