import type { Metadata } from "next";
import { CrmView } from "@/components/crm/crm-view";
import { PageBody } from "@/components/ui/panel";

export const metadata: Metadata = { title: "CRM" };

export default function CrmPage() {
  return (
    <PageBody>
      <CrmView />
    </PageBody>
  );
}
