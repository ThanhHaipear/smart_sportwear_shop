import { ApiRequestError } from "@/modules/api/common";

type ApiFieldErrorPayload = {
  field?: string;
  message?: string;
};

type ApiEnvelopePayload = {
  message?: string;
  data?: ApiFieldErrorPayload[] | null;
  errors?: ApiFieldErrorPayload[] | null;
};

const defaultFieldLabels: Record<string, string> = {
  name: "Tên",
  slug: "Slug",
  description: "Mô tả",
  shortDescription: "Mô tả ngắn",
  categoryId: "Danh mục",
  brandId: "Thương hiệu",
  status: "Trạng thái",
  season: "Mùa",
  year: "Năm",
  coverImageUrl: "Ảnh bìa",
  sortOrder: "Thứ tự hiển thị"
};

function formatFieldErrors(items: ApiFieldErrorPayload[], labels?: Record<string, string>) {
  const dictionary = { ...defaultFieldLabels, ...(labels ?? {}) };
  const lines = items
    .map((item) => {
      const message = item.message?.trim();
      if (!message) {
        return null;
      }

      if (!item.field) {
        return message;
      }

      return `${dictionary[item.field] ?? item.field}: ${message}`;
    })
    .filter((value): value is string => Boolean(value));

  return lines.length > 0 ? lines.join(" | ") : null;
}

export function extractAdminError(error: unknown, fallback: string, labels?: Record<string, string>) {
  if (error instanceof ApiRequestError) {
    const payload = error.payload as ApiEnvelopePayload | null;
    const fieldErrors = [
      ...(Array.isArray(payload?.errors) ? payload.errors : []),
      ...(Array.isArray(payload?.data) ? payload.data : [])
    ];
    const fieldMessage = formatFieldErrors(fieldErrors, labels);

    if (fieldMessage) {
      return fieldMessage;
    }

    return payload?.message ?? fallback;
  }

  return fallback;
}
