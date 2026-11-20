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
  const trigger = page.getByRole("button", { name: "Tìm sản phẩm bằng hình ảnh", exact: true })
    .or(page.getByRole("button", { name: "Thử ngay", exact: true }))
    .filter({ visible: true })
    .first();
  await trigger.click({ timeout: 30_000 });
  const dialog = page.getByRole("dialog", { name: "Tìm kiếm bằng hình ảnh" });
  await dialog.waitFor();
  await dialog
    .locator('input[accept="image/jpeg,image/png,image/webp"]')
    .setInputFiles(imagePath);

  const responsePromise = page.waitForResponse(
    (response) => response.url().includes("/api/v1/products/search-by-image"),
    { timeout: 60_000 },
  );
  await dialog.getByRole("button", { name: "Tìm sản phẩm tương tự", exact: true }).click();
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
