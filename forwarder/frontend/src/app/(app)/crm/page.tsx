import type { Metadata } from "next";
import { CrmView } from "@/components/crm/crm-view";
import { PageBody, PageTitle } from "@/components/ui/panel";

export const metadata: Metadata = { title: "CRM" };

export default function CrmPage() {
  return (
    <PageBody>
      <PageTitle title="CRM" subtitle="Bookings, won and lost quotes, and how fast and cheaply we got there." />
      <CrmView />
    </PageBody>
  );
}
