"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { getApiErrorMessage } from "@/lib/api-errors";
import { getVietQrPayment, type VietQrPaymentResponse } from "@/modules/payment/api";
import { CUSTOMER_ORDER_CHANGED_EVENT } from "@/modules/notifications/types";

const FALLBACK_POLL_INTERVAL_MS = 30_000;
const TERMINAL_STATUSES = new Set(["PAID", "FAILED", "CANCELLED", "REFUNDED"]);

function formatMoney(amount: number, currency: string) {
  return new Intl.NumberFormat("vi-VN", {
    style: "currency",
    currency,
    maximumFractionDigits: 0,
  }).format(amount);
}

function formatCountdown(seconds: number) {
  const safe = Math.max(0, seconds);
  const minutes = Math.floor(safe / 60).toString().padStart(2, "0");
  const remainder = (safe % 60).toString().padStart(2, "0");
  return `${minutes}:${remainder}`;
}

function bankName(bankBin: string) {
  return bankBin === "970422" ? "MB Bank" : `Ngân hàng BIN ${bankBin}`;
}

export function VietQrPaymentClient({ paymentId }: { paymentId: string }) {
  const [payment, setPayment] = useState<VietQrPaymentResponse | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const generatedQrKeyRef = useRef<string | null>(null);

  useEffect(() => {
    let active = true;
    let pollTimer: number | undefined;

    const poll = async () => {
      try {
        const next = await getVietQrPayment(paymentId);
        if (!active) return;
        setPayment(next);
        setError(null);
        setLoading(false);
        if (!TERMINAL_STATUSES.has(next.status)) {
          pollTimer = window.setTimeout(poll, FALLBACK_POLL_INTERVAL_MS);
        }
      } catch (cause) {
        if (!active) return;
        setError(getApiErrorMessage(cause, "Không thể tải thông tin thanh toán VietQR."));
        setLoading(false);
        pollTimer = window.setTimeout(poll, FALLBACK_POLL_INTERVAL_MS);
      }
    };

    void poll();
    return () => {
      active = false;
      if (pollTimer !== undefined) window.clearTimeout(pollTimer);
    };
  }, [paymentId]);

  useEffect(() => {
    const refresh = (event: Event) => {
      const changedOrderId = (event as CustomEvent<{ orderId?: string }>).detail?.orderId;
      if (payment?.orderId && changedOrderId && changedOrderId !== payment.orderId) return;
      void getVietQrPayment(paymentId).then((next) => {
        setPayment(next);
        setError(null);
        setLoading(false);
      }).catch(() => undefined);
    };
    window.addEventListener(CUSTOMER_ORDER_CHANGED_EVENT, refresh);
    return () => window.removeEventListener(CUSTOMER_ORDER_CHANGED_EVENT, refresh);
  }, [payment?.orderId, paymentId]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!payment) return;
    const qrKey = [
      payment.bankBin,
      payment.accountName,
      payment.accountNumber,
      payment.amount,
      payment.transactionRef,
    ].join("|");
    if (generatedQrKeyRef.current === qrKey) return;
    generatedQrKeyRef.current = qrKey;
    const qrInput = {
      bank: payment.bankBin,
      accountName: payment.accountName,
      accountNumber: payment.accountNumber,
      amount: Math.round(payment.amount).toString(),
      memo: payment.transactionRef,
    };
    let active = true;
    const generate = async () => {
      try {
        const { VietQR } = await import("vietqr");
        const result = await new VietQR().genQRCodeBase64({
          ...qrInput,
          media: "png",
        });
        const dataUrl = result.data.data?.qrDataURL;
        if (!dataUrl) throw new Error(result.data.desc || "VietQR generation failed");
        if (active) setQrDataUrl(dataUrl);
      } catch {
        if (active) {
          generatedQrKeyRef.current = null;
          setError("Không thể tạo ảnh QR trên thiết bị này. Bạn vẫn có thể chuyển khoản thủ công.");
        }
      }
    };
    void generate();
    return () => {
      active = false;
    };
  }, [payment]);

  const secondsRemaining = useMemo(() => {
    if (!payment) return 0;
    return Math.max(0, Math.ceil((new Date(payment.expiresAt).getTime() - now) / 1_000));
  }, [now, payment]);
  const expired = payment != null && secondsRemaining === 0 && payment.status === "PENDING";

  const copy = async (label: string, value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(label);
      window.setTimeout(() => setCopied((current) => current === label ? null : current), 1_500);
    } catch {
      setError("Trình duyệt không cho phép sao chép tự động. Vui lòng chọn và sao chép thủ công.");
    }
  };

  if (loading) {
    return <main className="page-below-header mx-auto min-h-[60vh] max-w-3xl px-4 py-16 text-center text-ivy-text">Đang tải mã thanh toán...</main>;
  }

  if (!payment) {
    return (
      <main className="page-below-header mx-auto min-h-[60vh] max-w-3xl px-4 py-16 text-center">
        <p className="text-[#C62127]">{error ?? "Không tìm thấy thanh toán VietQR."}</p>
        <Link href="/tai-khoan" className="mt-8 inline-block border border-ivy-dark px-6 py-3">Về tài khoản</Link>
      </main>
    );
  }

  const paid = payment.status === "PAID";
  const unavailable = ["FAILED", "CANCELLED", "REFUNDED"].includes(payment.status) || expired;

  return (
    <main className="page-below-header min-h-[70vh] border-b border-ivy-hairline">
      <section className="mx-auto max-w-5xl px-4 py-12 md:px-6">
        <div className="mb-8 text-center">
          <p className="text-[13px] uppercase tracking-[0.2em] text-ivy-text-muted">Thanh toán an toàn</p>
          <h1 className="mt-3 text-[32px] font-semibold uppercase tracking-[0.04em] text-ivy-dark">Thanh toán VietQR</h1>
        </div>

        {paid ? (
          <div className="border border-[#257A4D] bg-[#F2FAF5] px-6 py-10 text-center">
            <h2 className="text-2xl font-semibold text-[#257A4D]">Thanh toán thành công</h2>
            <p className="mt-3 text-ivy-text">SePay đã xác nhận giao dịch của bạn.</p>
            <Link href={`/tai-khoan/don-hang/${payment.orderId}`} className="mt-7 inline-flex bg-ivy-dark px-7 py-3 text-white">Xem đơn hàng</Link>
          </div>
        ) : (
          <div className="grid gap-8 border border-ivy-hairline p-6 md:grid-cols-[360px_1fr] md:p-8">
            <div className="flex flex-col items-center justify-center border border-ivy-hairline bg-white p-5">
              {qrDataUrl && !unavailable ? (
                <Image src={qrDataUrl} alt="Mã VietQR thanh toán đơn hàng" width={320} height={320} unoptimized className="h-auto w-full max-w-[320px]" />
              ) : (
                <div className="flex aspect-square w-full max-w-[320px] items-center justify-center bg-[#F5F5F5] px-6 text-center text-ivy-text">
                  {unavailable ? "Mã thanh toán không còn hiệu lực" : "Đang tạo mã QR..."}
                </div>
              )}
              <p className={`mt-4 text-sm font-semibold ${expired ? "text-[#C62127]" : "text-ivy-dark"}`}>
                {expired ? "Đã hết thời gian thanh toán" : `Còn lại ${formatCountdown(secondsRemaining)}`}
              </p>
            </div>

            <div>
              <h2 className="text-xl font-semibold text-ivy-dark">Thông tin chuyển khoản</h2>
              <dl className="mt-5 divide-y divide-ivy-hairline border-y border-ivy-hairline text-sm">
                {[
                  ["Ngân hàng", bankName(payment.bankBin)],
                  ["Chủ tài khoản", payment.accountName],
                  ["Số tài khoản", payment.accountNumber],
                  ["Số tiền", formatMoney(payment.amount, payment.currency)],
                  ["Nội dung", payment.transactionRef],
                ].map(([label, value]) => (
                  <div key={label} className="grid grid-cols-[120px_1fr_auto] items-center gap-3 py-4">
                    <dt className="text-ivy-text-muted">{label}</dt>
                    <dd className="break-all font-semibold text-ivy-dark">{value}</dd>
                    {label !== "Ngân hàng" && label !== "Chủ tài khoản" ? (
                      <button type="button" onClick={() => void copy(label, value)} className="text-xs uppercase tracking-[0.05em] text-ivy-dark underline">
                        {copied === label ? "Đã chép" : "Sao chép"}
                      </button>
                    ) : <span />}
                  </div>
                ))}
              </dl>

              <div className="mt-6 border-l-4 border-[#C62127] bg-[#FFF7F7] px-4 py-4 text-sm leading-6 text-ivy-text">
                Vui lòng chuyển đúng số tiền và giữ nguyên nội dung <strong>{payment.transactionRef}</strong>. Sai thông tin có thể khiến đơn hàng không được xác nhận tự động.
              </div>
              {error ? <p className="mt-4 text-sm text-[#C62127]">{error}</p> : null}
              <div className="mt-7 flex flex-wrap gap-3">
                <Link href={`/tai-khoan/don-hang/${payment.orderId}`} className="border border-ivy-dark px-6 py-3 text-sm">Xem đơn hàng</Link>
                <Link href="/" className="bg-ivy-dark px-6 py-3 text-sm text-white">Tiếp tục mua sắm</Link>
              </div>
            </div>
          </div>
        )}
      </section>
    </main>
  );
}
