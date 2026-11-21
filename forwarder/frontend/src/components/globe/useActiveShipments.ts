"use client";

import { QueryClient, QueryClientContext, useQuery } from "@tanstack/react-query";
import { useContext, useMemo, useState } from "react";
import { buildDemoShipments } from "./demoShipments";
import type { ActiveShipment } from "./types";

// TODO(integration): swap this hand-written fetcher for the orval-generated
// `useListActiveShipments` hook (operationId `listActiveShipments`). Keep the query key
// under ["shipments", ...] so the SSE provider's invalidation on `booking.*` events refreshes it.

const FORCE_MOCK = process.env.NEXT_PUBLIC_MOCK === "1";

async function fetchActiveShipments(): Promise<ActiveShipment[]> {
  const res = await fetch("/api/shipments/active", { headers: { accept: "application/json" } });
  if (!res.ok) throw new Error(`GET /api/shipments/active failed: ${res.status}`);
  const data: unknown = await res.json();
  return Array.isArray(data) ? (data as ActiveShipment[]) : [];
}

export interface ActiveShipmentsResult {
  shipments: ActiveShipment[];
  /** True when the demo fallback is shown instead of live API data. */
  isDemo: boolean;
  isLoading: boolean;
}

export function useActiveShipments(): ActiveShipmentsResult {
  // Use the app's QueryClient (so SSE invalidation of ["shipments"] reaches us); fall back to a
  // private client if the page is ever rendered outside a QueryClientProvider.
  const ctxClient = useContext(QueryClientContext);
  const [fallbackClient] = useState(() => (ctxClient ? null : new QueryClient()));
  const query = useQuery(
    {
      queryKey: ["shipments", "active"],
      queryFn: fetchActiveShipments,
      enabled: !FORCE_MOCK,
      retry: 1,
      staleTime: 15_000,
    },
    ctxClient ?? fallbackClient ?? undefined,
  );
  // Built once per mount so the demo timeline stays stable across re-renders.
  const [demo] = useState(() => buildDemoShipments(Date.now()));

  const live = useMemo(
    () => (query.data ?? []).filter((s) => s.status !== "failed"),
    [query.data],
  );
  const settled = FORCE_MOCK || query.isError || query.isSuccess;
  const useDemo = FORCE_MOCK || (settled && live.length === 0);

  return {
    shipments: useDemo ? demo : live,
    isDemo: useDemo,
    isLoading: !settled,
  };
}
