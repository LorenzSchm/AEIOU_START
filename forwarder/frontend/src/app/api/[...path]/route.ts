import type { NextRequest } from "next/server";
import { MOCK_MODE } from "@/lib/mock-mode";

/**
 * Forwards /api/* to the Spring Boot backend. BACKEND_URL is read per request,
 * so one image works in every environment. Responses are streamed through
 * untouched, which keeps the SSE endpoint unbuffered.
 */
export const dynamic = "force-dynamic";
export const runtime = "nodejs";

type Ctx = { params: Promise<{ path: string[] }> };

const HOP_BY_HOP = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
  "host",
  "cookie",
  "content-length",
]);
// fetch() already decoded the body, so these would lie about it.
const STRIP_FROM_RESPONSE = new Set([...HOP_BY_HOP, "content-encoding", "content-length", "set-cookie"]);

async function forward(request: NextRequest, { params }: Ctx): Promise<Response> {
  const { path } = await params;

  if (MOCK_MODE) {
    const { handleMockRequest } = await import("@/mocks/server");
    return handleMockRequest(request, path);
  }

  const backend = process.env.BACKEND_URL ?? "http://localhost:8080";
  const target = new URL(`/api/${path.map(encodeURIComponent).join("/")}`, backend);
  target.search = request.nextUrl.search;

  const headers = new Headers();
  request.headers.forEach((value, key) => {
    if (!HOP_BY_HOP.has(key.toLowerCase())) headers.set(key, value);
  });
  headers.set("x-forwarded-host", request.headers.get("host") ?? "");

  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: request.method,
      headers,
      body: hasBody ? await request.arrayBuffer() : undefined,
      signal: request.signal,
      cache: "no-store",
      redirect: "manual",
    });
  } catch (err) {
    if (request.signal.aborted) return new Response(null, { status: 499 });
    const message = err instanceof Error ? err.message : "unreachable";
    return Response.json({ error: "backend_unavailable", message: `Backend not reachable: ${message}` }, { status: 502 });
  }

  const out = new Headers();
  upstream.headers.forEach((value, key) => {
    if (!STRIP_FROM_RESPONSE.has(key.toLowerCase())) out.set(key, value);
  });

  if ((upstream.headers.get("content-type") ?? "").includes("text/event-stream")) {
    out.set("Content-Type", "text/event-stream; charset=utf-8");
    out.set("Cache-Control", "no-cache, no-transform");
    out.set("Connection", "keep-alive");
    out.set("X-Accel-Buffering", "no");
  }

  return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: out });
}

export const GET = forward;
export const POST = forward;
export const PUT = forward;
export const PATCH = forward;
export const DELETE = forward;
export const HEAD = forward;
