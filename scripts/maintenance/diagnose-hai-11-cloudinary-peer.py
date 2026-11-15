"""Read-only DNS/peer diagnostic for the HAI-11 Cloudinary download."""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import httpx


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "services/search-service"))

from app.services.image_pipeline import _resolve_public_addresses  # noqa: E402


HOST = "res.cloudinary.com"
URL = (
    "https://res.cloudinary.com/dfpgvlovs/image/upload/v1791707168/"
    "smart-sportswear/products/hd4lzf06ikipmkzbgb2o.png"
)


async def inspect(trust_env: bool) -> dict:
    resolved = await _resolve_public_addresses(HOST)
    async with httpx.AsyncClient(trust_env=trust_env) as client:
        async with client.stream("GET", URL) as response:
            stream = response.extensions.get("network_stream")
            peer = stream.get_extra_info("server_addr") or stream.get_extra_info("peername")
            return {
                "trustEnv": trust_env,
                "status": response.status_code,
                "resolved": sorted(resolved),
                "peer": peer[0] if peer else None,
                "matches": bool(peer and peer[0] in resolved),
            }


async def main() -> None:
    print(json.dumps([await inspect(True), await inspect(False)]))


if __name__ == "__main__":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(main())
