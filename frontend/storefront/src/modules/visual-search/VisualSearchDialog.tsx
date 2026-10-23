"use client";

import Image from "next/image";
import { Camera, ImagePlus, LoaderCircle, Search, SwitchCamera, X } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError } from "@/lib/api";
import { mapProductListItem } from "@/modules/product/mappers";
import { ProductCard } from "@/modules/product/components/ProductCard";
import type { VisualSearchResult } from "@/modules/product/types";
import { getVisualSearchStatus, searchProductsByImage } from "@/modules/visual-search/api";

const MAX_FILE_BYTES = 5 * 1024 * 1024;
const MAX_RESULTS = 4;
const ACCEPTED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

async function cropImage(source: string, zoom: number, x: number, y: number): Promise<Blob> {
  const image = document.createElement("img");
  image.src = source;
  await image.decode();
  // A compact query image keeps upload and multimodal embedding latency low.
  const output = 768;
  const canvas = document.createElement("canvas");
  canvas.width = output;
  canvas.height = output;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Trình duyệt không hỗ trợ xử lý ảnh.");

  const baseSize = Math.min(image.naturalWidth, image.naturalHeight) / zoom;
  const maxLeft = image.naturalWidth - baseSize;
  const maxTop = image.naturalHeight - baseSize;
  context.drawImage(
    image,
    maxLeft * (x / 100),
    maxTop * (y / 100),
    baseSize,
    baseSize,
    0,
    0,
    output,
    output,
  );
  return new Promise((resolve, reject) =>
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("Không thể tạo ảnh tìm kiếm."))), "image/jpeg", 0.82),
  );
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 429) return "Bạn đã tìm kiếm quá nhanh. Vui lòng thử lại sau ít phút.";
    if (error.status === 413 || error.status === 422) return "Ảnh không hợp lệ hoặc vượt quá giới hạn cho phép.";
    if (error.status >= 500) return "Dịch vụ tìm kiếm ảnh đang tạm gián đoạn. Vui lòng thử lại sau.";
  }
  return error instanceof Error ? error.message : "Không thể tìm kiếm bằng ảnh.";
}

export function VisualSearchDialog({ trigger }: { trigger?: (open: () => void) => ReactNode } = {}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const cameraInputRef = useRef<HTMLInputElement>(null);
  const openerRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const requestRef = useRef<AbortController | null>(null);
  const [availability, setAvailability] = useState<"checking" | "available" | "unavailable">("checking");
  const [open, setOpen] = useState(false);
  const [cameraOpen, setCameraOpen] = useState(false);
  const [cameraStream, setCameraStream] = useState<MediaStream>();
  const [facingMode, setFacingMode] = useState<"user" | "environment">("environment");
  const [source, setSource] = useState<string>();
  const [zoom, setZoom] = useState(1);
  const [positionX, setPositionX] = useState(50);
  const [positionY, setPositionY] = useState(50);
  const [results, setResults] = useState<VisualSearchResult[]>([]);
  const [hasSearched, setHasSearched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  useEffect(() => () => {
    if (source) URL.revokeObjectURL(source);
    streamRef.current?.getTracks().forEach((track) => track.stop());
    requestRef.current?.abort();
  }, [source]);
  useEffect(() => {
    const controller = new AbortController();
    void getVisualSearchStatus(controller.signal)
      .then((status) => setAvailability(status.enabled && status.available ? "available" : "unavailable"))
      .catch(() => {
        if (!controller.signal.aborted) setAvailability("unavailable");
      });
    return () => controller.abort();
  }, []);
  useEffect(() => {
    if (videoRef.current && cameraStream) videoRef.current.srcObject = cameraStream;
  }, [cameraOpen, cameraStream]);
  useEffect(() => {
    if (!open) return;
    const previousFocus = document.activeElement as HTMLElement | null;
    const returnFocus = previousFocus ?? openerRef.current;
    const focusable = () => Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ) ?? []);
    requestAnimationFrame(() => focusable()[0]?.focus());
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        requestRef.current?.abort();
        requestRef.current = null;
        setBusy(false);
        streamRef.current?.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        setCameraStream(undefined);
        setCameraOpen(false);
        setOpen(false);
      }
      if (event.key !== "Tab") return;
      const items = focusable();
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => { document.removeEventListener("keydown", onKeyDown); returnFocus?.focus(); };
  }, [open]);

  function chooseFile(file?: File) {
    setError(undefined);
    setResults([]);
    setHasSearched(false);
    if (!file) return;
    if (!ACCEPTED_TYPES.has(file.type)) return setError("Chỉ hỗ trợ ảnh JPG, PNG hoặc WebP.");
    if (file.size > MAX_FILE_BYTES) return setError("Ảnh phải có dung lượng không quá 5 MB.");
    if (source) URL.revokeObjectURL(source);
    setSource(URL.createObjectURL(file));
    setZoom(1);
    setPositionX(50);
    setPositionY(50);
  }

  function stopCamera() {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setCameraStream(undefined);
    setCameraOpen(false);
  }

  function closeDialog() {
    requestRef.current?.abort();
    requestRef.current = null;
    setBusy(false);
    stopCamera();
    setOpen(false);
  }

  function cancelSearch() {
    requestRef.current?.abort();
    requestRef.current = null;
    setBusy(false);
    setError(undefined);
  }

  async function startCamera(mode: "user" | "environment" = facingMode) {
    setError(undefined);
    if (!navigator.mediaDevices?.getUserMedia) {
      // getUserMedia is unavailable on insecure LAN origins (http://<ip>).
      // A capture-enabled file input still opens the native camera on phones
      // and provides a usable file-picker fallback on desktop browsers.
      cameraInputRef.current?.click();
      return;
    }
    stopCamera();
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: mode }, width: { ideal: 1280 }, height: { ideal: 720 } },
      });
      streamRef.current = stream;
      setFacingMode(mode);
      setCameraStream(stream);
      setCameraOpen(true);
    } catch (cause) {
      const denied = cause instanceof DOMException && (cause.name === "NotAllowedError" || cause.name === "SecurityError");
      setError(denied
        ? "Bạn chưa cấp quyền camera. Hãy cho phép Camera trong thanh địa chỉ rồi thử lại."
        : "Không mở được camera. Hãy kiểm tra camera có đang được ứng dụng khác sử dụng không.");
    }
  }

  async function capturePhoto() {
    const video = videoRef.current;
    if (!video || video.videoWidth === 0 || video.videoHeight === 0) {
      setError("Camera chưa sẵn sàng. Vui lòng đợi một chút rồi chụp lại.");
      return;
    }
    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const context = canvas.getContext("2d");
    if (!context) return setError("Trình duyệt không thể chụp ảnh từ camera.");
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.9));
    if (!blob) return setError("Không thể tạo ảnh chụp từ camera.");
    stopCamera();
    chooseFile(new File([blob], `camera-${Date.now()}.jpg`, { type: "image/jpeg" }));
  }

  async function submit() {
    if (!source) return;
    requestRef.current?.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    setBusy(true);
    setHasSearched(false);
    setError(undefined);
    try {
      const image = await cropImage(source, zoom, positionX, positionY);
      if (controller.signal.aborted) return;
      const searchResults = await searchProductsByImage(image, MAX_RESULTS, controller.signal);
      if (controller.signal.aborted) return;
      // Spring owns the final ranking (including the in-stock-first rule).
      setResults(searchResults.slice(0, MAX_RESULTS));
      setHasSearched(true);
    } catch (cause) {
      if (!controller.signal.aborted) setError(errorMessage(cause));
    } finally {
      if (requestRef.current === controller) {
        requestRef.current = null;
        setBusy(false);
      }
    }
  }

  function reset() {
    cancelSearch();
    if (source) URL.revokeObjectURL(source);
    setSource(undefined);
    setResults([]);
    setHasSearched(false);
    setError(undefined);
  }

  if (availability !== "available") return null;

  return (
    <>
      {trigger ? (
        trigger(() => setOpen(true))
      ) : (
        <button ref={openerRef} type="button" onClick={() => setOpen(true)} className="flex size-8 shrink-0 items-center justify-center rounded-md text-ivy-dark hover:bg-[#f3f3f3]" aria-label="Tìm sản phẩm bằng hình ảnh">
          <Camera className="size-[18px]" />
        </button>
      )}
      {open ? (
        <div className="fixed inset-0 z-[100] flex items-start justify-center overflow-y-auto bg-black/45 px-3 py-6 md:py-12" role="dialog" aria-modal="true" aria-labelledby="visual-search-title" onMouseDown={(event) => { if (event.target === event.currentTarget) closeDialog(); }}>
          <section ref={dialogRef} aria-busy={busy} className="w-full max-w-6xl rounded-2xl bg-white p-5 shadow-2xl md:p-8">
            <header className="mb-6 flex items-start justify-between gap-4">
              <div>
                <h2 id="visual-search-title" className="text-xl font-semibold text-ivy-dark">Tìm kiếm bằng hình ảnh</h2>
                <p className="mt-1 text-sm text-ivy-text-muted">Chọn hoặc chụp ảnh, căn sản phẩm vào khung rồi bắt đầu tìm kiếm.</p>
              </div>
              <button type="button" onClick={closeDialog} className="rounded-full p-2 hover:bg-[#f3f3f3]" aria-label="Đóng"><X className="size-5" /></button>
            </header>

            {cameraOpen ? (
              <div className="mx-auto max-w-3xl">
                <div className="relative aspect-video overflow-hidden rounded-xl bg-black">
                  <video ref={videoRef} autoPlay playsInline muted className={`h-full w-full object-cover ${facingMode === "user" ? "-scale-x-100" : ""}`} />
                </div>
                <div className="mt-4 flex flex-wrap justify-center gap-3">
                  <button type="button" onClick={capturePhoto} className="flex h-11 items-center gap-2 rounded-tl-xl rounded-br-xl bg-ivy-dark px-6 text-sm font-semibold text-white"><Camera className="size-4" />Chụp ảnh</button>
                  <button type="button" onClick={() => startCamera(facingMode === "user" ? "environment" : "user")} className="flex h-11 items-center gap-2 border border-ivy-hairline px-5 text-sm text-ivy-text hover:bg-[#fafafa]"><SwitchCamera className="size-4" />Đổi camera</button>
                  <button type="button" onClick={stopCamera} className="h-11 border border-ivy-hairline px-5 text-sm text-ivy-text hover:bg-[#fafafa]">Hủy</button>
                </div>
              </div>
            ) : !source ? (
              <div className="grid gap-4 sm:grid-cols-2">
                <button type="button" onClick={() => inputRef.current?.click()} className="flex min-h-72 flex-col items-center justify-center rounded-xl border-2 border-dashed border-ivy-hairline bg-[#fafafa] px-6 text-center hover:border-ivy-text-muted">
                  <ImagePlus className="mb-4 size-10 text-ivy-text-muted" />
                  <span className="font-medium text-ivy-dark">Chọn ảnh sản phẩm</span>
                  <span className="mt-2 text-sm text-ivy-text-muted">JPG, PNG hoặc WebP · tối đa 5 MB</span>
                </button>
                <button type="button" onClick={() => startCamera()} className="flex min-h-72 flex-col items-center justify-center rounded-xl border-2 border-dashed border-ivy-hairline bg-[#fafafa] px-6 text-center hover:border-ivy-text-muted">
                  <Camera className="mb-4 size-10 text-ivy-text-muted" />
                  <span className="font-medium text-ivy-dark">Mở camera</span>
                  <span className="mt-2 text-sm text-ivy-text-muted">Chụp trực tiếp bằng camera laptop hoặc điện thoại</span>
                </button>
              </div>
            ) : !hasSearched ? (
              <div className="grid gap-7 md:grid-cols-[minmax(0,1fr)_300px]">
                <div className="relative mx-auto aspect-square w-full max-w-[560px] overflow-hidden rounded-xl bg-[#f3f3f3]">
                  <Image src={source} alt="Ảnh xem trước để tìm kiếm" fill unoptimized className="object-cover" style={{ objectPosition: `${positionX}% ${positionY}%`, transform: `scale(${zoom})` }} />
                  <div className="pointer-events-none absolute inset-4 rounded-lg border-2 border-white shadow-[0_0_0_999px_rgba(0,0,0,.25)]" aria-hidden="true" />
                </div>
                <div className="flex flex-col justify-center gap-5">
                  <label className="text-sm text-ivy-text">Phóng to <input type="range" min="1" max="3" step="0.05" value={zoom} onChange={(e) => setZoom(Number(e.target.value))} className="mt-2 w-full accent-[#221f20]" /></label>
                  <label className="text-sm text-ivy-text">Di chuyển ngang <input type="range" min="0" max="100" value={positionX} onChange={(e) => setPositionX(Number(e.target.value))} className="mt-2 w-full accent-[#221f20]" /></label>
                  <label className="text-sm text-ivy-text">Di chuyển dọc <input type="range" min="0" max="100" value={positionY} onChange={(e) => setPositionY(Number(e.target.value))} className="mt-2 w-full accent-[#221f20]" /></label>
                  <button type="button" onClick={submit} disabled={busy} className="flex h-11 items-center justify-center gap-2 rounded-tl-xl rounded-br-xl bg-ivy-dark px-5 text-sm font-semibold text-white disabled:opacity-60">{busy ? <LoaderCircle className="size-4 animate-spin" /> : <Search className="size-4" />}{busy ? "Đang tìm kiếm..." : error ? "Thử lại" : "Tìm sản phẩm tương tự"}</button>
                  {busy ? <button type="button" onClick={cancelSearch} className="h-10 border border-ivy-hairline px-5 text-sm text-ivy-text hover:bg-[#fafafa]">Hủy tìm kiếm</button> : null}
                  <button type="button" onClick={() => startCamera()} disabled={busy} className="flex h-10 items-center justify-center gap-2 border border-ivy-hairline text-sm text-ivy-text hover:bg-[#fafafa] disabled:opacity-60"><Camera className="size-4" />Chụp lại bằng camera</button>
                  <button type="button" onClick={() => inputRef.current?.click()} disabled={busy} className="h-10 border border-ivy-hairline text-sm text-ivy-text hover:bg-[#fafafa]">Chọn ảnh khác</button>
                </div>
              </div>
            ) : (
              <div>
                <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
                  <p className="text-sm text-ivy-text">Tìm thấy <strong>{results.length}</strong> sản phẩm tương tự</p>
                  <div className="flex flex-wrap gap-2">
                    <button type="button" onClick={submit} disabled={busy} className="border border-ivy-hairline px-4 py-2 text-sm hover:bg-[#fafafa] disabled:opacity-60">Thử lại ảnh này</button>
                    <button type="button" onClick={reset} disabled={busy} className="border border-ivy-hairline px-4 py-2 text-sm hover:bg-[#fafafa] disabled:opacity-60">Tìm bằng ảnh khác</button>
                  </div>
                </div>
                {results.length > 0 ? (
                  <div className="grid grid-cols-2 gap-x-5 gap-y-10 sm:grid-cols-3 lg:grid-cols-4">
                    {results.map((result) => {
                      const product = mapProductListItem({ ...result.product, thumbnail: result.matchedImageUrl || result.product.thumbnail });
                      return (
                        <div
                          key={result.product.id}
                          onClickCapture={(event) => {
                            if ((event.target as HTMLElement).closest("a")) closeDialog();
                          }}
                        >
                          <div className="mb-2 text-xs text-ivy-text-muted">Tương đồng {Math.round(result.similarity * 100)}%</div>
                          <ProductCard product={product} />
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <div className="rounded-xl bg-[#fafafa] px-6 py-16 text-center text-sm text-ivy-text-muted">Không tìm thấy sản phẩm tương tự. Hãy thử ảnh rõ hơn hoặc crop sát vào sản phẩm.</div>
                )}
              </div>
            )}
            {error ? <div role="alert" className="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700"><span>{error}</span>{source && !busy ? <button type="button" onClick={submit} className="border border-red-200 bg-white px-3 py-1.5 font-medium hover:bg-red-100">Thử lại</button> : null}</div> : null}
            <input ref={inputRef} type="file" accept="image/jpeg,image/png,image/webp" className="sr-only" onChange={(event) => { chooseFile(event.target.files?.[0]); event.target.value = ""; }} />
            <input ref={cameraInputRef} type="file" accept="image/*" capture="environment" className="sr-only" onChange={(event) => { chooseFile(event.target.files?.[0]); event.target.value = ""; }} />
          </section>
        </div>
      ) : null}
    </>
  );
}
