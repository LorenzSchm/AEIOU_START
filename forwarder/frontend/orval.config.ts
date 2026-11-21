import { defineConfig } from "orval";

const infinite = {
  query: { useInfinite: true, useInfiniteQueryParam: "cursor", useQuery: true },
};

export default defineConfig({
  forwarder: {
    input: { target: "./openapi.json" },
    output: {
      mode: "tags-split",
      target: "src/api/generated/endpoints.ts",
      schemas: "src/api/generated/model",
      client: "react-query",
      httpClient: "fetch",
      clean: true,
      override: {
        mutator: { path: "src/api/fetcher.ts", name: "customFetch" },
        fetch: { includeHttpResponseReturnType: false },
        query: { signal: true },
        operations: {
          listSessions: infinite,
          listSessionEvents: infinite,
          listEvents: infinite,
          listMails: infinite,
          listLlmCalls: infinite,
          listBookings: infinite,
        },
      },
    },
  },
});
