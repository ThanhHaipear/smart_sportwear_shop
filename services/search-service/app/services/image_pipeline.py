import asyncio
import hashlib
import io
import socket
from dataclasses import dataclass
from ipaddress import ip_address
from typing import Awaitable, Callable
from urllib.parse import urljoin, urlsplit

import httpx
from PIL import Image, ImageOps, UnidentifiedImageError

from app.config import Settings

from .errors import ImageDownloadError, ImagePipelineError
from .image_source import ImageSourceResolver

Resolver = Callable[[str], Awaitable[set[str]]]
SUPPORTED_FORMATS = {"JPEG", "PNG", "WEBP"}
SUPPORTED_CONTENT_TYPES = {"image/jpeg", "image/png", "image/webp"}
FORMAT_CONTENT_TYPES = {"JPEG": "image/jpeg", "PNG": "image/png", "WEBP": "image/webp"}


@dataclass(frozen=True, slots=True)
class NormalizedImage:
    content: bytes
    sha256: str
    width: int
    height: int
    original_format: str
    color_signature: tuple[float, ...] = ()

    @property
    def pixels(self) -> int:
        return self.width * self.height


def color_signature(image: Image.Image) -> tuple[float, ...]:
    """Compact foreground-aware HSV histogram: 12 hues + black + gray/white."""
    sample = image.copy()
    sample.thumbnail((128, 128), Image.Resampling.BILINEAR)
    hsv = sample.convert("HSV")
    bins = [0.0] * 14
    width, height = hsv.size
    for index, (hue, saturation, value) in enumerate(hsv.getdata()):
        x, y = index % width, index // width
        # Down-weight borders where catalog/query backgrounds and watermarks live.
        central = 1.0 if width * .12 <= x <= width * .88 and height * .08 <= y <= height * .92 else .2
        if value < 45:
            bucket = 12
        elif saturation < 38:
            bucket = 13
        else:
            bucket = min(11, hue * 12 // 256)
        # Saturated garment pixels carry more color information than white backgrounds.
        bins[bucket] += central * (1.0 + saturation / 255.0 * 2.0)
    chromatic = sum(bins[:12])
    if chromatic > 0:
        # Color should dominate neutral studio backgrounds.
        bins[12] = min(bins[12], chromatic * .15)
        bins[13] = min(bins[13], chromatic * .15)
    total = sum(bins) or 1.0
    return tuple(value / total for value in bins)


async def _resolve_public_addresses(host: str) -> set[str]:
    loop = asyncio.get_running_loop()
    records = await loop.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    addresses = {record[4][0] for record in records}
    if not addresses or any(not ip_address(value).is_global for value in addresses):
        raise ImagePipelineError("Image host resolved to a non-public address")
    return addresses


class ImagePipeline:
    def __init__(
        self,
        settings: Settings,
        client: httpx.AsyncClient | None = None,
        dns_resolver: Resolver = _resolve_public_addresses,
    ):
        self.settings = settings
        self.source_resolver = ImageSourceResolver(settings)
        self._client = client
        self._dns_resolver = dns_resolver

    async def download_and_normalize(self, image_url: str, public_id: str | None = None) -> NormalizedImage:
        source = self.source_resolver.resolve(image_url, public_id)
        data, content_type = await self._download(source.download_url)
        return self.normalize(data, content_type)

    async def _download(self, initial_url: str) -> tuple[bytes, str | None]:
        client = self._client or httpx.AsyncClient(
            timeout=self.settings.catalog_download_timeout_seconds,
            follow_redirects=False,
        )
        owns_client = self._client is None
        url = initial_url
        try:
            for redirect_count in range(self.settings.catalog_download_max_redirects + 1):
                self.source_resolver.validate_redirect(url)
                host = urlsplit(url).hostname or ""
                try:
                    resolved = await self._dns_resolver(host)
                except ImagePipelineError:
                    raise
                except (OSError, socket.gaierror) as exc:
                    raise ImageDownloadError("Catalog image DNS resolution failed") from exc
                try:
                    async with client.stream("GET", url, headers={"Accept": "image/jpeg,image/png,image/webp"}) as response:
                        if response.status_code in {301, 302, 303, 307, 308}:
                            if redirect_count >= self.settings.catalog_download_max_redirects:
                                raise ImagePipelineError("Catalog image exceeded redirect limit")
                            location = response.headers.get("location")
                            if not location:
                                raise ImagePipelineError("Catalog image redirect has no location")
                            url = urljoin(url, location)
                            continue
                        if response.status_code == 404:
                            raise ImagePipelineError("Catalog image was not found")
                        if response.status_code == 429 or response.status_code >= 500:
                            raise ImageDownloadError(f"Catalog image upstream returned HTTP {response.status_code}")
                        if response.status_code != 200:
                            raise ImagePipelineError(f"Catalog image returned HTTP {response.status_code}")
                        self._validate_peer(response, resolved)
                        content_type = response.headers.get("content-type", "").split(";", 1)[0].lower()
                        if content_type and content_type not in SUPPORTED_CONTENT_TYPES:
                            raise ImagePipelineError("Catalog response is not a supported image MIME type")
                        content_length = response.headers.get("content-length")
                        if content_length:
                            try:
                                if int(content_length) > self.settings.max_upload_bytes:
                                    raise ImagePipelineError("Catalog image exceeds byte limit")
                            except ValueError as exc:
                                raise ImagePipelineError("Catalog image has an invalid content length") from exc
                        chunks: list[bytes] = []
                        total = 0
                        async for chunk in response.aiter_bytes():
                            total += len(chunk)
                            if total > self.settings.max_upload_bytes:
                                raise ImagePipelineError("Catalog image exceeds byte limit")
                            chunks.append(chunk)
                        return b"".join(chunks), content_type or None
                except (httpx.TimeoutException, httpx.NetworkError) as exc:
                    raise ImageDownloadError("Catalog image download failed") from exc
            raise ImagePipelineError("Catalog image exceeded redirect limit")
        finally:
            if owns_client:
                await client.aclose()

    @staticmethod
    def _validate_peer(response: httpx.Response, resolved: set[str]) -> None:
        stream = response.extensions.get("network_stream")
        if stream is None:  # MockTransport and other in-memory transports.
            return
        peer = stream.get_extra_info("server_addr") or stream.get_extra_info("peername")
        if peer and peer[0] not in resolved:
            raise ImagePipelineError("Image connection peer did not match validated DNS")
        if peer and not ip_address(peer[0]).is_global:
            raise ImagePipelineError("Image connection reached a non-public address")

    def normalize(self, content: bytes, declared_content_type: str | None = None) -> NormalizedImage:
        if not content or len(content) > self.settings.max_upload_bytes:
            raise ImagePipelineError("Image is empty or exceeds byte limit")
        if declared_content_type and declared_content_type.lower() not in SUPPORTED_CONTENT_TYPES:
            raise ImagePipelineError("Unsupported image MIME type")
        try:
            with Image.open(io.BytesIO(content)) as opened:
                original_format = (opened.format or "").upper()
                if original_format not in SUPPORTED_FORMATS:
                    raise ImagePipelineError("Unsupported image format")
                if declared_content_type and FORMAT_CONTENT_TYPES[original_format] != declared_content_type.lower():
                    raise ImagePipelineError("Declared MIME type does not match decoded image")
                width, height = opened.size
                if width <= 0 or height <= 0 or width * height > self.settings.max_image_pixels:
                    raise ImagePipelineError("Image exceeds decoded pixel limit")
                opened.load()
                image = ImageOps.exif_transpose(opened).convert("RGB")
                image.thumbnail(
                    (self.settings.target_image_max_width, self.settings.target_image_max_height),
                    Image.Resampling.LANCZOS,
                )
                signature = color_signature(image)
                output = io.BytesIO()
                image.save(output, format="JPEG", quality=88, optimize=True)
                normalized = output.getvalue()
                return NormalizedImage(
                    content=normalized,
                    sha256=hashlib.sha256(normalized).hexdigest(),
                    width=image.width,
                    height=image.height,
                    original_format=original_format,
                    color_signature=signature,
                )
        except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as exc:
            raise ImagePipelineError("Image could not be decoded safely") from exc
