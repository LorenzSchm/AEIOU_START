"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { AUTH_COOKIE, AUTH_MAX_AGE_SECONDS, checkPassword, configuredPassword, sessionToken } from "@/lib/auth";

export interface LoginState {
  error: string | null;
}

function safeNext(value: FormDataEntryValue | null): string {
  const next = typeof value === "string" ? value : "/";
  // Only same-site relative paths, never "//host" or absolute URLs.
  return next.startsWith("/") && !next.startsWith("//") ? next : "/";
}

export async function login(_prev: LoginState, form: FormData): Promise<LoginState> {
  const password = form.get("password");
  if (typeof password !== "string" || password.length === 0) return { error: "Enter the password." };
  const configured = configuredPassword();
  if (!configured) return { error: "APP_PASSWORD is not set on the server." };
  if (!(await checkPassword(password))) return { error: "That password is not right." };

  const jar = await cookies();
  jar.set(AUTH_COOKIE, await sessionToken(configured), {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.COOKIE_SECURE === "1",
    path: "/",
    maxAge: AUTH_MAX_AGE_SECONDS,
  });
  redirect(safeNext(form.get("next")));
}

export async function logout(): Promise<void> {
  const jar = await cookies();
  jar.delete(AUTH_COOKIE);
  redirect("/login");
}
