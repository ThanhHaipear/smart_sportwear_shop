"use client";

import { useEffect, useMemo, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { getApiErrorMessage } from "@/lib/api-errors";
import { emitSessionChange, getAccessToken, onSessionChange } from "@/lib/session";
import { getCart, removeCartItem, updateCartItem } from "@/modules/cart/api";
import { NO_IMAGE } from "@/modules/ui/placeholder";
import type { CartResponse } from "@/modules/cart/types";
import { saveCheckoutSelection } from "@/modules/checkout/selection";
import { CartRecommendations } from "@/modules/recommendation/CartRecommendations";

export function CartPageClient() {
  const [cart, setCart] = useState<CartResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingItemId, setPendingItemId] = useState<string | null>(null);
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);

  const loadCart = async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await getCart();
      setCart(response);
      setSelectedIds(response.items.map((item) => item.id));
      emitSessionChange();
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể tải giỏ hàng."));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadCart();
  }, []);

  // Track login state so the "save cart" prompt only shows for guests. Deferred
  // off the effect body to avoid a synchronous setState; kept in sync on login/logout.
  useEffect(() => {
    const sync = () => setIsAuthenticated(Boolean(getAccessToken()));
    const timer = setTimeout(sync, 0);
    const unsubscribe = onSessionChange(sync);
    return () => {
      clearTimeout(timer);
      unsubscribe();
    };
  }, []);

  const selectedSubtotal = useMemo(() => cart?.items
    .filter((item) => selectedIds.includes(item.id))
    .reduce((sum, item) => sum + item.lineTotal, 0) ?? 0, [cart, selectedIds]);

  const handleQuantityChange = async (itemId: string, quantity: number) => {
    if (quantity < 1) return;
    setPendingItemId(itemId);
    try {
      const response = await updateCartItem(itemId, quantity);
      setCart(response);
      emitSessionChange();
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể cập nhật số lượng."));
    } finally {
      setPendingItemId(null);
    }
  };

  const handleRemove = async (itemId: string) => {
    setPendingItemId(itemId);
    try {
      const response = await removeCartItem(itemId);
      setCart(response);
      emitSessionChange();
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể xóa sản phẩm khỏi giỏ."));
    } finally {
      setPendingItemId(null);
    }
  };

  return (
    <main className="page-below-header flex-1 border-b border-ivy-hairline">
      <div className="mx-auto max-w-[1368px] px-4 py-12 md:px-0">
        <div className="mb-10">
          <p className="mb-3 text-[13px] uppercase tracking-[0.24em] text-ivy-text-muted">Điểm Đến Thể Thao</p>
          <h1 className="text-[40px] font-semibold uppercase tracking-[0.06em] text-ivy-dark">Giỏ hàng</h1>
        </div>

        {error ? <p className="mb-6 text-[14px] text-[#C62127]">{error}</p> : null}

        <div className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_380px]">
          <section className="overflow-hidden border border-ivy-hairline">
            <div className="hidden grid-cols-[24px_120px_minmax(0,1fr)_140px_140px_120px] border-b border-ivy-hairline px-6 py-4 text-[13px] font-semibold uppercase tracking-[0.04em] text-ivy-text-muted md:grid">
              <span />
              <span>Sản phẩm</span>
              <span>Thông tin</span>
              <span>Đơn giá</span>
              <span>Số lượng</span>
              <span>Tạm tính</span>
            </div>

            {loading ? (
              <div className="px-6 py-10 text-[15px] text-ivy-text">Đang tải giỏ hàng...</div>
            ) : cart && cart.items.length > 0 ? (
              <>
                {cart.items.map((item) => (
                  <div
                    key={item.id}
                    className="grid gap-5 border-b border-ivy-hairline px-6 py-6 md:grid-cols-[24px_120px_minmax(0,1fr)_140px_140px_120px] md:items-center"
                  >
                    <input
                      type="checkbox"
                      aria-label={`Chọn ${item.productName} để thanh toán`}
                      checked={selectedIds.includes(item.id)}
                      onChange={(event) => setSelectedIds((current) => event.target.checked
                        ? [...current, item.id]
                        : current.filter((id) => id !== item.id))}
                      className="size-4 accent-ivy-dark"
                    />
                    <Link href={`/sanpham/${item.productId}`} className="relative aspect-[0.78] w-[120px] overflow-hidden bg-[#f5f5f5]">
                      <Image
                        src={item.thumbnail || NO_IMAGE}
                        alt={item.productName}
                        fill
                        sizes="120px"
                        className="object-cover"
                      />
                    </Link>
                    <div>
                      <p className="mb-3 block text-[18px] font-medium text-ivy-dark">{item.productName}</p>
                      <p className="text-[14px] leading-7 text-ivy-text">Màu sắc: {item.color || "Đang cập nhật"}</p>
                      <p className="text-[14px] leading-7 text-ivy-text">Kích thước: {item.size || "Đang cập nhật"}</p>
                    </div>
                    <div className="text-[20px] font-semibold text-ivy-dark">{item.price.toLocaleString("vi-VN")}đ</div>
                    <div className="flex items-center">
                      <button
                        className="flex h-10 w-10 items-center justify-center border border-ivy-hairline text-ivy-dark disabled:opacity-50"
                        onClick={() => void handleQuantityChange(item.id, item.quantity - 1)}
                        disabled={item.quantity <= 1 || pendingItemId === item.id}
                      >
                        -
                      </button>
                      <div className="flex h-10 w-12 items-center justify-center border-y border-ivy-hairline text-ivy-dark">
                        {item.quantity}
                      </div>
                      <button
                        className="flex h-10 w-10 items-center justify-center border border-ivy-hairline text-ivy-dark disabled:opacity-50"
                        onClick={() => void handleQuantityChange(item.id, item.quantity + 1)}
                        disabled={pendingItemId === item.id}
                      >
                        +
                      </button>
                    </div>
                    <div className="text-[20px] font-semibold text-ivy-dark">{item.lineTotal.toLocaleString("vi-VN")}đ</div>
                    <div className="md:col-span-5">
                      <button
                        className="text-[13px] uppercase tracking-[0.04em] text-ivy-text underline"
                        onClick={() => void handleRemove(item.id)}
                        disabled={pendingItemId === item.id}
                      >
                        Xóa sản phẩm
                      </button>
                    </div>
                  </div>
                ))}

                <div className="flex items-center justify-between px-6 py-5">
                  <Link href="/" className="text-[14px] underline text-ivy-dark">
                    Tiếp tục mua sắm
                  </Link>
                  <button
                    className="text-[14px] text-ivy-text"
                    onClick={() => void Promise.all(cart.items.map((item) => handleRemove(item.id)))}
                  >
                    Xóa tất cả
                  </button>
                </div>
              </>
            ) : (
              <div className="px-6 py-10 text-[15px] text-ivy-text">
                Giỏ hàng của bạn đang trống. Hãy chọn sản phẩm ở trang danh mục hoặc chi tiết sản phẩm.
              </div>
            )}
          </section>

          <aside className="h-fit border border-ivy-hairline px-6 py-8">
            <h2 className="mb-6 text-[28px] font-semibold uppercase tracking-[0.04em] text-ivy-dark">Tóm tắt đơn hàng</h2>
            <div className="space-y-4 border-b border-ivy-hairline pb-6">
              <div className="flex items-center justify-between text-[15px] text-ivy-text">
                <span>Tạm tính</span>
                <span>{selectedSubtotal.toLocaleString("vi-VN")}đ</span>
              </div>
              <div className="flex items-center justify-between text-[15px] text-ivy-text">
                <span>Phí vận chuyển</span>
                <span>Được tính ở bước thanh toán</span>
              </div>
            </div>
            <div className="flex items-center justify-between py-6 text-[24px] font-semibold text-ivy-dark">
              <span>Tổng cộng</span>
              <span>{selectedSubtotal.toLocaleString("vi-VN")}đ</span>
            </div>

            <div className="space-y-4">
              <Link
                href="/thanh-toan"
                onClick={(event) => {
                  if (selectedIds.length === 0) event.preventDefault();
                  else saveCheckoutSelection(selectedIds);
                }}
                className="flex h-12 w-full items-center justify-center rounded-tl-[20px] rounded-br-[20px] bg-ivy-dark text-[14px] font-semibold uppercase tracking-[0.05em] text-white"
              >
                Tiến hành thanh toán
              </Link>
              {!isAuthenticated ? (
                <Link
                  href="/dang-nhap"
                  className="flex h-12 items-center justify-center rounded-tl-[20px] rounded-br-[20px] border border-ivy-dark text-[14px] font-semibold uppercase tracking-[0.05em] text-ivy-dark"
                >
                  Đăng nhập để lưu giỏ
                </Link>
              ) : null}
            </div>
          </aside>
        </div>
        {cart && cart.items.length > 0 ? (
          <CartRecommendations productIds={cart.items.map((item) => item.productId)} />
        ) : null}
      </div>
    </main>
  );
}
