import type { Metadata } from "next";
import { TariffsView } from "@/components/tariffs/tariffs-view";
import { PageBody, PageTitle } from "@/components/ui/panel";

export const metadata: Metadata = { title: "Tariffs" };

export default function TariffsPage() {
  return (
    <PageBody>
      <PageTitle title="Tariffs" subtitle="Rate sheet versions per airline. New versions that fail the sanity checks wait here for a human." />
      <TariffsView />
    </PageBody>
  );
}
