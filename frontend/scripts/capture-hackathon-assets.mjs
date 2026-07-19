import { chromium } from '@playwright/test';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '../..');
const outputDir = path.resolve(
  repoRoot,
  process.env.FRESNEL_PITCH_ASSETS_DIR ?? 'build/hackathon-video/assets',
);
const baseUrl = process.env.FRESNEL_PITCH_BASE_URL ?? 'http://127.0.0.1:8080';
const username = process.env.FRESNEL_PITCH_USER ?? 'user';
const password = process.env.FRESNEL_PITCH_PASSWORD ?? 'user';

await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
  viewport: { width: 1920, height: 1080 },
  deviceScaleFactor: 1,
  colorScheme: 'light',
  reducedMotion: 'reduce',
  httpCredentials: { username, password },
});
const page = await context.newPage();
const captures = [];
const browserEvents = [];

page.on('console', (message) => {
  browserEvents.push({ type: `console:${message.type()}`, text: message.text() });
});
page.on('pageerror', (error) => {
  browserEvents.push({ type: 'pageerror', text: error.stack ?? error.message });
});
page.on('requestfailed', (request) => {
  browserEvents.push({
    type: 'requestfailed',
    text: `${request.method()} ${request.url()} — ${request.failure()?.errorText ?? 'unknown'}`,
  });
});
page.on('response', (response) => {
  if (response.status() >= 400) {
    browserEvents.push({
      type: 'http-error',
      text: `${response.status()} ${response.request().method()} ${response.url()}`,
    });
  }
});

async function capture(name, locator = page) {
  const target = path.join(outputDir, name);
  await locator.screenshot({
    path: target,
    animations: 'disabled',
    caret: 'hide',
  });
  const bytes = await readFile(target);
  captures.push({
    path: path.relative(repoRoot, target).replaceAll(path.sep, '/'),
    sizeBytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex'),
  });
}

async function writeDiagnostics(error = null) {
  const bodyText = await page.locator('body').innerText().catch(() => '');
  const title = await page.title().catch(() => '');
  const html = await page.content().catch(() => '');
  await writeFile(path.join(outputDir, 'loaded-page.html'), html, 'utf8');
  await writeFile(
    path.join(outputDir, 'capture-diagnostics.json'),
    `${JSON.stringify({
      formatVersion: 1,
      url: page.url(),
      title,
      bodyText: bodyText.slice(0, 20_000),
      browserEvents,
      error: error
        ? { name: error.name, message: error.message, stack: error.stack }
        : null,
    }, null, 2)}\n`,
    'utf8',
  );
}

async function fill(label, value) {
  const control = page.locator('[data-plugin-schema="zone-plate"]').getByLabel(label);
  await control.waitFor({ state: 'visible', timeout: 30_000 });
  await control.fill(value);
  await control.blur();
}

try {
  await page.goto(`${baseUrl}/`, { waitUntil: 'domcontentloaded', timeout: 90_000 });
  await page.waitForTimeout(1500);
  await capture('00-loaded-page.png');
  await page.getByRole('heading', { name: 'Fresnel Designer' })
    .waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('[data-plugin-schema="zone-plate"]')
    .waitFor({ state: 'visible', timeout: 45_000 });
  await page.addStyleTag({
    content: `
      *, *::before, *::after {
        animation-duration: 0s !important;
        animation-delay: 0s !important;
        transition-duration: 0s !important;
        caret-color: transparent !important;
      }
      .panel { scrollbar-width: none; }
      .panel::-webkit-scrollbar { display: none; }
    `,
  });

  const panel = page.locator('aside.panel');
  await panel.waitFor({ state: 'visible' });
  await capture('01-default-editor.png', panel);

  await fill('Aperture diameter (mm)', '10');
  await fill('Focal length (mm)', '1000');
  await fill('Wavelength (nm)', '532');
  await fill('Printer DPI', '1200');
  await page.waitForTimeout(1200);
  await capture('02-physical-target.png', panel);

  const render = page.getByRole('button', { name: 'Render preview', exact: true });
  await render.waitFor({ state: 'visible' });
  await page.waitForFunction(() => {
    const button = [...document.querySelectorAll('button')]
      .find((candidate) => candidate.textContent?.trim() === 'Render preview');
    return button instanceof HTMLButtonElement && !button.disabled;
  }, undefined, { timeout: 30_000 });
  await render.click();

  const preview = page.getByRole('img', { name: 'Fresnel zone plate preview' });
  await preview.waitFor({ state: 'visible', timeout: 60_000 });
  await page.waitForFunction(() => {
    const image = document.querySelector('img[alt="Fresnel zone plate preview"]');
    return image instanceof HTMLImageElement && image.complete && image.naturalWidth > 0;
  }, undefined, { timeout: 60_000 });

  const validation = page.locator('[data-editor-extension="validation"]');
  await validation.scrollIntoViewIfNeeded();
  await page.waitForTimeout(500);
  await capture('03-preview-and-validation.png', panel);

  await page.evaluate(() => {
    const panelElement = document.querySelector('aside.panel');
    if (panelElement) panelElement.scrollTop = panelElement.scrollHeight;
  });
  await page.waitForTimeout(350);
  await capture('04-validation-details.png', panel);

  await writeDiagnostics();
  await writeFile(
    path.join(outputDir, 'capture-manifest.json'),
    `${JSON.stringify({
      formatVersion: 1,
      viewport: { width: 1920, height: 1080 },
      basePath: '/',
      captures,
    }, null, 2)}\n`,
    'utf8',
  );
} catch (error) {
  await capture('99-capture-failure.png').catch(() => {});
  await writeDiagnostics(error).catch(() => {});
  throw error;
} finally {
  await browser.close();
}
