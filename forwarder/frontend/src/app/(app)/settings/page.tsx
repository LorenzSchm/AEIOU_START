import type { Metadata } from "next";
import { SettingsView } from "@/components/settings/settings-view";
import { PageBody } from "@/components/ui/panel";

export const metadata: Metadata = { title: "Settings" };

export default function SettingsPage() {
  return (
    <PageBody>
      <SettingsView />
    </PageBody>
  );
}
