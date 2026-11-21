import { Geist, Geist_Mono } from "next/font/google";
import "./style.css";

const sans = Geist({ subsets: ["latin"], variable: "--font-sans" });
const mono = Geist_Mono({ subsets: ["latin"], variable: "--font-mono" });

export const metadata = { title: "AEIOU · Air export dispatch" };

export default function Layout({ children }) {
  return (
    <html lang="en" className={sans.variable + " " + mono.variable}>
      <body>{children}</body>
    </html>
  );
}
