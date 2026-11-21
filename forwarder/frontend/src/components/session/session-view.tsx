"use client";

import { SearchX } from "lucide-react";
import Link from "next/link";
import { ApiError } from "@/api/fetcher";
import { useGetSession } from "@/api/generated/sessions/sessions";
import { PageBody } from "@/components/ui/panel";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui/states";
import { DraftEditor } from "./draft-editor";
import { SessionHeader } from "./session-header";
import { BidsPanel, BookingCard, CandidatesTable, RfqFacts } from "./side-panels";
import { Timeline } from "./timeline";

export function SessionView({ id }: { id: number }) {
  const { data, isPending, isError, error } = useGetSession(id);

  if (isPending) {
    return (
      <PageBody className="flex flex-col gap-4">
        <Skeleton className="h-40 w-full rounded-[10px]" />
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
          <Skeleton className="h-[480px] rounded-[10px]" />
          <Skeleton className="h-[480px] rounded-[10px]" />
        </div>
      </PageBody>
    );
  }
  if (isError) {
    return (
      <PageBody>
        {error instanceof ApiError && error.status === 404 ? (
          <EmptyState
            icon={SearchX}
            title={`Session ${id} does not exist`}
            action={
              <Link href="/" className="text-xs text-accent hover:underline">
                Back to the live board
              </Link>
            }
          />
        ) : (
          <ErrorState error={error} />
        )}
      </PageBody>
    );
  }

  const { session, rfq, candidates, bids, booking } = data;
  return (
    <PageBody className="flex flex-col gap-4">
      <SessionHeader s={session} />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
        <div className="flex min-w-0 flex-col gap-4">
          <DraftEditor sessionId={id} />
          <Timeline sessionId={id} startedAt={session.createdAt} />
        </div>
        <aside className="flex min-w-0 flex-col gap-4 lg:sticky lg:top-4 lg:self-start">
          {session.type === "RFQ" && <RfqFacts rfq={rfq} />}
          {booking && <BookingCard booking={booking} />}
          {(session.type === "RFQ" || candidates.length > 0) && <CandidatesTable candidates={candidates} currency={session.quotedCurrency} />}
          <BidsPanel bids={bids} />
        </aside>
      </div>
    </PageBody>
  );
}
