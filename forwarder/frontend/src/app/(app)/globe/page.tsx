import type { Metadata } from "next";
import { GlobeView } from "@/components/globe/GlobeView";

export const metadata: Metadata = {
  title: "Globe · forwarder",
  description: "Live 3D view of every booked and confirmed air-freight shipment.",
};

export default function GlobePage() {
  return <GlobeView />;
}
