import asyncio
import io

import httpx
import pytest
from PIL import Image

from app.config import Settings
from app.services.errors import ImageDownloadError, ImagePipelineError
from app.services.image_pipeline import ImagePipeline


def image_bytes(fmt: str = "PNG", size: tuple[int, int] = (40, 20)) -> bytes:
    output = io.BytesIO()
    Image.new("RGB", size, (15, 70, 180)).save(output, format=fmt)
    return output.getvalue()


def test_normalizes_to_deterministic_jpeg_and_hash():
    pipeline = ImagePipeline(Settings(cloudinary_cloud_name="demo"))
    first = pipeline.normalize(image_bytes())
    second = pipeline.normalize(image_bytes())
    assert first.content.startswith(b"\xff\xd8")
    assert first.sha256 == second.sha256
    assert (first.width, first.height, first.original_format) == (40, 20, "PNG")


def test_downsizes_while_preserving_aspect_ratio():
    pipeline = ImagePipeline(
        Settings(cloudinary_cloud_name="demo", target_image_max_width=100, target_image_max_height=100)
    )
    normalized = pipeline.normalize(image_bytes(size=(400, 200)))
    assert (normalized.width, normalized.height) == (100, 50)


def test_color_signature_ignores_white_catalog_background():
    output = io.BytesIO()
    image = Image.new("RGB", (100, 100), "white")
    for x in range(25, 75):
        for y in range(15, 90):
            image.putpixel((x, y), (245, 220, 20))
    image.save(output, format="PNG")
    normalized = ImagePipeline(Settings(cloudinary_cloud_name="demo")).normalize(output.getvalue())
    assert len(normalized.color_signature) == 14
    assert max(normalized.color_signature[:12]) > normalized.color_signature[13]


@pytest.mark.parametrize(
    "content,mime",
    [(b"not-image", "image/jpeg"), (b"x", "text/html"), (image_bytes(), "image/jpeg")],
)
def test_rejects_bad_content(content: bytes, mime: str):
    pipeline = ImagePipeline(Settings(cloudinary_cloud_name="demo"))
    with pytest.raises(ImagePipelineError):
        pipeline.normalize(content, mime)


def test_rejects_decoded_pixel_limit():
    pipeline = ImagePipeline(Settings(cloudinary_cloud_name="demo", max_image_pixels=100))
    with pytest.raises(ImagePipelineError, match="pixel"):
        pipeline.normalize(image_bytes(size=(20, 20)))


def test_rejects_encoded_byte_limit():
    payload = image_bytes()
    pipeline = ImagePipeline(
        Settings(cloudinary_cloud_name="demo", max_upload_bytes=len(payload) - 1)
    )
    with pytest.raises(ImagePipelineError, match="byte"):
        pipeline.normalize(payload, "image/png")


def test_rejects_pillow_decompression_bomb(monkeypatch):
    # Pillow raises DecompressionBombError above twice MAX_IMAGE_PIXELS. Keep
    # the fixture tiny while exercising the same defensive path as a huge file.
    monkeypatch.setattr(Image, "MAX_IMAGE_PIXELS", 100)
    pipeline = ImagePipeline(Settings(cloudinary_cloud_name="demo", max_image_pixels=10_000))
    with pytest.raises(ImagePipelineError, match="decoded safely"):
        pipeline.normalize(image_bytes(size=(20, 20)), "image/png")


def test_download_stream_enforces_redirect_allowlist_and_normalizes():
    asyncio.run(_test_download_stream_enforces_redirect_allowlist_and_normalizes())


async def _test_download_stream_enforces_redirect_allowlist_and_normalizes():
    payload = image_bytes()

    def handler(request: httpx.Request) -> httpx.Response:
        if "start.jpg" in str(request.url):
            return httpx.Response(302, headers={"location": "/s/files/final.png"})
        return httpx.Response(200, headers={"content-type": "image/png"}, content=payload)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        pipeline = ImagePipeline(
            Settings(cloudinary_cloud_name="demo"),
            client=client,
            dns_resolver=lambda _host: _resolved(),
        )
        result = await pipeline.download_and_normalize("https://cdn.shopify.com/s/files/start.jpg")
    assert result.original_format == "PNG"


async def _resolved() -> set[str]:
    return {"8.8.8.8"}


def test_download_classifies_5xx_as_retryable():
    asyncio.run(_test_download_classifies_5xx_as_retryable())


async def _test_download_classifies_5xx_as_retryable():
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _request: httpx.Response(503))
    ) as client:
        pipeline = ImagePipeline(
            Settings(cloudinary_cloud_name="demo"), client=client, dns_resolver=lambda _host: _resolved()
        )
        with pytest.raises(ImageDownloadError):
            await pipeline.download_and_normalize("https://cdn.shopify.com/s/files/item.jpg")


def test_download_rejects_cross_host_redirect():
    asyncio.run(_test_download_rejects_cross_host_redirect())


async def _test_download_rejects_cross_host_redirect():
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(302, headers={"location": "https://evil.example/item.jpg"})
        )
    ) as client:
        pipeline = ImagePipeline(
            Settings(cloudinary_cloud_name="demo"), client=client, dns_resolver=lambda _host: _resolved()
        )
        with pytest.raises(ImagePipelineError, match="allowlisted"):
            await pipeline.download_and_normalize("https://cdn.shopify.com/s/files/item.jpg")
