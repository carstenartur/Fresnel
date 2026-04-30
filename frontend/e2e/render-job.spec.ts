import { expect, test } from '@playwright/test';

/**
 * Render-job lifecycle exercised through the public REST/SSE surface. The SPA
 * itself does not yet expose a job UI for the single zone-plate path, so this
 * spec drives the backend directly via {@link page.request} (with the seeded
 * basic-auth credentials configured globally) to verify the full submit →
 * progress → result.png pipeline behaves end-to-end.
 */
test('render job submits, progresses to 100%, and result PNG is downloadable', async ({ request }) => {
  // 1) Submit a quick single-zone-plate job.
  const submit = await request.post('/api/jobs/single', {
    data: {
      apertureDiameterMm: 4.0,
      focalLengthMm: 50.0,
      wavelengthNm: 550.0,
      dpi: 300.0,
    },
  });
  expect(submit.ok(), `submit status=${submit.status()} body=${await submit.text()}`).toBe(true);
  const { jobId } = await submit.json() as { jobId: string };
  expect(jobId).toMatch(/^j-\d+-\d+$/);

  // 2) Poll the JSON job-status endpoint until COMPLETED. We deliberately use
  //    the polling endpoint instead of SSE here because Playwright's
  //    APIRequestContext does not expose an EventSource-style API; SSE is
  //    exercised by the SPA and verified via the SingleZonePlate spec.
  const deadline = Date.now() + 60_000;
  let progress = 0;
  while (Date.now() < deadline) {
    const status = await request.get(`/api/jobs/${jobId}`);
    expect(status.ok()).toBe(true);
    const body = await status.json() as { state: string; progress: number };
    progress = body.progress;
    if (body.state === 'COMPLETED') break;
    if (body.state === 'FAILED') throw new Error(`Job failed: ${JSON.stringify(body)}`);
    await new Promise((r) => setTimeout(r, 250));
  }
  expect(progress).toBe(1);

  // 3) Download the result PNG and assert it's a real image (PNG magic bytes).
  const png = await request.get(`/api/jobs/${jobId}/result.png`);
  expect(png.ok()).toBe(true);
  expect(png.headers()['content-type']).toContain('image/png');
  const buf = await png.body();
  expect(buf.length).toBeGreaterThan(64);
  // PNG magic: 89 50 4E 47 0D 0A 1A 0A
  expect(buf.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))).toBe(true);
});
