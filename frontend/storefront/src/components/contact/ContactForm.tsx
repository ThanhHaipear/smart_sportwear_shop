"use client";

import { useState, type ComponentType, type FormEvent, type InputHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { User, Phone, Mail, FileText, MessageCircle, Lock } from "lucide-react";
import { cn } from "@/lib/utils";

const CAPTCHA_TEXT = "4c0qx";
// Per-character rotation/offset so the mock captcha reads as a distorted image, not plain text.
const CAPTCHA_CHAR_STYLES = [
  { rotate: -8, translateY: 2 },
  { rotate: 6, translateY: -3 },
  { rotate: -4, translateY: 1 },
  { rotate: 9, translateY: -2 },
  { rotate: -6, translateY: 3 },
];

interface IconFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  icon: ComponentType<{ className?: string }>;
}

function IconInput({ icon: Icon, className, ...props }: IconFieldProps) {
  return (
    <div className="relative">
      <Icon className="pointer-events-none absolute left-3.5 top-4.5 size-4 text-[#A8A9AD]" />
      <input
        {...props}
        className={cn(
          "w-full rounded-lg border border-ivy-hairline py-3 pl-10 pr-4 text-sm text-ivy-dark placeholder:text-[#A8A9AD] focus:outline-none focus:ring-1 focus:ring-ivy-dark",
          className
        )}
      />
    </div>
  );
}

interface IconTextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  icon: ComponentType<{ className?: string }>;
}

function IconTextarea({ icon: Icon, className, ...props }: IconTextareaProps) {
  return (
    <div className="relative md:col-span-2">
      <Icon className="pointer-events-none absolute left-3.5 top-4.5 size-4 text-[#A8A9AD]" />
      <textarea
        {...props}
        className={cn(
          "w-full resize-none rounded-lg border border-ivy-hairline py-3 pl-10 pr-4 text-sm text-ivy-dark placeholder:text-[#A8A9AD] focus:outline-none focus:ring-1 focus:ring-ivy-dark",
          className
        )}
      />
    </div>
  );
}

export function ContactForm() {
  const [notice, setNotice] = useState<string | null>(null);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (String(form.get("captcha") ?? "").trim().toLowerCase() !== CAPTCHA_TEXT) {
      setNotice("Mã captcha chưa đúng. Vui lòng kiểm tra lại.");
      return;
    }

    const message = [
      `Họ tên: ${form.get("fullName") ?? ""}`,
      `Điện thoại: ${form.get("phone") ?? ""}`,
      `Email: ${form.get("email") ?? ""}`,
      `Chủ đề: ${form.get("subject") ?? ""}`,
      `Nội dung: ${form.get("message") ?? ""}`,
    ].join("\n");
    window.dispatchEvent(new CustomEvent("storefront:open-chat", { detail: { message } }));
    setNotice("Nội dung đã được chuyển sang cửa sổ chat. Vui lòng nhấn Gửi để hoàn tất.");
  }

  return (
    <div className="rounded-2xl border border-ivy-hairline p-5 md:p-10">
      <h2 className="mb-3 text-[28px] font-bold text-ivy-dark">Gửi yêu cầu hỗ trợ</h2>
      <p className="mb-6 text-sm leading-5.5 text-ivy-text">
        Hãy cho chúng tôi biết vấn đề bạn đang gặp phải. Nội dung sẽ được chuyển sang trợ lý trực tuyến để hỗ trợ nhanh hơn.
      </p>

      <form
        onSubmit={handleSubmit}
        className="grid grid-cols-1 gap-4 md:grid-cols-2"
      >
        <IconInput icon={User} type="text" name="fullName" placeholder="Họ và tên" required />
        <IconInput icon={Phone} type="tel" name="phone" placeholder="Điện thoại" required />
        <IconInput icon={Mail} type="email" name="email" placeholder="Địa chỉ email" required />
        <IconInput icon={FileText} type="text" name="subject" placeholder="Chủ đề" required />
        <IconTextarea
          icon={MessageCircle}
          name="message"
          placeholder="Nội dung"
          rows={4}
          required
        />

        <div className="flex flex-col items-center gap-4 sm:flex-row md:col-span-2">
          <div className="relative w-full flex-1">
            <Lock className="pointer-events-none absolute left-3.5 top-4.5 size-4 text-[#A8A9AD]" />
            <input
              type="text"
              name="captcha"
              placeholder="Nhập mã captcha"
              required
              className="w-full rounded-lg border border-ivy-hairline py-3 pl-10 pr-4 text-sm text-ivy-dark placeholder:text-[#A8A9AD] focus:outline-none focus:ring-1 focus:ring-ivy-dark"
            />
          </div>

          <div
            aria-hidden="true"
            className="flex h-10 w-30 shrink-0 select-none items-center justify-center gap-px rounded-md bg-[#F1F1F2] font-mono text-base font-semibold text-[#6C6D70]"
          >
            {CAPTCHA_TEXT.split("").map((char, index) => {
              const style = CAPTCHA_CHAR_STYLES[index % CAPTCHA_CHAR_STYLES.length];
              return (
                <span
                  key={`${char}-${index}`}
                  style={{
                    transform: `rotate(${style.rotate}deg) translateY(${style.translateY}px)`,
                  }}
                  className="inline-block"
                >
                  {char}
                </span>
              );
            })}
          </div>
        </div>

        {notice ? <p role="status" className="text-sm text-ivy-accent md:col-span-2">{notice}</p> : null}

        <button
          type="submit"
          className="rounded-lg bg-ivy-dark px-10 py-3.5 text-sm font-semibold uppercase tracking-[1px] text-white md:col-span-2"
        >
          GỬI
        </button>
      </form>
    </div>
  );
}
