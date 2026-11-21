import type { Metadata } from "next";
import { EventTicker } from "@/components/live/event-ticker";
import { KpiStrip } from "@/components/live/kpi-strip";
import { NeedsHumanQueue } from "@/components/live/needs-human-queue";
import { SessionList } from "@/components/live/session-list";
import { PageBody } from "@/components/ui/panel";

export const metadata: Metadata = { title: "Live" };

export default function LivePage() {
  return (
    <PageBody className="flex flex-col gap-4">
      <KpiStrip />
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
        <div className="flex min-w-0 flex-col gap-4">
          <NeedsHumanQueue />
          <SessionList />
        </div>
        <EventTicker className="xl:sticky xl:top-4 xl:h-[calc(100dvh-8.5rem)] max-xl:max-h-[520px]" />
      </div>
    </PageBody>
  );
}
