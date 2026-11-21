"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useSessionAction } from "@/api/generated/sessions/sessions";
import type { SessionAction } from "@/api/generated/model";
import { useToast } from "@/components/ui/toast";
import { applySession } from "@/lib/live/cache";
import { byPath } from "@/lib/query-keys";

const DONE: Record<SessionAction["action"], string> = {
  approve: "Draft approved and sent",
  edit_and_send: "Your reply was sent",
  reroute: "Mail re-routed",
  take_over: "You have this session now",
  release: "Autopilot is back on",
  resolve: "Marked as resolved",
};

/** The generated mutation plus cache write-back and feedback. */
export function useSessionActions(sessionId: number) {
  const qc = useQueryClient();
  const toast = useToast();
  const m = useSessionAction({
    mutation: {
      onSuccess: (summary, vars) => {
        applySession(qc, summary);
        void qc.invalidateQueries({ predicate: byPath(`/api/sessions/${sessionId}/draft`, `/api/sessions/${sessionId}`) });
        toast.ok(DONE[vars.data.action]);
      },
      onError: (err) => toast.error("Action failed", err.message),
    },
  });
  return {
    run: (data: SessionAction) => m.mutate({ id: sessionId, data }),
    runAsync: (data: SessionAction) => m.mutateAsync({ id: sessionId, data }),
    pending: m.isPending ? m.variables?.data.action : undefined,
  };
}
