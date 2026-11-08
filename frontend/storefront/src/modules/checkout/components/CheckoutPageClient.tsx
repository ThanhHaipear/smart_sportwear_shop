"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { getApiErrorMessage } from "@/lib/api-errors";
import { useAuthenticated } from "@/lib/use-authenticated";
import { ProvinceWardSelect } from "@/components/shared/ProvinceWardSelect";
import {
  createAddress,
  listAddresses,
  setDefaultAddress,
  type AddressResponse,
} from "@/modules/account/api";
import { createOrder, createVietQrPayment, previewCheckout } from "@/modules/checkout/api";
import { clearCheckoutSelection, loadBuyNowSelection, loadCheckoutSelection } from "@/modules/checkout/selection";
import type { CheckoutPreviewResponse } from "@/modules/checkout/types";

type PaymentMethod = "COD" | "VIETQR";

const EMPTY_ADDRESS_FORM = {
  receiverName: "",
  phone: "",
  province: "",
  ward: "",
  addressLine: "",
};

const EMPTY_INVOICE_FORM = {
  requested: false,
  companyName: "",
  taxCode: "",
  companyAddress: "",
};

// Vietnamese business tax code: 10 digits, optionally with a 3-digit branch
// suffix (e.g. 0123456789-001) - mirrors OrderService.TAX_CODE_PATTERN.
const TAX_CODE_PATTERN = /^\d{10}(-\d{3})?$/;

export function CheckoutPageClient() {
  const router = useRouter();
  const authenticated = useAuthenticated();
  // React state disables the button on the next render. The ref also blocks
  // rapid clicks that arrive before that render has happened.
  const submitLockRef = useRef(false);
  const [addresses, setAddresses] = useState<AddressResponse[]>([]);
  const [selectedAddressId, setSelectedAddressId] = useState("");
  const [addressForm, setAddressForm] = useState(EMPTY_ADDRESS_FORM);
  const [preview, setPreview] = useState<CheckoutPreviewResponse | null>(null);
  const [note, setNote] = useState("");
  const [invoiceForm, setInvoiceForm] = useState(EMPTY_INVOICE_FORM);
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("COD");
  const [cartItemIds] = useState<string[]>(() => loadCheckoutSelection());
  const [buyNow] = useState(() => loadBuyNowSelection());
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [creatingAddress, setCreatingAddress] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const selectedAddress = useMemo(
    () => addresses.find((address) => address.id === selectedAddressId) ?? null,
    [addresses, selectedAddressId],
  );

  const refreshPreview = useCallback(async (addressId?: string) => {
    try {
      const response = await previewCheckout(addressId, cartItemIds, buyNow);
      setPreview(response);
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể tải dữ liệu thanh toán."));
    }
  }, [buyNow, cartItemIds]);

  useEffect(() => {
    const load = async () => {
      if (!authenticated) {
        setLoading(false);
        return;
      }

      try {
        // These calls do not depend on each other. Loading them concurrently
        // keeps a slow address query from delaying the cart summary (and vice versa).
        const [addressList, checkoutPreview] = await Promise.all([
          listAddresses(),
          previewCheckout(undefined, cartItemIds, buyNow),
        ]);
        setAddresses(addressList);
        const defaultAddress = addressList.find((item) => item.isDefault) ?? addressList[0];
        const defaultId = defaultAddress?.id ?? "";
        setSelectedAddressId(defaultId);
        setPreview(checkoutPreview);
      } catch (err) {
        setError(getApiErrorMessage(err, "Không thể tải địa chỉ hoặc giỏ hàng."));
      } finally {
        setLoading(false);
      }
    };

    void load();
  }, [authenticated, buyNow, cartItemIds]);

  const handleCreateAddress = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setCreatingAddress(true);
    setError(null);

    try {
      const address = await createAddress({ ...addressForm, isDefault: addresses.length === 0 });
      const nextAddresses = [address, ...addresses];
      setAddresses(nextAddresses);
      setSelectedAddressId(address.id);
      setAddressForm(EMPTY_ADDRESS_FORM);
      await refreshPreview(address.id);
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể tạo địa chỉ mới."));
    } finally {
      setCreatingAddress(false);
    }
  };

  const handleSetDefault = async (id: string) => {
    try {
      await setDefaultAddress(id);
      const refreshed = await listAddresses();
      setAddresses(refreshed);
      setSelectedAddressId(id);
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể cập nhật địa chỉ mặc định."));
    }
  };

  const handleCreateOrder = async () => {
    if (submitLockRef.current) return;

    if (!selectedAddressId) {
      setError("Vui lòng chọn địa chỉ giao hàng.");
      return;
    }
    if (invoiceForm.requested) {
      if (!invoiceForm.companyName.trim() || !invoiceForm.taxCode.trim() || !invoiceForm.companyAddress.trim()) {
        setError("Vui lòng nhập đầy đủ tên công ty, mã số thuế và địa chỉ để xuất hóa đơn công ty.");
        return;
      }
      if (!TAX_CODE_PATTERN.test(invoiceForm.taxCode.trim())) {
        setError("Mã số thuế không đúng định dạng (10 chữ số, có thể kèm 3 số chi nhánh, ví dụ 0123456789-001).");
        return;
      }
    }

    submitLockRef.current = true;
    setSubmitting(true);
    setError(null);
    setSuccess(null);

    try {
      const order = await createOrder({
        addressId: selectedAddressId,
        paymentMethod,
        note: note || undefined,
        cartItemIds: cartItemIds.length ? cartItemIds : undefined,
        buyNowVariantId: buyNow?.variantId,
        buyNowQuantity: buyNow?.quantity,
        invoiceRequested: invoiceForm.requested,
        invoiceCompanyName: invoiceForm.requested ? invoiceForm.companyName.trim() : undefined,
        invoiceTaxCode: invoiceForm.requested ? invoiceForm.taxCode.trim() : undefined,
        invoiceCompanyAddress: invoiceForm.requested ? invoiceForm.companyAddress.trim() : undefined,
      });
      clearCheckoutSelection();
      if (paymentMethod === "VIETQR") {
        try {
          const payment = await createVietQrPayment(order.id);
          router.replace(`/payment/vietqr/${payment.paymentId}`);
        } catch (paymentError) {
          setSuccess(`Đơn ${order.orderCode} đã được tạo và đang giữ hàng.`);
          setError(getApiErrorMessage(paymentError, "Không thể tạo mã VietQR. Hãy thử thanh toán lại từ đơn hàng."));
          router.replace(`/tai-khoan/don-hang/${order.id}`);
        }
        return;
      }
      router.replace(`/tai-khoan/don-hang/${order.id}`);
    } catch (err) {
      // The create-order request failed, so it is safe to let the customer retry.
      submitLockRef.current = false;
      setError(getApiErrorMessage(err, "Không thể tạo đơn hàng."));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="page-below-header flex-1 border-b border-ivy-hairline">
      <div className="mx-auto max-w-[1368px] px-4 py-12 md:px-0">
        <div className="mb-10">
          <p className="mb-3 text-[13px] uppercase tracking-[0.24em] text-ivy-text-muted">Điểm Đến Thể Thao</p>
          <h1 className="text-[40px] font-semibold uppercase tracking-[0.06em] text-ivy-dark">Thanh toán</h1>
        </div>

        {!authenticated ? (
          <div className="border border-ivy-hairline px-6 py-10 text-[15px] text-ivy-text">
            Bạn cần đăng nhập tài khoản khách hàng để thanh toán.
          </div>
        ) : (
          <>
            {error ? <p className="mb-4 text-[14px] text-[#C62127]">{error}</p> : null}
            {success ? <p className="mb-4 text-[14px] text-[#257A4D]">{success}</p> : null}

            <div className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_400px]">
              <section className="space-y-8">
                <div className="border border-ivy-hairline px-6 py-8">
                  <h2 className="mb-5 text-[26px] font-semibold uppercase tracking-[0.04em] text-ivy-dark">
                    Địa chỉ nhận hàng
                  </h2>

                  {loading ? (
                    <p className="text-[15px] text-ivy-text">Đang tải địa chỉ và dữ liệu thanh toán...</p>
                  ) : addresses.length > 0 ? (
                    <div className="space-y-4">
                      {addresses.map((address) => (
                        <label
                          key={address.id}
                          className="flex cursor-pointer items-start gap-4 border border-ivy-hairline px-4 py-4"
                        >
                          <input
                            type="radio"
                            checked={selectedAddressId === address.id}
                            onChange={() => {
                              setSelectedAddressId(address.id);
                              void refreshPreview(address.id);
                            }}
                            className="mt-1 size-4 accent-ivy-dark"
                          />
                          <div className="flex-1">
                            <div className="flex items-center gap-3">
                              <p className="text-[16px] font-medium text-ivy-dark">{address.receiverName}</p>
                              {address.isDefault ? (
                                <span className="text-[12px] uppercase tracking-[0.06em] text-[#257A4D]">
                                  Mặc định
                                </span>
                              ) : null}
                            </div>
                            <p className="mt-2 text-[14px] text-ivy-text">{address.phone}</p>
                            <p className="mt-1 text-[14px] text-ivy-text">
                              {[address.addressLine, address.ward, address.district, address.province].filter(Boolean).join(", ")}
                            </p>
                            {!address.isDefault ? (
                              <button
                                type="button"
                                onClick={() => void handleSetDefault(address.id)}
                                className="mt-3 text-[12px] uppercase tracking-[0.06em] text-ivy-text underline"
                              >
                                Đặt làm mặc định
                              </button>
                            ) : null}
                          </div>
                        </label>
                      ))}
                    </div>
                  ) : (
                    <p className="mb-6 text-[15px] text-ivy-text">
                      Bạn chưa có địa chỉ nào. Tạo mới ngay bên dưới.
                    </p>
                  )}
                </div>

                <div className="border border-ivy-hairline px-6 py-8">
                  <h2 className="mb-5 text-[26px] font-semibold uppercase tracking-[0.04em] text-ivy-dark">
                    Thêm địa chỉ mới
                  </h2>
                  <form className="grid gap-5 md:grid-cols-2" onSubmit={handleCreateAddress}>
                    <input
                      className="h-12 border border-ivy-hairline px-4 text-[15px] outline-none"
                      placeholder="Họ và tên"
                      value={addressForm.receiverName}
                      onChange={(e) => setAddressForm((current) => ({ ...current, receiverName: e.target.value }))}
                    />
                    <input
                      className="h-12 border border-ivy-hairline px-4 text-[15px] outline-none"
                      placeholder="Số điện thoại"
                      value={addressForm.phone}
                      onChange={(e) => setAddressForm((current) => ({ ...current, phone: e.target.value }))}
                    />
                    <ProvinceWardSelect
                      provinceValue={addressForm.province}
                      wardValue={addressForm.ward}
                      onProvinceChange={(name) => setAddressForm((current) => ({ ...current, province: name }))}
                      onWardChange={(name) => setAddressForm((current) => ({ ...current, ward: name }))}
                    />
                    <input
                      className="h-12 border border-ivy-hairline px-4 text-[15px] outline-none md:col-span-2"
                      placeholder="Địa chỉ chi tiết"
                      value={addressForm.addressLine}
                      onChange={(e) => setAddressForm((current) => ({ ...current, addressLine: e.target.value }))}
                    />
                    <div className="md:col-span-2">
                      <button
                        type="submit"
                        disabled={creatingAddress}
                        className="h-12 rounded-tl-[20px] rounded-br-[20px] border border-ivy-dark px-8 text-[14px] font-semibold uppercase tracking-[0.05em] text-ivy-dark disabled:opacity-60"
                      >
                        {creatingAddress ? "Đang lưu..." : "Lưu địa chỉ"}
                      </button>
                    </div>
                  </form>
                </div>

                <div className="border border-ivy-hairline px-6 py-8">
                  <h2 className="mb-5 text-[26px] font-semibold uppercase tracking-[0.04em] text-ivy-dark">
                    Thông tin bổ sung
                  </h2>
                  <textarea
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                    className="mt-6 min-h-[120px] w-full border border-ivy-hairline px-4 py-3 text-[15px] outline-none"
                    placeholder="Ghi chú đơn hàng"
                  />

                  <label className="mt-6 flex items-center gap-3 text-[15px] text-ivy-text">
                    <input
                      type="checkbox"
                      checked={invoiceForm.requested}
                      onChange={(e) => setInvoiceForm((current) => ({ ...current, requested: e.target.checked }))}
                      className="size-4 accent-ivy-dark"
                    />
                    <span>Xuất hóa đơn công ty</span>
                  </label>
                  {invoiceForm.requested ? (
                    <div className="mt-4 grid gap-4 md:grid-cols-2">
                      <input
                        className="h-12 border border-ivy-hairline px-4 text-[15px] outline-none md:col-span-2"
                        placeholder="Tên công ty"
                        value={invoiceForm.companyName}
                        onChange={(e) => setInvoiceForm((current) => ({ ...current, companyName: e.target.value }))}
                      />
                      <input
                        className="h-12 border border-ivy-hairline px-4 text-[15px] outline-none"
                        placeholder="Mã số thuế (VD: 0123456789)"
                        value={invoiceForm.taxCode}
                        onChange={(e) => setInvoiceForm((current) => ({ ...current, taxCode: e.target.value }))}
                      />
                      <input
                        className="h-12 border border-ivy-hairline px-4 text-[15px] outline-none"
                        placeholder="Địa chỉ công ty"
                        value={invoiceForm.companyAddress}
                        onChange={(e) => setInvoiceForm((current) => ({ ...current, companyAddress: e.target.value }))}
                      />
                    </div>
                  ) : null}
                </div>
              </section>

              <aside className="h-fit border border-ivy-hairline px-6 py-8">
                <h2 className="mb-6 text-[28px] font-semibold uppercase tracking-[0.04em] text-ivy-dark">
                  Đơn hàng của bạn
                </h2>

                <div className="space-y-5 border-b border-ivy-hairline pb-6">
                  {preview?.items?.map((item) => (
                    <div key={item.variantId} className="flex items-start justify-between gap-4">
                      <div>
                        <p className="text-[15px] font-medium text-ivy-dark">{item.productName}</p>
                        <p className="mt-1 text-[14px] text-ivy-text">SKU: {item.sku}</p>
                        <p className="mt-1 text-[14px] text-ivy-text">SL: {item.quantity}</p>
                        {!item.valid && item.errorMessage ? (
                          <p className="mt-1 text-[13px] text-[#C62127]">{item.errorMessage}</p>
                        ) : null}
                      </div>
                      <span className="text-[16px] font-semibold text-ivy-dark">
                        {item.lineTotal.toLocaleString("vi-VN")}đ
                      </span>
                    </div>
                  ))}
                </div>

                <div className="space-y-4 py-6">
                  <div className="flex items-center justify-between text-[15px] text-ivy-text">
                    <span>Tạm tính</span>
                    <span>{(preview?.subtotal ?? 0).toLocaleString("vi-VN")}đ</span>
                  </div>
                  {(preview?.discountAmount ?? 0) > 0 ? (
                    <div className="flex items-center justify-between text-[15px] text-[#257A4D]">
                      <span>Giảm giá combo</span>
                      <span>-{(preview?.discountAmount ?? 0).toLocaleString("vi-VN")}đ</span>
                    </div>
                  ) : null}
                  <div className="flex items-center justify-between text-[15px] text-ivy-text">
                    <span>Phí vận chuyển</span>
                    <span>{(preview?.shippingFee ?? 0).toLocaleString("vi-VN")}đ</span>
                  </div>
                </div>

                <div className="flex items-center justify-between border-t border-ivy-hairline pt-6 text-[24px] font-semibold text-ivy-dark">
                  <span>Tổng cộng</span>
                  <span>{(preview?.totalAmount ?? 0).toLocaleString("vi-VN")}đ</span>
                </div>

                <fieldset className="mt-6 border-t border-ivy-hairline pt-6">
                  <legend className="mb-4 text-[15px] font-semibold uppercase tracking-[0.05em] text-ivy-dark">
                    Phương thức thanh toán
                  </legend>
                  <div className="space-y-3 text-[14px] text-ivy-text">
                    <label className="flex cursor-pointer items-center gap-3">
                      <input
                        type="radio"
                        name="payment"
                        checked={paymentMethod === "COD"}
                        onChange={() => setPaymentMethod("COD")}
                        className="size-4 accent-ivy-dark"
                      />
                      <span>Thanh toán khi nhận hàng (COD)</span>
                    </label>
                    <label className="flex cursor-pointer items-center gap-3">
                      <input
                        type="radio"
                        name="payment"
                        checked={paymentMethod === "VIETQR"}
                        onChange={() => setPaymentMethod("VIETQR")}
                        className="size-4 accent-ivy-dark"
                      />
                      <span>Chuyển khoản ngân hàng qua VietQR</span>
                    </label>
                  </div>
                </fieldset>

                <button
                  disabled={submitting || !selectedAddress || !preview?.canCheckout}
                  onClick={() => void handleCreateOrder()}
                  className="mt-6 h-12 w-full rounded-tl-[20px] rounded-br-[20px] bg-ivy-dark text-[14px] font-semibold uppercase tracking-[0.05em] text-white disabled:opacity-60"
                >
                  {submitting
                    ? "Đang xử lý..."
                    : paymentMethod === "VIETQR"
                      ? "Tạo mã thanh toán VietQR"
                      : "Hoàn tất đặt hàng COD"}
                </button>

                <Link href="/gio-hang" className="mt-4 block text-center text-[14px] text-ivy-dark underline">
                  Quay lại giỏ hàng
                </Link>
              </aside>
            </div>
          </>
        )}
      </div>
    </main>
  );
}
