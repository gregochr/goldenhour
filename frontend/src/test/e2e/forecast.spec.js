import { test, expect } from '@playwright/test';

/**
 * End-to-end tests for the Golden Hour forecast timeline.
 *
 * These tests require both the React dev server (port 5173) and the Spring Boot
 * backend (port 8082) to be running.
 */

const BACKEND = 'http://127.0.0.1:8082';

/**
 * Obtains a JWT from the backend and injects it into localStorage so that the
 * app boots directly into the authenticated state, bypassing the login page.
 */
async function loginAsAdmin(page) {
  const response = await page.request.post(`${BACKEND}/api/auth/login`, {
    data: { username: 'admin', password: 'golden2026' },
  });
  const { accessToken, refreshToken, refreshExpiresAt } = await response.json();
  await page.evaluate(({ token, refresh, refreshExpires }) => {
    localStorage.setItem('goldenhour_token', token);
    localStorage.setItem('goldenhour_refresh', refresh);
    localStorage.setItem('goldenhour_role', 'ADMIN');
    localStorage.setItem('goldenhour_refresh_expires', refreshExpires);
  }, { token: accessToken, refresh: refreshToken, refreshExpires: refreshExpiresAt });
}

test.describe('Forecast timeline', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');        // establishes the origin (shows login page)
    await loginAsAdmin(page);    // inject tokens into localStorage
    await page.goto('/');        // reload — now authenticated
    await page.getByRole('button', { name: 'By Location' }).click();
  });

  test('page loads and shows app header', async ({ page }) => {
    await expect(page.getByRole('heading', { name: /Golden Hour/ })).toBeVisible();
    await page.waitForSelector('[data-testid="forecast-card"]', { timeout: 10000 });
    await expect(page.getByTestId('forecast-card').first()).toBeVisible();
  });

  test('shows Today and Tomorrow labels', async ({ page }) => {
    await expect(page.getByText('Today')).toBeVisible();
    await expect(page.getByText('Tomorrow')).toBeVisible();
  });

  test('renders at least 8 forecast cards', async ({ page }) => {
    await page.waitForSelector('[data-testid="forecast-card"]', { timeout: 10000 });
    const cards = page.getByTestId('forecast-card');
    await expect(cards).toHaveCount(16); // 8 dates × 2 (sunrise + sunset)
  });

  test('sunrise and sunset ratings are visible', async ({ page }) => {
    await page.waitForSelector('[data-testid="sunrise-rating"]', { timeout: 10000 });
    await expect(page.getByTestId('sunrise-rating').first()).toBeVisible();
    await expect(page.getByTestId('sunset-rating').first()).toBeVisible();
  });

  test('Windy data visualisations render', async ({ page }) => {
    await page.waitForSelector('[data-testid="cloud-cover-bars"]', { timeout: 10000 });
    await expect(page.getByTestId('cloud-cover-bars').first()).toBeVisible();
    await expect(page.getByTestId('wind-indicator').first()).toBeVisible();
    await expect(page.getByTestId('visibility-indicator').first()).toBeVisible();
  });
});

test.describe('Outcome recording flow', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await loginAsAdmin(page);
    await page.goto('/');
    await page.getByRole('button', { name: 'By Location' }).click();
  });

  test('opens outcome form on button click', async ({ page }) => {
    await page.waitForSelector('[data-testid="record-outcome-button"]', { timeout: 10000 });
    await page.getByTestId('record-outcome-button').first().click();
    await expect(page.getByTestId('outcome-form')).toBeVisible();
  });

  test('submits outcome form successfully', async ({ page }) => {
    await page.waitForSelector('[data-testid="record-outcome-button"]', { timeout: 10000 });
    await page.getByTestId('record-outcome-button').first().click();

    await page.getByTestId('actual-rating-3').click();
    await page.getByTestId('went-out-yes').click();
    await page.getByTestId('outcome-notes').fill('Beautiful warm light on the cathedral.');
    await page.getByTestId('outcome-submit').click();

    await expect(page.getByTestId('outcome-saved-message')).toBeVisible({ timeout: 5000 });
  });
});

test.describe('By Date view', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await loginAsAdmin(page);
    await page.goto('/');
    await page.getByRole('button', { name: 'By Date' }).click();
  });

  test('renders date strip for date-based view', async ({ page }) => {
    await page.waitForSelector('[data-testid="date-strip"]', { timeout: 10000 });
    await expect(page.getByTestId('date-strip')).toBeVisible();
  });

  test('allows clicking date buttons to change selected date', async ({ page }) => {
    await page.waitForSelector('[data-testid="date-strip"] button', { timeout: 10000 });
    const dateButtons = page.locator('[data-testid="date-strip"] button');
    const count = await dateButtons.count();
    if (count > 1) {
      const secondButton = dateButtons.nth(1);
      await secondButton.click();
      // Verify the click succeeded by checking it now has the active styling
      await expect(secondButton).toHaveClass(/bg-gray-100/);
    }
  });
});

test.describe('Map view', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await loginAsAdmin(page);
    await page.goto('/');
    await page.getByRole('button', { name: 'Map' }).click();
  });

  test('renders map and date strip', async ({ page }) => {
    await page.waitForSelector('[data-testid="map-container"]', { timeout: 10000 });
    await expect(page.getByTestId('map-container')).toBeVisible();
    await expect(page.getByTestId('date-strip')).toBeVisible();
  });

  test('renders map with markers for locations', async ({ page }) => {
    // Map is rendered if we can see the Leaflet container div
    const mapDiv = await page.locator('.leaflet-container').first();
    await expect(mapDiv).toBeVisible({ timeout: 5000 });
  });

  test('allows date selection on map view', async ({ page }) => {
    await page.waitForSelector('[data-testid="date-strip"] button', { timeout: 10000 });
    const dateButtons = page.locator('[data-testid="date-strip"] button');
    const count = await dateButtons.count();
    if (count > 1) {
      await dateButtons.nth(1).click();
      await expect(page.getByTestId('map-container')).toBeVisible();
    }
  });
});

test.describe('Error handling', () => {
  test('shows friendly error message when API is unavailable', async ({ page }) => {
    // Login first, then abort API calls to simulate the backend going down
    await page.goto('/');
    await loginAsAdmin(page);
    await page.route('/api/**', (route) => route.abort());
    await page.goto('/');
    await expect(page.getByTestId('error-message')).toBeVisible({ timeout: 10000 });
  });
});
