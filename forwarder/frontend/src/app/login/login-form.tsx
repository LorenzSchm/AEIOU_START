"use client";

import { ArrowRight, KeyRound, LoaderCircle } from "lucide-react";
import { useActionState } from "react";
import { login, type LoginState } from "./actions";

export function LoginForm({ next, hint }: { next: string; hint?: string }) {
  const [state, action, pending] = useActionState<LoginState, FormData>(login, { error: null });
  return (
    <form action={action} className="flex flex-col gap-3">
      <input type="hidden" name="next" value={next} />
      <label htmlFor="password" className="eyebrow">
        Control panel password
      </label>
      <div className="relative">
        <KeyRound className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" />
        <input
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          autoFocus
          required
          aria-invalid={state.error ? true : undefined}
          aria-describedby={state.error ? "login-error" : undefined}
          className="h-11 w-full rounded-xl border border-line bg-surface-2 pr-3 pl-10 text-[15px] tracking-wide transition-colors placeholder:text-subtle hover:border-line-strong focus:border-accent/60 focus:bg-surface focus:outline-none"
          placeholder="••••••••"
        />
      </div>
      {state.error && (
        <p id="login-error" role="alert" className="text-xs text-danger">
          {state.error}
        </p>
      )}
      <button
        type="submit"
        disabled={pending}
        className="mt-1 inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-accent text-sm font-semibold text-accent-fg transition hover:bg-accent-strong disabled:opacity-60"
      >
        {pending ? <LoaderCircle className="size-4 animate-spin" /> : null}
        Enter the ops room
        {!pending && <ArrowRight className="size-4" />}
      </button>
      {hint && <p className="text-center text-xs text-subtle">{hint}</p>}
    </form>
  );
}
