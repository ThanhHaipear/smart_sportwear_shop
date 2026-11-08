import { VietQrPaymentClient } from "@/modules/payment/VietQrPaymentClient";

export default async function VietQrPaymentPage({
  params,
}: {
  params: Promise<{ paymentId: string }>;
}) {
  const { paymentId } = await params;
  return <VietQrPaymentClient paymentId={paymentId} />;
}
