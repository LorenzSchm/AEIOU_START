const backend = process.env.DISPATCH_API_URL || "http://127.0.0.1:8000";

export default {
  poweredByHeader: false,
  async rewrites() {
    return [
      { source: "/api/v1/:path*", destination: backend + "/api/v1/:path*" },
    ];
  },
};
