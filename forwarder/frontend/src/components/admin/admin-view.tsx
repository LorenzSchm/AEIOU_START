"use client";

import { useQueryClient } from "@tanstack/react-query";
import { CircleCheck, CircleHelp, CircleX, KeyRound, Power, TriangleAlert } from "lucide-react";
import { useState, type FormEvent } from "react";
import { getGetAdminStatusQueryKey, useGetAdminStatus, useRegisterOpenAiKey, useSetLive } from "@/api/generated/admin/admin";
import type { AdminStatus, OpenAiKeyResult } from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/drawer";
import { Input, Switch } from "@/components/ui/fields";
import { Panel } from "@/components/ui/panel";
import { ErrorState, SkeletonRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { cn } from "@/lib/cn";

function KeyCard({ status }: { status: AdminStatus }) {
  const qc = useQueryClient();
  const [key, setKey] = useState("");
  const [result, setResult] = useState<OpenAiKeyResult | null>(null);
  const register = useRegisterOpenAiKey({
    mutation: {
      onSuccess: (r) => {
        setResult(r);
        void qc.invalidateQueries({ queryKey: getGetAdminStatusQueryKey() });
      },
      onError: (e) => setResult({ ok: false, message: e.message }),
    },
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const apiKey = key.trim();
    if (!apiKey) return;
    // The key only lives in this request: never in state beyond the input, never in storage.
    setKey("");
    setResult(null);
    register.mutate({ data: { apiKey } });
  };

  const registered = status.openAiKeyRegistered;
  return (
    <Panel title="OpenAI key for the 5U LLM gateway" icon={<KeyRound />}>
      <div className="flex flex-col gap-4 p-4">
        <div
          className={cn(
            "flex items-start gap-2.5 rounded-lg border px-3 py-2.5 text-[13px]",
            registered === true ? "border-ok/25 bg-ok/[0.06]" : registered === false ? "border-attn/30 bg-attn/[0.07]" : "border-line bg-surface-2",
          )}
        >
          {registered === true ? (
            <CircleCheck className="mt-0.5 size-4 shrink-0 text-ok" />
          ) : registered === false ? (
            <CircleX className="mt-0.5 size-4 shrink-0 text-attn" />
          ) : (
            <CircleHelp className="mt-0.5 size-4 shrink-0 text-subtle" />
          )}
          <div>
            <p className="font-medium">{registered === true ? "A key is registered" : registered === false ? "No key registered" : "Status unknown"}</p>
            {status.openAiKeyMessage && <p className="mt-0.5 text-xs text-muted">{status.openAiKeyMessage}</p>}
          </div>
        </div>

        <form onSubmit={submit} className="flex flex-col gap-2 sm:flex-row" autoComplete="off">
          <Input
            type="password"
            name="openai-key"
            value={key}
            onChange={(e) => setKey(e.target.value)}
            placeholder="sk-…"
            autoComplete="off"
            spellCheck={false}
            aria-label="OpenAI API key"
            className="num flex-1"
          />
          <Button type="submit" variant="primary" loading={register.isPending} disabled={!key.trim()}>
            Register
          </Button>
        </form>
        <p className="text-2xs text-subtle">Sent once to the gateway through the backend. It is not stored in this browser.</p>

        {result && (
          <p role="status" className={cn("flex items-start gap-2 text-[13px]", result.ok ? "text-ok" : "text-danger")}>
            {result.ok ? <CircleCheck className="mt-0.5 size-4 shrink-0" /> : <CircleX className="mt-0.5 size-4 shrink-0" />}
            {result.message}
          </p>
        )}
      </div>
    </Panel>
  );
}

function LiveCard({ status }: { status: AdminStatus }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [confirmAutopilot, setConfirmAutopilot] = useState(false);
  const setLive = useSetLive({
    mutation: {
      onSuccess: (s) => {
        qc.setQueryData(getGetAdminStatusQueryKey(), s);
        toast.ok("Live switches updated");
      },
      onError: (e) => toast.error("Could not change the switches", e.message),
    },
  });
  const inert = !status.envPollerEnabled || !status.envSendEnabled;

  return (
    <Panel title="Live switches" icon={<Power />}>
      {inert && (
        <div className="flex items-start gap-2.5 border-b border-attn/25 bg-attn/[0.08] px-4 py-3 text-[13px]" role="alert">
          <TriangleAlert className="mt-0.5 size-4 shrink-0 text-attn" />
          <div>
            <p className="font-semibold text-attn">This is not the live instance.</p>
            <p className="mt-0.5 text-xs text-fg/85">
              {!status.envPollerEnabled && "POLLER_ENABLED is false"}
              {!status.envPollerEnabled && !status.envSendEnabled && " and "}
              {!status.envSendEnabled && "SEND_ENABLED is false"} in this backend&apos;s environment, so these switches have no effect here.
            </p>
          </div>
        </div>
      )}
      <ul className="divide-y divide-line">
        <li className="flex items-center gap-4 px-4 py-4">
          <div className="flex-1">
            <p className="text-[13px] font-medium">Poll mailbox</p>
            <p className="mt-0.5 text-xs text-muted">Fetch new mail from aeiou@hackloop.mail every second.</p>
          </div>
          <Switch
            checked={status.pollingEnabled}
            onChange={(v) => setLive.mutate({ data: { pollingEnabled: v } })}
            label="Poll mailbox"
            disabled={setLive.isPending}
          />
        </li>
        <li className="flex items-center gap-4 px-4 py-4">
          <div className="flex-1">
            <p className="text-[13px] font-medium">Answer live mails automatically</p>
            <p className="mt-0.5 text-xs text-muted">
              Off means draft mode: the agents still price and write every reply, but a human approves each one before it goes out.
            </p>
          </div>
          <Switch
            checked={status.autopilot}
            onChange={(v) => (v ? setConfirmAutopilot(true) : setLive.mutate({ data: { autopilot: false } }))}
            label="Answer live mails automatically"
            disabled={setLive.isPending}
          />
        </li>
      </ul>

      <Modal open={confirmAutopilot} onClose={() => setConfirmAutopilot(false)} title="Turn on automatic answering?">
        <p className="text-[13px] text-muted">
          From now on, quotes, booking confirmations and tracking answers go to real customers without review. Only the first quote in a thread counts, so a
          wrong one cannot be taken back.
        </p>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setConfirmAutopilot(false)}>
            Cancel
          </Button>
          <Button
            variant="attn"
            loading={setLive.isPending}
            onClick={() => {
              setLive.mutate({ data: { autopilot: true } }, { onSettled: () => setConfirmAutopilot(false) });
            }}
          >
            Yes, answer automatically
          </Button>
        </div>
      </Modal>
    </Panel>
  );
}

export function AdminView() {
  const { data, isPending, isError, error } = useGetAdminStatus();
  if (isPending) return <SkeletonRows rows={6} />;
  if (isError) return <ErrorState error={error} />;
  return (
    <div className="grid max-w-4xl grid-cols-1 gap-4 lg:grid-cols-2">
      <KeyCard status={data} />
      <LiveCard status={data} />
    </div>
  );
}
