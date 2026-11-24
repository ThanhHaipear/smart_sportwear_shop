# Minimal stand-in for catalog-service: only the public product detail the chatbot fallback reads.
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = {"data": {"id": self.path.rsplit("/", 1)[-1], "relatedProducts": [
            {"id": "99999999-9999-4999-8999-999999999999", "name": "Catalog related (fallback)", "slug": "catalog-related", "minPrice": 50}]}}
        payload = json.dumps(body).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload))); self.end_headers(); self.wfile.write(payload)

HTTPServer(("0.0.0.0", 8092), Handler).serve_forever()
