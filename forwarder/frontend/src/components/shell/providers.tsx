"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { ApiError } from "@/api/fetcher";
import { ToastProvider } from "@/components/ui/toast";
import { LiveProvider } from "@/lib/live/live-provider";
import { ThemeProvider } from "@/lib/theme";

function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // The SSE stream keeps caches fresh; never refetch on a timer or on focus.
        staleTime: 5 * 60_000,
        gcTime: 10 * 60_000,
        refetchOnWindowFocus: false,
        refetchOnReconnect: true,
        retry: (count, err) => !(err instanceof ApiError && err.status < 500) && count < 2,
      },
    },
  });
}

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(makeQueryClient);
  return (
    <ThemeProvider>
      <QueryClientProvider client={client}>
        <ToastProvider>
          <LiveProvider>{children}</LiveProvider>
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
