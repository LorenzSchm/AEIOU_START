import type { Metadata } from "next";
import { SettingsView } from "@/components/settings/settings-view";
import { PageBody, PageTitle } from "@/components/ui/panel";

export const metadata: Metadata = { title: "Settings" };

export default function SettingsPage() {
  return (
    <PageBody>
      <PageTitle title="Settings" subtitle="Tuning knobs for the hourly setup changes, and the carrier list from the start kit." />
      <SettingsView />
    </PageBody>
  );
}
