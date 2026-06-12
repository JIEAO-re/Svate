import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Emit a self-contained server bundle (.next/standalone) so the Docker
  // runner stage can ship only the runtime files instead of the full
  // node_modules tree (which includes dev-only tooling such as vitest/eslint).
  output: "standalone",
  serverExternalPackages: [
    "@google-cloud/storage",
    "@google-cloud/tasks",
    "pg",
  ],
};

export default nextConfig;
