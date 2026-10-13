from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from io import BytesIO
from urllib.parse import urlparse

import httpx


ALLOWED_IMAGE_FORMATS = {"JPEG", "PNG", "WEBP", "AVIF"}


@dataclass(frozen=True)
class DownloadedImage:
    content: bytes
    sha256: str
    width: int
    height: int
    format: str


def validate_cloudinary_original_url(url: str, asset_version: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError("Cloudinary source URL must use HTTPS")
    hostname = parsed.hostname.lower()
    if hostname != "res.cloudinary.com":
        raise ValueError(f"Image host is not allowed: {hostname}")
    if not re.search(rf"/v{re.escape(asset_version)}(?:/|$)", parsed.path):
        raise ValueError("Cloudinary URL does not contain the registered asset version")
    if "/image/upload/" not in parsed.path:
        raise ValueError("Cloudinary URL must reference an uploaded image")


def inspect_image(content: bytes, max_pixels: int) -> tuple[int, int, str]:
    try:
        from PIL import Image
    except ImportError as exc:
        raise RuntimeError("Pillow is required by the image worker") from exc
    with Image.open(BytesIO(content)) as image:
        image.verify()
    with Image.open(BytesIO(content)) as image:
        width, height = image.size
        image_format = (image.format or "").upper()
    if width <= 0 or height <= 0 or width * height > max_pixels:
        raise ValueError(f"Image dimensions are not allowed: {width}x{height}")
    if image_format not in ALLOWED_IMAGE_FORMATS:
        raise ValueError(f"Unsupported image format: {image_format}")
    return width, height, image_format


async def download_cloudinary_original(
    url: str,
    asset_version: str,
    *,
    expected_sha256: str | None,
    max_bytes: int,
    max_pixels: int,
    timeout_seconds: float,
) -> DownloadedImage:
    validate_cloudinary_original_url(url, asset_version)
    chunks: list[bytes] = []
    size = 0
    async with httpx.AsyncClient(timeout=timeout_seconds, follow_redirects=False) as client:
        async with client.stream("GET", url, headers={"Accept": "image/*"}) as response:
            response.raise_for_status()
            content_type = response.headers.get("content-type", "").split(";", 1)[0].lower()
            if not content_type.startswith("image/"):
                raise ValueError(f"Cloudinary response is not an image: {content_type or 'missing'}")
            content_length = response.headers.get("content-length")
            if content_length and int(content_length) > max_bytes:
                raise ValueError("Image exceeds EMBEDDING_MAX_IMAGE_BYTES")
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > max_bytes:
                    raise ValueError("Image exceeds EMBEDDING_MAX_IMAGE_BYTES")
                chunks.append(chunk)
    content = b"".join(chunks)
    digest = hashlib.sha256(content).hexdigest()
    if expected_sha256 and digest != expected_sha256.lower():
        raise ValueError("Downloaded image SHA-256 does not match product_images.checksum")
    width, height, image_format = inspect_image(content, max_pixels)
    return DownloadedImage(content, digest, width, height, image_format)
