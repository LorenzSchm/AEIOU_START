import type { Metadata } from "next";
import { EventTicker } from "@/components/live/event-ticker";
import { KpiStrip } from "@/components/live/kpi-strip";
import { SessionList } from "@/components/live/session-list";
import { PageBody } from "@/components/ui/panel";

export const metadata: Metadata = { title: "Live" };

export default function LivePage() {
  return (
    <PageBody className="flex flex-col gap-3">
      <KpiStrip />
      <div className="flex min-w-0 gap-3">
        <SessionList />
        <EventTicker />
      </div>
    </PageBody>
  );
}
