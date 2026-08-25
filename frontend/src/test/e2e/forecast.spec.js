import { test, expect } from '@playwright/test';

/**
 * End-to-end tests for the Golden Hour Plan/Map UI.
 *
 * These tests require both the React dev server (port 5173) and the Spring Boot
 * backend (port 8083) to be running.
 */

const BACKEND = 'http://127.0.0.1:8083';

/**
 * Obtains a JWT from the backend and injects it into localStorage so that the
 * app boots directly into the authenticated state, bypassing the login page.
 */
async function loginAsAdmin(page) {
  const response = await page.request.post(`${BACKEND}/api/auth/login`, {
    data: { username: 'admin', password: 'golden2026' },
  });
  const { accessToken, refreshToken, refreshExpiresAt, username } = await response.json();
  await page.evaluate(({ token, refresh, refreshExpires, user }) => {
    localStorage.setItem('goldenhour_token', token);
    localStorage.setItem('goldenhour_refresh', refresh);
    localStorage.setItem('goldenhour_role', 'ADMIN');
    localStorage.setItem('goldenhour_username', user);
    localStorage.setItem('goldenhour_refresh_expires', refreshExpires);
  }, { token: accessToken, refresh: refreshToken, refreshExpires: refreshExpiresAt, user: username });
}

// ---------------------------------------------------------------------------
// Login flow
// ---------------------------------------------------------------------------
test.describe('Login flow', () => {
  test('renders login form with username and password fields', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('login-username')).toBeVisible();
    await expect(page.getByTestId('login-password')).toBeVisible();
    await expect(page.getByTestId('login-submit')).toBeVisible();
  });

  test('logs in successfully and shows app header', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-username').fill('admin');
    await page.getByTestId('login-password').fill('golden2026');
    await page.getByTestId('login-submit').click();
    await expect(page.getByRole('heading', { name: /PhotoCast/ })).toBeVisible({ timeout: 10000 });
  });
});

// ---------------------------------------------------------------------------
// Map view — core
// ---------------------------------------------------------------------------
test.describe('Map view — core', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await loginAsAdmin(page);
    await page.goto('/');
    await page.getByRole('tab', { name: 'Map' }).click();
  });

  test('renders map container and date strip after opening the Map tab', async ({ page }) => {
    await expect(page.getByTestId('map-container')).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('date-strip')).toBeVisible();
  });

  test('renders Leaflet map with location markers', async ({ page }) => {
    await page.waitForSelector('.leaflet-container', { timeout: 10000 });
    await expect(page.locator('.leaflet-container').first()).toBeVisible();
    // At least one marker should be rendered for configured locations
    await page.waitForSelector('.leaflet-marker-icon', { timeout: 10000 });
    const markers = page.locator('.leaflet-marker-icon');
    const count = await markers.count();
    expect(count).toBeGreaterThanOrEqual(1);
  });

  test('date strip chips are clickable and change selection', async ({ page }) => {
    await page.waitForSelector('[data-testid="date-chip"]', { timeout: 10000 });
    const dateButtons = page.getByTestId('date-chip');
    const count = await dateButtons.count();
    expect(count).toBeGreaterThanOrEqual(2);

    // Click the second date chip
    const secondButton = dateButtons.nth(1);
    await secondButton.click();
    await expect(secondButton).toHaveAttribute('data-selected', 'true');
  });
});

// ---------------------------------------------------------------------------
// Map view — marker interaction
// ---------------------------------------------------------------------------
test.describe('Map view — marker interaction', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await loginAsAdmin(page);
    await page.goto('/');
    await page.getByRole('tab', { name: 'Map' }).click();
    await page.waitForSelector('.leaflet-marker-icon', { timeout: 10000 });
  });

  test('clicking a marker opens a popup', async ({ page }) => {
    // Custom marker icons have child divs that intercept pointer events; use force click
    await page.locator('.leaflet-marker-icon').first().click({ force: true });
    await expect(page.locator('.leaflet-popup')).toBeVisible({ timeout: 5000 });
  });

  test('popup contains location name and forecast summary', async ({ page }) => {
    await page.locator('.leaflet-marker-icon').first().click({ force: true });
    await page.waitForSelector('.leaflet-popup', { timeout: 5000 });

    const popupContent = page.locator('.leaflet-popup-content');
    // The popup should contain non-empty text (location name is rendered as bold text)
    const text = await popupContent.textContent();
    expect(text.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// Operations view — ADMIN
// ---------------------------------------------------------------------------
test.describe('Operations view — ADMIN', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await loginAsAdmin(page);
    await page.goto('/');
  });

  test('Operations tab is visible for admin and shows sub-tabs', async ({ page }) => {
    const operationsTab = page.getByRole('tab', { name: 'Operations' });
    await expect(operationsTab).toBeVisible();
    await operationsTab.click();

    // Group tabs should be visible
    await expect(page.getByTestId('manage-group-data')).toBeVisible({ timeout: 5000 });
    await expect(page.getByTestId('manage-group-operations')).toBeVisible();

    // Data sub-tabs should be visible by default
    await expect(page.getByTestId('manage-tab-users')).toBeVisible();
    await expect(page.getByTestId('manage-tab-locations')).toBeVisible();

    // Switch to Operations group and verify its sub-tabs
    await page.getByTestId('manage-group-operations').click();
    await expect(page.getByTestId('manage-tab-metrics')).toBeVisible();
    await expect(page.getByTestId('manage-tab-models')).toBeVisible();
  });

  test('Locations sub-tab shows location cards with coordinates', async ({ page }) => {
    await page.getByRole('tab', { name: 'Operations' }).click();
    await page.getByTestId('manage-tab-locations').click();
    // Location cards contain lat/lon coordinates text (e.g. "54.7753° N, 1.5849° W")
    await expect(page.locator('text=/\\d+\\.\\d+° [NS]/').first()).toBeVisible({ timeout: 10000 });
  });
});
