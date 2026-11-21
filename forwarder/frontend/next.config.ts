import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  // Tailwind CSS 4 runs as a Turbopack loader on every stylesheet.
  turbopack: {
    rules: {
      "*.css": { loaders: ["@tailwindcss/turbopack"], as: "*.css" },
    },
  },
};

export default nextConfig;
