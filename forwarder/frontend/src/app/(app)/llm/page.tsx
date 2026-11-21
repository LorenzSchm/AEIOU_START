import type { Metadata } from "next";
import { LlmView } from "@/components/llm/llm-view";
import { PageBody, PageTitle } from "@/components/ui/panel";

export const metadata: Metadata = { title: "LLM calls" };

export default function LlmPage() {
  return (
    <PageBody>
      <PageTitle title="LLM calls" subtitle="Every gateway call with its cost, tokens and latency. Full points at $0 per quote, zero at $0.10." />
      <LlmView />
    </PageBody>
  );
}
