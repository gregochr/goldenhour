import { test, expect, loginAsAdmin } from './fixture.js';

test.describe('Scheduler invariant', () => {
  test('the smoke database has no scheduler_job_config rows, so no job registers', async ({ page }) => {
    await loginAsAdmin(page);
    const token = await page.evaluate(() => localStorage.getItem('goldenhour_token'));
    const response = await page.request.get('/api/admin/scheduler/jobs', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(response.status()).toBe(200);
    expect(await response.json()).toEqual([]);
  });
});
