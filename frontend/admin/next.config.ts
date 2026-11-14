import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  experimental: {
    optimizePackageImports: ["@phosphor-icons/react"]
  },
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "res.cloudinary.com"
      },
      {
        protocol: "https",
        hostname: "cdn.shopify.com"
      },
      {
        protocol: "https",
        hostname: "placehold.co"
      }
    ]
  }
};

export default nextConfig;

