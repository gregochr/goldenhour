import { test, expect } from '@playwright/test';

/**
 * End-to-end tests for the Golden Hour forecast timeline.
 *
 * These tests require both the React dev server and the Spring Boot backend
 * to be running.
 */

test.describe('Forecast timeline', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('page loads and shows app header', async ({ page }) => {
    await expect(page.getByText('Golden Hour')).toBeVisible();
    await expect(page.getByText(/Durham UK/)).toBeVisible();
  });

  test('shows Today and Tomorrow labels', async ({ page }) => {
    await expect(page.getByText('Today')).toBeVisible();
    await expect(page.getByText('Tomorrow')).toBeVisible();
  });

  test('renders at least 8 forecast cards', async ({ page }) => {
    // Wait for cards to load (API call)
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
  test('opens outcome form on button click', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[data-testid="record-outcome-button"]', { timeout: 10000 });
    await page.getByTestId('record-outcome-button').first().click();
    await expect(page.getByTestId('outcome-form')).toBeVisible();
  });

  test('submits outcome form successfully', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[data-testid="record-outcome-button"]', { timeout: 10000 });
    await page.getByTestId('record-outcome-button').first().click();

    await page.getByTestId('actual-rating-3').click();
    await page.getByTestId('went-out-yes').click();
    await page.getByTestId('outcome-notes').fill('Beautiful warm light on the cathedral.');
    await page.getByTestId('outcome-submit').click();

    await expect(page.getByTestId('outcome-saved-message')).toBeVisible({ timeout: 5000 });
  });
});

test.describe('Error handling', () => {
  test('shows friendly error message when API is unavailable', async ({ page }) => {
    // Route all API calls to a non-existent server
    await page.route('/api/**', (route) => route.abort());
    await page.goto('/');
    await expect(page.getByTestId('error-message')).toBeVisible({ timeout: 10000 });
  });
});
