import { chromium, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const storefrontUrl = process.env.STOREFRONT_URL ?? "http://localhost:3000";
const imageBuffer = await readFile(resolve(
  process.cwd(),
  process.env.VISUAL_SEARCH_QA_IMAGE ?? "../../catalog/pilot-v1/assets/PILOT-TOP-001-01.png",
));
const productImage = "/images/ivymoda/products/23b930813fdb472c25dd09fe6f6fdf66.jpg";
const results = [
  result("backend-first", 0.51),
  result("backend-second", 0.99),
  result("backend-third", 0.75),
  result("backend-fourth", 0.60),
];
const report = {
  desktop: [],
  mobile: [],
  requests: { aborted: 0, completed: 0 },
};

const browser = await chromium.launch({
  headless: process.env.PLAYWRIGHT_HEADLESS !== "false",
  executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE
    ?? "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
  args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"],
});

try {
  await verifyUnavailableState();
  await verifyUploadCropAndOrder();
  await verifyLoadingCancellationAndClose();
  await verifyErrorRetryAndEmpty();
  await verifyCameraAndMobile();
  console.log(JSON.stringify({ passed: true, ...report }));
} finally {
  await browser.close();
}

async function newPage({ mobile = false, available = true, searchHandler } = {}) {
  const context = await browser.newContext({
    viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 1000 },
    isMobile: mobile,
    hasTouch: mobile,
    permissions: ["camera"],
  });
  const page = await context.newPage();
  await page.addInitScript(() => {
    window.__visualSearchAbortCount = 0;
    const originalFetch = window.fetch.bind(window);
    window.fetch = (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.includes("/api/v1/products/search-by-image") && init?.signal) {
        init.signal.addEventListener("abort", () => { window.__visualSearchAbortCount += 1; }, { once: true });
      }
      return originalFetch(input, init);
    };
  });
  await page.route("**/api/v1/visual-search/status", (route) => fulfillJson(route, 200, {
    success: true,
    data: { enabled: available, available },
  }));
  await page.route("**/api/v1/products/search-by-image*", async (route) => {
    try {
      if (searchHandler) await searchHandler(route);
      else await fulfillJson(route, 200, { success: true, data: results });
      report.requests.completed += 1;
    } catch (error) {
      if (String(error).includes("Target page, context or browser has been closed")
          || String(error).includes("Route is already handled")) {
        report.requests.aborted += 1;
        return;
      }
      throw error;
    }
  });
  await page.goto(storefrontUrl, { waitUntil: "domcontentloaded", timeout: 60_000 });
  await page.waitForTimeout(1_000);
  return { context, page };
}

async function openDialog(page) {
  const cameraButtons = page.locator("button:has(svg.lucide-camera)");
  for (let index = 0; index < await cameraButtons.count(); index += 1) {
    const candidate = cameraButtons.nth(index);
    if (await candidate.isVisible()) {
      await candidate.click();
      const dialog = page.locator('[role="dialog"]');
      await expect(dialog).toBeVisible();
      return dialog;
    }
  }
  throw new Error("No visible visual-search trigger was found");
}

async function upload(dialog, name, mimeType) {
  await dialog.locator('input[accept="image/jpeg,image/png,image/webp"]').setInputFiles({
    name,
    mimeType,
    buffer: imageBuffer,
  });
  await expect(dialog.locator('input[type="range"]')).toHaveCount(3);
}

async function submit(dialog) {
  await dialog.locator("button.bg-ivy-dark").click();
}

async function verifyUnavailableState() {
  const { context, page } = await newPage({ available: false });
  try {
    await page.waitForTimeout(300);
    const buttons = page.locator("button:has(svg.lucide-camera)");
    for (let index = 0; index < await buttons.count(); index += 1) {
      await expect(buttons.nth(index)).toBeHidden();
    }
    report.desktop.push("unavailable-hidden");
  } finally {
    await context.close();
  }
}

async function verifyUploadCropAndOrder() {
  for (const [name, mimeType] of [
    ["query.jpg", "image/jpeg"],
    ["query.png", "image/png"],
    ["query.webp", "image/webp"],
  ]) {
    const { context, page } = await newPage();
    try {
      const dialog = await openDialog(page);
      await upload(dialog, name, mimeType);
      const ranges = dialog.locator('input[type="range"]');
      await ranges.nth(0).fill("2");
      await ranges.nth(1).fill("25");
      await ranges.nth(2).fill("75");
      await expect(ranges.nth(0)).toHaveValue("2");
      await expect(ranges.nth(1)).toHaveValue("25");
      await expect(ranges.nth(2)).toHaveValue("75");
      await submit(dialog);
      await expect(dialog.locator('a[href*="/sanpham/backend-first"]').first()).toBeVisible();
      const hrefs = await dialog.locator('a[href*="/sanpham/"]').evaluateAll((anchors) =>
        [...new Set(anchors.map((anchor) => anchor.getAttribute("href")).filter(Boolean))],
      );
      expect(hrefs).toEqual([
        "/sanpham/backend-first",
        "/sanpham/backend-second",
        "/sanpham/backend-third",
        "/sanpham/backend-fourth",
      ]);
      report.desktop.push(`upload-${mimeType}`, `crop-${mimeType}`, `backend-order-${mimeType}`);
    } finally {
      await context.close();
    }
  }
}

async function verifyLoadingCancellationAndClose() {
  let release;
  const pending = new Promise((resolvePromise) => { release = resolvePromise; });
  const delayed = async (route) => {
    await pending;
    await fulfillJson(route, 200, { success: true, data: results });
  };
  const first = await newPage({ searchHandler: delayed });
  try {
    const dialog = await openDialog(first.page);
    await upload(dialog, "query.png", "image/png");
    await submit(dialog);
    await expect(dialog.locator('section[aria-busy="true"]')).toBeVisible();
    const cancel = dialog.locator("button", { hasText: "Hủy tìm kiếm" });
    await expect(cancel).toBeVisible();
    await cancel.click();
    await expect(dialog.locator('section[aria-busy="false"]')).toBeVisible();
    const abortCount = await first.page.evaluate(() => window.__visualSearchAbortCount);
    expect(abortCount).toBeGreaterThan(0);
    report.requests.aborted += abortCount;
    release();
    await first.page.waitForTimeout(200);
    await expect(dialog.locator('a[href*="/sanpham/"]')).toHaveCount(0);
    report.desktop.push("loading", "cancel-request");
  } finally {
    release?.();
    await first.context.close();
  }

  let releaseClose;
  const pendingClose = new Promise((resolvePromise) => { releaseClose = resolvePromise; });
  const second = await newPage({
    searchHandler: async (route) => {
      await pendingClose;
      await fulfillJson(route, 200, { success: true, data: results });
    },
  });
  try {
    const dialog = await openDialog(second.page);
    await upload(dialog, "query.png", "image/png");
    await submit(dialog);
    await expect(dialog.locator('section[aria-busy="true"]')).toBeVisible();
    await dialog.locator('button[aria-label="Đóng"]').click();
    await expect(dialog).toBeHidden();
    const abortCount = await second.page.evaluate(() => window.__visualSearchAbortCount);
    expect(abortCount).toBeGreaterThan(0);
    report.requests.aborted += abortCount;
    releaseClose();
    report.desktop.push("close-during-request");
  } finally {
    releaseClose?.();
    await second.context.close();
  }
}

async function verifyErrorRetryAndEmpty() {
  let attempts = 0;
  const retryCase = await newPage({
    searchHandler: async (route) => {
      attempts += 1;
      if (attempts === 1) {
        await fulfillJson(route, 503, { success: false, message: "internal detail" });
      } else {
        await fulfillJson(route, 200, { success: true, data: results });
      }
    },
  });
  try {
    const dialog = await openDialog(retryCase.page);
    await upload(dialog, "query.png", "image/png");
    await submit(dialog);
    await expect(dialog.locator('[role="alert"]')).toBeVisible();
    await dialog.locator('[role="alert"] button').click();
    await expect(dialog.locator('a[href*="/sanpham/backend-first"]').first()).toBeVisible();
    expect(attempts).toBe(2);
    report.desktop.push("error", "retry");
  } finally {
    await retryCase.context.close();
  }

  const emptyCase = await newPage({
    searchHandler: (route) => fulfillJson(route, 200, { success: true, data: [] }),
  });
  try {
    const dialog = await openDialog(emptyCase.page);
    await upload(dialog, "query.png", "image/png");
    await submit(dialog);
    await expect(dialog.locator("strong")).toHaveText("0");
    await expect(dialog.locator('a[href*="/sanpham/"]')).toHaveCount(0);
    report.desktop.push("empty");
  } finally {
    await emptyCase.context.close();
  }
}

async function verifyCameraAndMobile() {
  const { context, page } = await newPage({ mobile: true });
  try {
    const dialog = await openDialog(page);
    const openCamera = dialog.locator("button", { hasText: "Mở camera" });
    await openCamera.click();
    const video = dialog.locator("video");
    await expect(video).toBeVisible();
    await expect.poll(() => video.evaluate((element) => element.videoWidth)).toBeGreaterThan(0);
    await dialog.locator("button", { hasText: "Chụp ảnh" }).click();
    await expect(dialog.locator('input[type="range"]')).toHaveCount(3);
    await submit(dialog);
    await expect(dialog.locator('a[href*="/sanpham/backend-first"]').first()).toBeVisible();
    const box = await dialog.boundingBox();
    expect(box?.width).toBeLessThanOrEqual(390);
    report.mobile.push("camera-capture", "responsive-layout", "result-rendering");
  } finally {
    await context.close();
  }
}

async function fulfillJson(route, status, payload) {
  await route.fulfill({
    status,
    contentType: "application/json",
    headers: {
      "access-control-allow-origin": new URL(storefrontUrl).origin,
      "access-control-allow-credentials": "true",
    },
    body: JSON.stringify(payload),
  });
}

function result(slug, similarity) {
  return {
    product: {
      id: slug,
      name: slug,
      slug,
      thumbnail: productImage,
      minPrice: 100_000,
      maxPrice: 100_000,
      availableQuantity: 3,
      status: "ACTIVE",
      productType: "SPORTS_TOP",
    },
    matchedImageId: `image-${slug}`,
    matchedImageUrl: productImage,
    similarity,
  };
}
