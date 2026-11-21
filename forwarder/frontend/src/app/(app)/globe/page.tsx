import type { Metadata } from "next";
import { GlobeView } from "@/components/globe/GlobeView";

export const metadata: Metadata = {
  title: "Globe · forwarder",
  description: "Live 3D view of every booked and confirmed air-freight shipment.",
};

export default function GlobePage() {
  return (
    <div className="h-full overflow-hidden border-white/10 md:rounded-[14px] md:border">
      <GlobeView />
    </div>
  );
}
