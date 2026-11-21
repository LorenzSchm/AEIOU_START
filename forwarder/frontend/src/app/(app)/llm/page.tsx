import type { Metadata } from "next";
import { LlmView } from "@/components/llm/llm-view";
import { PageBody } from "@/components/ui/panel";

export const metadata: Metadata = { title: "LLM calls" };

export default function LlmPage() {
  return (
    <PageBody>
      <LlmView />
    </PageBody>
  );
}
