import { AhAdapter } from "@/stores/ah";
import { JumboAdapter } from "@/stores/jumbo";
import type { StoreHealth } from "@/stores/throttle";
import type { StoreAdapter, StoreCode, StoreInfo } from "@/stores/types";

/**
 * Adding a supermarket = write one adapter implementing StoreAdapter and register it here.
 * The app receives the store list from GET /api/v1/stores, so no app change is required
 * beyond an optional logo.
 */
type RegisteredAdapter = StoreAdapter & { health(): StoreHealth };

let adapters: RegisteredAdapter[] = [new AhAdapter(), new JumboAdapter()];

/** Test hook: swap the live adapters for fakes. */
export function setAdaptersForTests(list: RegisteredAdapter[]) {
  adapters = list;
}

export function allStores(): StoreInfo[] {
  return adapters.map((adapter) => adapter.info);
}

export function getAdapter(code: StoreCode) {
  const adapter = adapters.find((entry) => entry.info.code === code);
  if (!adapter) throw new Error(`Unknown store ${code}`);
  return adapter;
}

export function hasStore(code: string) {
  return adapters.some((entry) => entry.info.code === code);
}

export function storeHealth() {
  return adapters.map((adapter) => adapter.health());
}
