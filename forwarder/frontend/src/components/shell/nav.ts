import { Bot, ChartColumn, Globe, Mail, Radio, SlidersHorizontal, Table2, type LucideIcon } from "lucide-react";

export interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
  /** Second key of the `g` sequence. */
  key: string;
}

export const NAV: NavItem[] = [
  { href: "/", label: "Live", icon: Radio, key: "l" },
  { href: "/mail", label: "Mailbox", icon: Mail, key: "m" },
  { href: "/crm", label: "CRM", icon: ChartColumn, key: "c" },
  { href: "/globe", label: "Globe", icon: Globe, key: "o" },
  { href: "/llm", label: "LLM calls", icon: Bot, key: "i" },
  { href: "/tariffs", label: "Tariffs", icon: Table2, key: "t" },
  { href: "/settings", label: "Settings", icon: SlidersHorizontal, key: "s" },
];

export function isActive(pathname: string, href: string): boolean {
  if (href === "/") return pathname === "/" || pathname.startsWith("/sessions");
  return pathname === href || pathname.startsWith(`${href}/`);
}
