import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  serverExternalPackages: [
    "@google-cloud/storage",
    "@google-cloud/tasks",
    "pg",
  ],
};

export default nextConfig;
