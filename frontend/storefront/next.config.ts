import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  async redirects() {
    return [
      { source: "/danh-muc/ao", destination: "/danh-muc/ao-the-thao", permanent: true },
      { source: "/danh-muc/quan", destination: "/danh-muc/quan-the-thao", permanent: true },
      { source: "/danh-muc/ao-da-bong", destination: "/danh-muc/ao-the-thao?sportType=FOOTBALL", permanent: true },
      { source: "/danh-muc/quan-da-bong", destination: "/danh-muc/quan-the-thao?sportType=FOOTBALL", permanent: true },
      { source: "/danh-muc/ao-chay-bo", destination: "/danh-muc/ao-the-thao?sportType=RUNNING", permanent: true },
      { source: "/danh-muc/quan-chay-bo", destination: "/danh-muc/quan-the-thao?sportType=RUNNING", permanent: true },
      { source: "/danh-muc/giay-da-bong-fg", destination: "/danh-muc/giay-the-thao?sportType=FOOTBALL", permanent: true },
      { source: "/danh-muc/giay-da-bong-tf", destination: "/danh-muc/giay-the-thao?sportType=FOOTBALL", permanent: true },
      { source: "/danh-muc/giay-futsal", destination: "/danh-muc/giay-the-thao?sportType=FOOTBALL", permanent: true },
      { source: "/danh-muc/giay-chay-bo", destination: "/danh-muc/giay-the-thao?sportType=RUNNING", permanent: true },
      { source: "/danh-muc/phu-kien-da-bong", destination: "/danh-muc/phu-kien-the-thao?sportType=FOOTBALL", permanent: true },
    ];
  },
  // Allow devices on the local network to load Next.js development assets and HMR.
  allowedDevOrigins: [
    "172.20.10.4",
    "172.20.10.4:3000",
    "192.168.0.60",
    "192.168.0.60:3000",
  ],
  // Allows CI/build verification to avoid colliding with a running dev server's .next directory.
  distDir: process.env.NEXT_DIST_DIR ?? ".next",
  output: "standalone",
  images: {
    // During local development, let the browser fetch CDN assets directly.
    // This avoids /_next/image 500 responses when the local Next.js process
    // cannot reach Cloudinary, while production keeps Next image optimization.
    unoptimized: process.env.NODE_ENV === "development",
    remotePatterns: [
      {
        protocol: "https",
        hostname: "res.cloudinary.com",
      },
      {
        protocol: "https",
        hostname: "pubcdn.ivymoda.com",
      },
      {
        protocol: "https",
        hostname: "cdn.shopify.com",
      },
      {
        protocol: "https",
        hostname: "picsum.photos",
      },
      {
        protocol: "https",
        hostname: "placehold.co",
      },
    ],
  },
};

export default nextConfig;
