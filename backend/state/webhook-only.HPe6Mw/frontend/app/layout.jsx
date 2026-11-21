import { readFileSync } from "node:fs";
import { join } from "node:path";
import { Geist, Geist_Mono } from "next/font/google";
import { EmailTemplateProvider } from "./email-preview";
import "./style.css";

const sans = Geist({ subsets: ["latin"], variable: "--font-sans" });
const mono = Geist_Mono({ subsets: ["latin"], variable: "--font-mono" });
const emailTemplate = readFileSync(join(process.cwd(), "app", "aeiou-mail-template.html"), "utf8");

export const metadata = { title: "AEIOU · Air export dispatch" };

export default function Layout({ children }) {
  return (
    <html lang="en" className={sans.variable + " " + mono.variable}>
      <body>
        <EmailTemplateProvider template={emailTemplate}>{children}</EmailTemplateProvider>
      </body>
    </html>
  );
}
