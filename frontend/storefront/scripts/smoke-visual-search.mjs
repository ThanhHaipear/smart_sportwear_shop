import { chromium } from "@playwright/test";

const storefrontUrl = process.env.STOREFRONT_URL ?? "http://localhost:3000";
const imagePath = process.env.VISUAL_SEARCH_SMOKE_IMAGE
  ?? "../../catalog/pilot-v1/assets/PILOT-TOP-001-01.png";
const expectedSlug = process.env.VISUAL_SEARCH_EXPECTED_SLUG
  ?? "ao-chay-bo-nam-aero-navy";

const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE
    ?? "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
});
try {
  const page = await browser.newPage();
  await page.goto(storefrontUrl, { waitUntil: "domcontentloaded", timeout: 60_000 });
  await page.waitForTimeout(1_000);

  const cameraButtons = page.locator("button:has(svg.lucide-camera)");
  let trigger;
  for (let index = 0; index < await cameraButtons.count(); index += 1) {
    const candidate = cameraButtons.nth(index);
    if (await candidate.isVisible()) {
      trigger = candidate;
      break;
    }
  }
  if (!trigger) throw new Error("No visible visual-search trigger was found");
  await trigger.click();
  const dialog = page.locator('[role="dialog"]');
  await dialog.waitFor();
  await dialog
    .locator('input[accept="image/jpeg,image/png,image/webp"]')
    .setInputFiles(imagePath);

  const responsePromise = page.waitForResponse(
    (response) => response.url().includes("/api/v1/products/search-by-image"),
    { timeout: 60_000 },
  );
  await dialog.locator("button.bg-ivy-dark").click();
  const response = await responsePromise;
  if (!response.ok()) throw new Error(`Visual-search API returned HTTP ${response.status()}`);

  const expectedProduct = dialog.locator(`a[href*="${expectedSlug}"]`).first();
  await expectedProduct.waitFor({ timeout: 60_000 });
  const productHrefs = await dialog.locator('a[href*="/sanpham/"]').evaluateAll((anchors) =>
    [...new Set(anchors.map((anchor) => anchor.getAttribute("href")).filter(Boolean))],
  );
  if (productHrefs.length !== 4) throw new Error(`Expected 4 result cards, received ${productHrefs.length}`);
  if (!productHrefs[0]?.includes(expectedSlug)) throw new Error(`Expected ${expectedSlug} to be the first result`);

  console.log(JSON.stringify({
    httpStatus: response.status(),
    resultCards: productHrefs.length,
    expectedTopVisible: true,
    expectedSlug,
  }));
} finally {
  await browser.close();
}
