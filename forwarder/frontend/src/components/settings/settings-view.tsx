"use client";

import { useQueryClient } from "@tanstack/react-query";
import { Plane, SlidersHorizontal, Truck } from "lucide-react";
import { useMemo, useState, type ReactNode } from "react";
import { useListCarriers } from "@/api/generated/carriers/carriers";
import type { Carrier, Setting } from "@/api/generated/model";
import { getListSettingsQueryKey, useListSettings, useUpdateSetting } from "@/api/generated/settings/settings";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input, SearchInput, Segmented, Select, Switch, Textarea } from "@/components/ui/fields";
import { Panel } from "@/components/ui/panel";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { cn } from "@/lib/cn";
import { dateTime } from "@/lib/format";

type FieldSpec =
  | { kind: "number"; label: string; hint: string; min: number; max: number; step: number; unit?: string }
  | { kind: "boolean"; label: string; hint: string }
  | { kind: "select"; label: string; hint: string; options: { value: string; label: string }[] }
  | { kind: "text"; label: string; hint: string };

const KNOWN: Record<string, FieldSpec> = {
  bidWaitSeconds: { kind: "number", label: "Bid wait", hint: "How long to wait for carrier bids before deciding. Quotes within 2:00 get full time points.", min: 0, max: 300, step: 5, unit: "s" },
  routerConfidenceThreshold: { kind: "number", label: "Router confidence threshold", hint: "Below this, a routed mail is marked for attention.", min: 0, max: 1, step: 0.01 },
  autopilot: { kind: "boolean", label: "Autopilot", hint: "Off = draft mode." },
  bidPolicy: {
    kind: "select",
    label: "Bid policy",
    hint: "How carrier bids are weighed against each other.",
    options: [
      { value: "balanced_50_50", label: "Balanced: price and speed 50/50" },
      { value: "cheapest", label: "Cheapest bid" },
      { value: "fastest", label: "Fastest bid" },
    ],
  },
  writerPersonaName: { kind: "text", label: "Writer persona", hint: "The person every outgoing mail is signed by." },
  writerCompany: { kind: "text", label: "Writer company", hint: "Company line in the signature." },
};

function sameValue(a: unknown, b: unknown) {
  return JSON.stringify(a) === JSON.stringify(b);
}

function SettingRow({ setting }: { setting: Setting }) {
  const qc = useQueryClient();
  const toast = useToast();
  const spec = KNOWN[setting.key];
  const [draft, setDraft] = useState<unknown>(setting.value);
  const [jsonText, setJsonText] = useState(() => JSON.stringify(setting.value, null, 2));
  const [jsonError, setJsonError] = useState<string | null>(null);

  // Reset local edits when the server value changes underneath us.
  const [seen, setSeen] = useState(setting.updatedAt);
  if (seen !== setting.updatedAt) {
    setSeen(setting.updatedAt);
    setDraft(setting.value);
    setJsonText(JSON.stringify(setting.value, null, 2));
  }

  const update = useUpdateSetting({
    mutation: {
      onSuccess: (s) => {
        qc.setQueryData<Setting[]>(getListSettingsQueryKey(), (old) => old?.map((x) => (x.key === s.key ? s : x)));
        toast.ok(`${spec?.label ?? s.key} saved`);
      },
      onError: (e) => toast.error(`Could not save ${setting.key}`, e.message),
    },
  });
  const save = (value: unknown) => update.mutate({ key: setting.key, data: { value } });

  let control: ReactNode;
  let dirty = !sameValue(draft, setting.value);
  if (spec?.kind === "boolean") {
    control = <Switch checked={draft === true} onChange={(v) => (setDraft(v), save(v))} label={spec.label} disabled={update.isPending} />;
    dirty = false;
  } else if (spec?.kind === "number") {
    control = (
      <div className="flex items-center gap-2">
        <Input
          type="number"
          min={spec.min}
          max={spec.max}
          step={spec.step}
          value={typeof draft === "number" ? draft : String(draft ?? "")}
          onChange={(e) => setDraft(e.target.value === "" ? "" : Number(e.target.value))}
          className="num w-28 text-right"
          aria-label={spec.label}
        />
        {spec.unit && <span className="text-xs text-subtle">{spec.unit}</span>}
      </div>
    );
  } else if (spec?.kind === "select") {
    control = (
      <Select value={String(draft)} onChange={(e) => setDraft(e.target.value)} aria-label={spec.label} className="w-72">
        {spec.options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </Select>
    );
  } else if (spec?.kind === "text") {
    control = <Input value={String(draft ?? "")} onChange={(e) => setDraft(e.target.value)} className="w-72" aria-label={spec.label} />;
  } else {
    dirty = jsonText !== JSON.stringify(setting.value, null, 2);
    control = (
      <div className="flex w-full max-w-md flex-col gap-1">
        <Textarea
          value={jsonText}
          onChange={(e) => {
            setJsonText(e.target.value);
            try {
              JSON.parse(e.target.value);
              setJsonError(null);
            } catch (err) {
              setJsonError(err instanceof Error ? err.message : "Invalid JSON");
            }
          }}
          rows={Math.min(10, jsonText.split("\n").length + 1)}
          className={cn("num w-full text-xs", jsonError && "border-danger/60")}
          aria-label={setting.key}
          spellCheck={false}
        />
        {jsonError && <p className="text-2xs text-danger">{jsonError}</p>}
      </div>
    );
  }

  const invalid =
    spec?.kind === "number" && (typeof draft !== "number" || Number.isNaN(draft) || draft < spec.min || draft > spec.max);

  return (
    <li className="flex flex-wrap items-center gap-x-6 gap-y-3 px-4 py-3">
      <div className="min-w-[220px] flex-1">
        <div className="flex items-center gap-2">
          <p className="text-[13px] font-medium" title={`${spec?.hint ?? "JSON value"} · updated ${dateTime(setting.updatedAt)}${setting.updatedBy ? ` by ${setting.updatedBy}` : ""}`}>
            {spec?.label ?? setting.key}
          </p>
          <code className="num text-2xs text-subtle">{setting.key}</code>
        </div>

      </div>
      <div className="flex items-center gap-2">
        {control}
        {spec?.kind !== "boolean" && (
          <Button
            size="sm"
            variant={dirty ? "primary" : "secondary"}
            disabled={!dirty || invalid || Boolean(jsonError)}
            loading={update.isPending}
            onClick={() => save(spec ? draft : JSON.parse(jsonText))}
          >
            Save
          </Button>
        )}
      </div>
    </li>
  );
}

function Carriers() {
  const { data, isPending, isError, error } = useListCarriers();
  const [kind, setKind] = useState<"all" | Carrier["kind"]>("all");
  const [q, setQ] = useState("");
  const rows = useMemo(
    () =>
      (data ?? []).filter(
        (c) => (kind === "all" || c.kind === kind) && (!q || [c.name, c.email, c.code, ...c.serves].join(" ").toLowerCase().includes(q.toLowerCase())),
      ),
    [data, kind, q],
  );
  return (
    <Panel
      title="Carriers"
      icon={<Plane />}
      count={data?.length}
      actions={
        <Segmented<"all" | Carrier["kind"]>
          size="xs"
          value={kind}
          onChange={setKind}
          options={[
            { value: "all", label: "All" },
            { value: "airline", label: "Airlines" },
            { value: "trucker", label: "Truckers" },
          ]}
        />
      }
    >
      <div className="border-b border-line px-3.5 py-2.5">
        <SearchInput value={q} onChange={setQ} placeholder="Search carriers, airports or zip zones…" />
      </div>
      {isPending ? (
        <SkeletonRows rows={6} />
      ) : isError ? (
        <ErrorState error={error} />
      ) : rows.length === 0 ? (
        <EmptyState icon={Truck} title="No carriers match" className="py-8" />
      ) : (
        <ul className="max-h-[560px] divide-y divide-line overflow-y-auto">
          {rows.map((c) => (
            <li key={c.id} className="flex items-start gap-3 px-4 py-2.5">
              <span className="mt-0.5 text-subtle">{c.kind === "airline" ? <Plane className="size-3.5" /> : <Truck className="size-3.5" />}</span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <p className="truncate text-[13px] font-medium">{c.name}</p>
                  {c.code && <span className="num text-2xs text-subtle">{c.code}</span>}
                </div>
                <p className="truncate text-xs text-muted">{c.email}</p>
                <div className="mt-1 flex flex-wrap gap-1">
                  {c.serves.map((s) => (
                    <Badge key={s} tone="muted" className="num">
                      {s}
                    </Badge>
                  ))}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

export function SettingsView() {
  const { data, isPending, isError, error } = useListSettings();
  const ordered = useMemo(() => {
    const known = Object.keys(KNOWN);
    return [...(data ?? [])].sort((a, b) => {
      const ia = known.indexOf(a.key);
      const ib = known.indexOf(b.key);
      return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib) || a.key.localeCompare(b.key);
    });
  }, [data]);

  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
      <Panel title="Runtime settings" icon={<SlidersHorizontal />} actions={<span className="text-2xs text-subtle">applied without a redeploy</span>}>
        {isPending ? (
          <SkeletonRows rows={6} />
        ) : isError ? (
          <ErrorState error={error} />
        ) : ordered.length === 0 ? (
          <EmptyState icon={SlidersHorizontal} title="No settings yet" />
        ) : (
          <ul className="divide-y divide-line">
            {ordered.map((s) => (
              <SettingRow key={s.key} setting={s} />
            ))}
          </ul>
        )}
      </Panel>
      <Carriers />
    </div>
  );
}
