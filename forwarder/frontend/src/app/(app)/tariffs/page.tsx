import type { Metadata } from "next";
import { TariffsView } from "@/components/tariffs/tariffs-view";
import { PageBody } from "@/components/ui/panel";

export const metadata: Metadata = { title: "Tariffs" };

export default function TariffsPage() {
  return (
    <PageBody>
      <TariffsView />
    </PageBody>
  );
}
