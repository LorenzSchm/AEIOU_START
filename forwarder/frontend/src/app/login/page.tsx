import type { Metadata } from "next";
import { Logo } from "@/components/shell/logo";
import { MOCK_MODE } from "@/lib/mock-mode";
import { LoginForm } from "./login-form";

export const metadata: Metadata = { title: "Log in" };

/** Decorative flight paths from the German origin airports. */
function Routes() {
  const arcs = [
    "M140 420 C 360 120, 760 80, 1040 260",
    "M150 430 C 420 260, 820 300, 1120 520",
    "M130 410 C 260 40, 620 -40, 980 90",
    "M160 440 C 420 520, 760 640, 1080 700",
  ];
  return (
    <svg viewBox="0 0 1200 800" className="absolute inset-0 h-full w-full opacity-60" preserveAspectRatio="xMidYMid slice" aria-hidden>
      {arcs.map((d, i) => (
        <path key={d} d={d} fill="none" className="stroke-accent/25" strokeWidth="1.2" strokeDasharray="3 7">
          <animate attributeName="stroke-dashoffset" from="200" to="0" dur={`${9 + i * 2}s`} repeatCount="indefinite" />
        </path>
      ))}
      <circle cx="140" cy="425" r="4" className="fill-attn" />
      <circle cx="140" cy="425" r="14" className="fill-attn/10" />
    </svg>
  );
}

export default async function LoginPage({ searchParams }: PageProps<"/login">) {
  const params = await searchParams;
  const next = typeof params.next === "string" ? params.next : "/";
  const hint = MOCK_MODE && !process.env.APP_PASSWORD ? "Demo mode: the password is “demo”." : undefined;

  return (
    <main className="relative grid min-h-dvh place-items-center overflow-hidden px-4">
      <Routes />
      <div className="relative w-full max-w-[380px]">
        <div className="mb-8 flex justify-center">
          <Logo />
        </div>
        <div className="panel p-6 sm:p-7">
          <h1 className="mb-5 text-lg font-semibold tracking-tight">Sign in</h1>
          <LoginForm next={next} hint={hint} />
        </div>
      </div>
    </main>
  );
}
