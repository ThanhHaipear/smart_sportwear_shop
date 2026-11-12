const http = require("http");
const path = require("path");

const projectRoot = path.resolve(__dirname, "..");
const ngrok = require(path.join(
  projectRoot,
  ".tools",
  "ngrok-sdk",
  "node_modules",
  "@ngrok",
  "ngrok",
));

const listenHost = "127.0.0.1";
const listenPort = 18083;
const upstreamHost = "127.0.0.1";
const upstreamPort = 8085;
const webhookPath = "/api/v1/webhooks/sepay";
const maxBodyBytes = 1024 * 1024;

const proxy = http.createServer((request, response) => {
  if (request.method !== "POST" || request.url !== webhookPath) {
    response.writeHead(404, { "content-type": "application/json" });
    response.end('{"success":false}');
    return;
  }

  let receivedBytes = 0;
  const chunks = [];
  request.on("data", (chunk) => {
    receivedBytes += chunk.length;
    if (receivedBytes > maxBodyBytes) {
      request.destroy();
      return;
    }
    chunks.push(chunk);
  });
  request.on("end", () => {
    if (receivedBytes > maxBodyBytes) {
      response.writeHead(413).end();
      return;
    }

    const body = Buffer.concat(chunks);
    const headers = {
      "content-type": request.headers["content-type"] || "application/json",
      "content-length": body.length,
    };
    for (const name of ["x-sepay-signature", "x-sepay-timestamp"]) {
      if (request.headers[name]) headers[name] = request.headers[name];
    }

    const upstream = http.request(
      {
        host: upstreamHost,
        port: upstreamPort,
        path: webhookPath,
        method: "POST",
        headers,
        timeout: 10_000,
      },
      (upstreamResponse) => {
        response.writeHead(upstreamResponse.statusCode || 502, {
          "content-type": upstreamResponse.headers["content-type"] || "application/json",
        });
        upstreamResponse.pipe(response);
      },
    );
    upstream.on("timeout", () => upstream.destroy(new Error("upstream timeout")));
    upstream.on("error", () => {
      if (!response.headersSent) {
        response.writeHead(502, { "content-type": "application/json" });
      }
      response.end('{"success":false}');
    });
    upstream.end(body);
  });
});

async function shutdown(listener, exitCode) {
  try {
    if (listener) await listener.close();
  } finally {
    proxy.close(() => process.exit(exitCode));
  }
}

async function main() {
  await new Promise((resolve, reject) => {
    proxy.once("error", reject);
    proxy.listen(listenPort, listenHost, resolve);
  });

  const config = {
    addr: `http://${listenHost}:${listenPort}`,
    authtoken_from_env: true,
    app_protocol: "http1",
    forwards_to: "smart-sportswear-shop-sepay-webhook",
  };
  if (process.env.NGROK_DOMAIN?.trim()) {
    config.domain = process.env.NGROK_DOMAIN.trim().replace(/^https?:\/\//, "");
  }

  const listener = await ngrok.forward(config);
  const publicUrl = listener.url().replace(/\/$/, "");
  console.log(`Ngrok endpoint: ${publicUrl}`);
  console.log(`SePay webhook: ${publicUrl}${webhookPath}`);
  console.log("Only the exact SePay POST route is exposed; press Ctrl+C to stop.");

  process.once("SIGINT", () => void shutdown(listener, 0));
  process.once("SIGTERM", () => void shutdown(listener, 0));
}

main().catch((error) => {
  console.error(`Cannot start SePay ngrok tunnel: ${error.message}`);
  proxy.close(() => process.exit(1));
});
