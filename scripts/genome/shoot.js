const { chromium } = require('playwright');
const path = require('path');

(async () => {
  const file = 'file://' + path.resolve(__dirname, 'grain-genome.html');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 2 });
  const errors = [];
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

  await page.goto(file, { waitUntil: 'networkidle' });
  await page.waitForTimeout(700);
  await page.screenshot({ path: 'shot-1-board.png' });

  // select the event-store-v3 hub to see its trace subgraph
  await page.click('.node[data-id="event-store-v3"]');
  await page.waitForTimeout(700);
  await page.screenshot({ path: 'shot-2-selected.png' });

  // protocols lens
  await page.click('.node[data-id="event-store-v3"]'); // deselect via re-... actually use Esc
  await page.keyboard.press('Escape');
  await page.click('.lensbtn[data-lens="protocols"]');
  await page.waitForTimeout(700);
  await page.screenshot({ path: 'shot-3-protocols.png' });

  // catalog board
  await page.click('.lensbtn[data-lens="structure"]');
  await page.click('#gocat');
  await page.waitForTimeout(900);
  await page.screenshot({ path: 'shot-4-catalog.png' });

  console.log('errors:', errors.length ? errors.join('\n') : 'none');
  await browser.close();
})();
