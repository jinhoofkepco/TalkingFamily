#!/usr/bin/env node
"use strict";

// From the repository root: node scripts/test_map.cjs
// Requires the Playwright npm package and installed Chrome or a Playwright browser.
// If Playwright is bundled outside the repo, point NODE_PATH at its node_modules.
// Optional: CHROME_EXECUTABLE and MAP_TEST_OUTPUT_DIR. No real tile requests occur.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

const origin = "https://appassets.androidplatform.net";
const pageUrl = `${origin}/assets/family-map/index.html`;
const assets = path.resolve(__dirname, "../android/app/src/main/assets/family-map");
const output = path.resolve(process.env.MAP_TEST_OUTPUT_DIR || path.join(__dirname, "../../talkingfamily-map-review"));
const tileSvg = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#e7eee0"/><path d="M0 65H256M85 0V256M180 0V256M0 175H256" stroke="#fff" stroke-width="12"/><path d="M0 230L256 30" stroke="#b7d8e5" stroke-width="22"/><text x="12" y="25" fill="#69806b" font-size="11">SYNTHETIC TEST TILE</text></svg>');
const mime = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png" };
const checks = [];
const unexpectedRequests = [];
const scriptErrors = [];
const tileRequests = [];
let failTiles = false;

async function until(check, description) {
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    if (await check()) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out: ${description}`);
}

async function loaded(page) {
  await until(async () => !await page.locator("#status").isVisible(), "map tile loading");
  assert.ok(await page.locator(".leaflet-tile-loaded").count() > 0, "at least one tile rendered");
}

async function viewSnapshot(page) {
  return page.evaluate(() => ({
    pane: document.querySelector(".leaflet-map-pane").style.transform,
    tiles: [...document.querySelectorAll(".leaflet-tile")].map(tile => ({ src: tile.src, transform: tile.style.transform })).sort((a, b) => a.src.localeCompare(b.src)),
  }));
}

async function markerCenter(page) {
  return page.locator("path.leaflet-interactive").last().evaluate(element => {
    const box = element.getBoundingClientRect();
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  });
}

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const options = { headless: true };
  const installedChrome = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
  if (process.env.CHROME_EXECUTABLE) options.executablePath = process.env.CHROME_EXECUTABLE;
  else if (fs.existsSync(installedChrome)) options.executablePath = installedChrome;
  const browser = await chromium.launch(options);
  try {
    const context = await browser.newContext({ viewport: { width: 360, height: 300 }, deviceScaleFactor: 2, userAgent: "TalkingFamily/0.4.1 (+https://github.com/jinhoofkepco/TalkingFamily) MapSmokeTest" });
    await context.route("**/*", async route => {
      const request = route.request();
      const url = new URL(request.url());
      if (url.origin === origin && url.pathname.startsWith("/assets/family-map/")) {
        const relative = decodeURIComponent(url.pathname.slice("/assets/family-map/".length));
        const file = path.resolve(assets, relative);
        if (!file.startsWith(assets + path.sep) || !fs.existsSync(file)) return route.fulfill({ status: 404, body: "Not found" });
        return route.fulfill({ status: 200, contentType: mime[path.extname(file)] || "application/octet-stream", body: fs.readFileSync(file) });
      }
      if (url.origin === "https://tile.openstreetmap.org" && /^\/\d+\/\d+\/\d+\.png$/.test(url.pathname) && !url.search) {
        tileRequests.push({ url: request.url(), referer: request.headers().referer, userAgent: request.headers()["user-agent"] });
        return route.fulfill(failTiles ? { status: 503, body: "Synthetic network failure" } : { status: 200, contentType: "image/svg+xml", headers: { "Cache-Control": "public, max-age=604800" }, body: tileSvg });
      }
      unexpectedRequests.push(request.url());
      return route.abort("blockedbyclient");
    });
    const page = await context.newPage();
    page.on("pageerror", error => scriptErrors.push(error.message));
    await page.goto(pageUrl);
    await page.waitForFunction(() => !!window.FamilyMap);
    await page.evaluate(() => window.FamilyMap.recenter());
    assert.equal(tileRequests.length, 0, "no tile request before coordinates arrive");
    assert.equal(await page.locator("#recenter").isDisabled(), true);
    await page.evaluate(() => {
      window.FamilyMap.setLocation(NaN, 127, 10);
      window.FamilyMap.setLocation(37, Infinity, 10);
      window.FamilyMap.setLocation(91, 127, 10);
      window.FamilyMap.setLocation(37, 181, 10);
      window.FamilyMap.setLocation("37", 127, 10);
    });
    assert.equal(tileRequests.length, 0, "invalid points cannot start tile loading");
    assert.equal(await page.locator("path.leaflet-interactive").count(), 0);
    checks.push("No tile fetch for missing or invalid coordinates");

    await page.evaluate(() => window.FamilyMap.setLocation(37.5665, 126.978, 15));
    await loaded(page);
    assert.equal(await page.locator("path.leaflet-interactive").count(), 2, "marker and accuracy circle");
    assert.equal(await page.locator("#recenter").isEnabled(), true);
    const attribution = page.getByRole("link", { name: "OpenStreetMap contributors" });
    assert.equal(await attribution.isVisible(), true);
    assert.equal(await attribution.getAttribute("href"), "https://www.openstreetmap.org/copyright");
    const attributionBox = await attribution.boundingBox();
    assert.ok(attributionBox.x >= 0 && attributionBox.y >= 0 && attributionBox.x + attributionBox.width <= 360 && attributionBox.y + attributionBox.height <= 300);
    assert.equal(await page.locator('a[href*="google"]').count(), 0);
    assert.ok(tileRequests.every(request => request.referer === `${origin}/`), "fixed origin-only Referer, without coordinates");
    assert.ok(tileRequests.every(request => request.userAgent.startsWith("TalkingFamily/")));
    checks.push("Initial marker, accuracy circle, visible attribution and fixed origin Referer");
    await page.screenshot({ path: path.join(output, "map-initial.png") });

    await page.mouse.move(230, 190);
    await page.mouse.down();
    await page.mouse.move(290, 215, { steps: 8 });
    await page.mouse.up();
    // Leaflet inertia settles before preserving the user's resulting viewport.
    await page.waitForTimeout(650);
    await page.getByRole("button", { name: "지도 확대", exact: true }).click();
    await loaded(page);
    await page.waitForTimeout(150);
    const selectedView = await viewSnapshot(page);
    const oldMarker = await markerCenter(page);
    const requestsBeforeUpdate = tileRequests.length;
    await page.evaluate(() => window.FamilyMap.setLocation(37.5668, 126.9785, 25));
    assert.deepEqual(await viewSnapshot(page), selectedView, "position updates preserve chosen pan and zoom");
    const movedMarker = await markerCenter(page);
    assert.ok(Math.hypot(movedMarker.x - oldMarker.x, movedMarker.y - oldMarker.y) > 5, "marker moves to new location");
    assert.equal(tileRequests.length, requestsBeforeUpdate, "marker update does not fetch new viewport tiles");
    checks.push("New point moves marker and preserves user pan/zoom without tile requests");
    await page.getByRole("button", { name: "선택한 기록의 위치로 지도 이동" }).click();
    await loaded(page);
    const centeredMarker = await markerCenter(page);
    assert.ok(Math.abs(centeredMarker.x - 180) < 2 && Math.abs(centeredMarker.y - 150) < 2, "recenter uses latest point");
    assert.equal(page.url(), pageUrl);
    checks.push("Recenter returns to latest point without external navigation");
    await page.screenshot({ path: path.join(output, "map-recentered.png") });

    const beforeHistorySelection = await viewSnapshot(page);
    const zoomBeforeHistorySelection = new URL(beforeHistorySelection.tiles[0].src).pathname.split("/")[1];
    // Native history selection sends the selected point and an explicit recenter in order.
    await page.evaluate(() => {
      window.FamilyMap.setLocation(37.552, 126.965, 9);
      window.FamilyMap.recenter();
    });
    await loaded(page);
    const historyMarker = await markerCenter(page);
    assert.ok(Math.abs(historyMarker.x - 180) < 2 && Math.abs(historyMarker.y - 150) < 2,
      "history selection centers the newly selected point, not the previous point");
    const selectedHistoryView = await viewSnapshot(page);
    assert.notDeepEqual(selectedHistoryView, beforeHistorySelection, "a distant history point changes the viewport");
    assert.ok(selectedHistoryView.tiles.every(tile => new URL(tile.src).pathname.split("/")[1] === zoomBeforeHistorySelection),
      "explicit history selection preserves a zoom level above the minimum focus zoom");
    await page.mouse.move(170, 170);
    await page.mouse.down();
    await page.mouse.move(225, 195, { steps: 8 });
    await page.mouse.up();
    await page.waitForTimeout(650);
    const pannedHistoryMarker = await markerCenter(page);
    assert.ok(Math.hypot(pannedHistoryMarker.x - 180, pannedHistoryMarker.y - 150) > 20,
      "the user can pan away from a historical selection");
    // Selecting that same record again may send only recenter, without a coordinate update.
    await page.evaluate(() => window.FamilyMap.recenter());
    await loaded(page);
    const refocusedHistoryMarker = await markerCenter(page);
    assert.ok(Math.abs(refocusedHistoryMarker.x - 180) < 2 && Math.abs(refocusedHistoryMarker.y - 150) < 2,
      "direct recenter refocuses the existing historical point without setLocation");
    assert.equal(page.url(), pageUrl);
    checks.push("History selection API focuses the selected point and refocuses an unchanged record while preserving zoom");
    await page.screenshot({ path: path.join(output, "map-history-selection.png") });

    await page.setViewportSize({ width: 360, height: 440 });
    await page.reload();
    await page.waitForFunction(() => !!window.FamilyMap);
    const dayPoints = [
      [37.552, 126.965, 9, 1790042400000],
      [37.558, 126.971, 12, 1790044200000],
      [37.5665, 126.978, 15, 1790046000000],
    ];
    await page.evaluate(points => {
      window.FamilyMap.setTimelineInset(132);
      window.FamilyMap.setHistory(points);
      window.FamilyMap.selectHistory(2);
    }, dayPoints);
    await loaded(page);
    const routeLine = page.locator('path[stroke-dasharray]');
    assert.equal(await routeLine.count(), 1, "history draws one dashed point-connection line");
    const routeBox = await routeLine.boundingBox();
    assert.ok(routeBox.width > 20 && routeBox.height > 20 && routeBox.x >= 0 && routeBox.y >= 0 && routeBox.y + routeBox.height <= 308,
      "initial history fits the complete route above the native timeline");
    const insetAttribution = await page.getByRole("link", { name: "OpenStreetMap contributors" }).boundingBox();
    assert.ok(insetAttribution.y + insetAttribution.height <= 308, "attribution stays above native timeline");
    assert.match(await page.locator(".leaflet-tooltip").last().textContent(), /\d{2}:\d{2} 기록/);
    const fullDayView = await viewSnapshot(page);
    await page.evaluate(points => {
      window.FamilyMap.setHistory([...points, [37.568, 126.98, 10, 1790047800000]]);
      window.FamilyMap.selectHistory(3);
    }, dayPoints);
    assert.deepEqual(await viewSnapshot(page), fullDayView, "routine history refresh preserves user viewport");
    assert.equal(await page.evaluate(() => window.FamilyMap.selectHistory(99)), false);
    await page.evaluate(() => { window.FamilyMap.selectHistory(0); window.FamilyMap.recenter(); });
    await loaded(page);
    const scrubbedMarker = await markerCenter(page);
    assert.ok(Math.abs(scrubbedMarker.x - 180) < 2 && Math.abs(scrubbedMarker.y - 154) < 2,
      "scrubbing focuses the exact historical point above the bottom controls");
    await page.getByRole("button", { name: "불러온 위치의 전체 경로 보기" }).click();
    await loaded(page);
    await page.screenshot({ path: path.join(output, "map-day-route.png") });
    await page.evaluate(() => {
      const points = Array.from({ length: 2501 }, (_, index) => [37.55 + index / 100000, 126.97 + index / 100000, 10, 1790042400000 + index * 1000]);
      window.FamilyMap.setHistory(points);
      if (!window.FamilyMap.selectHistory(2500)) throw new Error("Full history selection was truncated");
      window.FamilyMap.recenter();
    });
    await loaded(page);
    assert.ok(await page.locator("path.leaflet-interactive").count() <= 10, "history uses at most eight minor markers plus selected point and accuracy circle");
    const lastFullHistoryMarker = await markerCenter(page);
    assert.ok(Math.abs(lastFullHistoryMarker.x - 180) < 2 && Math.abs(lastFullHistoryMarker.y - 154) < 2,
      "record beyond the drawn vertex limit remains exactly selectable");
    checks.push("Day route fits all points, timeline inset preserves attribution, refresh preserves view, and all 2501 records remain selectable");

    failTiles = true;
    await page.reload();
    await page.waitForFunction(() => !!window.FamilyMap);
    await page.evaluate(() => { window.FamilyMap.setTimelineInset(132); window.FamilyMap.setLocation(37.5665, 126.978, 15); });
    await page.getByRole("button", { name: "다시 보기" }).waitFor({ state: "visible" });
    assert.match(await page.locator("#status-text").textContent(), /불러오지 못했어요/);
    const insetStatus = await page.locator("#status").boundingBox();
    assert.ok(insetStatus.y + insetStatus.height <= 308, "offline status stays above the native timeline");
    await page.screenshot({ path: path.join(output, "map-network-error.png") });
    const failedRequests = tileRequests.length;
    failTiles = false;
    await page.getByRole("button", { name: "다시 보기" }).click();
    await loaded(page);
    assert.ok(tileRequests.length > failedRequests, "retry requests current viewport again");
    assert.equal(await page.getByRole("button", { name: "다시 보기" }).isVisible(), false);
    assert.equal(page.url(), pageUrl);
    checks.push("Tile failure is visible and retry recovers without navigation");
    assert.deepEqual(unexpectedRequests, [], "all network requests stay in the local asset and stub tile allowlist");
    assert.deepEqual(scriptErrors, [], "no unhandled JavaScript errors");
    const report = { passed: checks.length, checks, tileResponsesStubbed: tileRequests.length, realTileRequests: 0, browserVersion: browser.version(), screenshotNote: "All map tiles are synthetic fixtures; no real OSM requests.", output };
    fs.writeFileSync(path.join(output, "map-test-report.json"), JSON.stringify(report, null, 2) + "\n");
    console.log(JSON.stringify(report, null, 2));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
