import type { Metadata } from "next";
import { Suspense } from "react";
import { Mailbox } from "@/components/mail/mailbox";
import { PageBody, PageTitle } from "@/components/ui/panel";
import { Skeleton } from "@/components/ui/states";

export const metadata: Metadata = { title: "Mailbox" };

export default function MailPage() {
  return (
    <PageBody>
      <PageTitle title="Mailbox" subtitle="Every mail in and out of aeiou@hackloop.mail, linked to its session." />
      <Suspense fallback={<Skeleton className="h-[calc(100dvh-10.5rem)] w-full rounded-[10px]" />}>
        <Mailbox />
      </Suspense>
    </PageBody>
  );
}
