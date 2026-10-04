import type { InventoryItemResponse, InventoryPage, InventoryTransactionResponse } from "@/modules/inventory/types";
import { AdminInventoryClient } from "./AdminInventoryClient";

export function InventoryWorkspace({ initialItemsPage, initialTransactionsPage }: {
  initialItemsPage: InventoryPage<InventoryItemResponse>;
  initialTransactionsPage: InventoryPage<InventoryTransactionResponse>;
}) {
  return <AdminInventoryClient initialItemsPage={initialItemsPage} initialTransactionsPage={initialTransactionsPage} />;
}
