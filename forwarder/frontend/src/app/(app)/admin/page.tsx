import type { Metadata } from "next";
import { AdminView } from "@/components/admin/admin-view";
import { PageBody, PageTitle } from "@/components/ui/panel";

// Deliberately not linked from the nav: reachable only by typing /admin.
export const metadata: Metadata = { title: "Admin", robots: { index: false, follow: false } };

export default function AdminPage() {
  return (
    <PageBody>
      <PageTitle title="Admin" subtitle="Gateway key and the switches that make this instance live." />
      <AdminView />
    </PageBody>
  );
}
