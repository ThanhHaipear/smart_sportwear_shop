import asyncio
import hashlib
from unittest.mock import AsyncMock, Mock

import pytest

from app.config import Settings

from app.services.errors import ImagePipelineError
from app.services.image_pipeline import ImagePipeline


def response_with_peer(address: str) -> Mock:
    stream = Mock()
    stream.get_extra_info.side_effect = lambda name: (address, 443) if name == "server_addr" else None
    response = Mock()
    response.extensions = {"network_stream": stream}
    return response


def test_public_peer_must_match_dns_snapshot():
    with pytest.raises(ImagePipelineError, match="did not match"):
        ImagePipeline._validate_peer(response_with_peer("8.8.8.8"), {"1.1.1.1"})


def test_versioned_cloudinary_image_checks_original_bytes_before_normalizing():
    pipeline = ImagePipeline(Settings(cloudinary_cloud_name="demo"))
    asset_url = "https://res.cloudinary.com/demo/image/upload/v42/catalog/demo.jpg"
    pipeline._download = AsyncMock(return_value=(b"original", "image/jpeg"))
    pipeline.normalize = Mock()

    result = asyncio.run(pipeline.download_and_normalize(
        asset_url, "catalog/demo", asset_version="42", checksum=hashlib.sha256(b"original").hexdigest(),
    ))

    pipeline._download.assert_awaited_once_with(asset_url)
    pipeline.normalize.assert_called_once_with(b"original", "image/jpeg")
    assert result is pipeline.normalize.return_value

    pipeline.normalize.reset_mock()
    with pytest.raises(ImagePipelineError, match="checksum"):
        asyncio.run(pipeline.download_and_normalize(
            asset_url, "catalog/demo", asset_version="42", checksum="a" * 64,
        ))
    with pytest.raises(ImagePipelineError, match="original asset version"):
        asyncio.run(pipeline.download_and_normalize(asset_url, "catalog/demo", asset_version="43"))
    pipeline.normalize.assert_not_called()


def test_private_peer_is_rejected_even_if_dns_snapshot_was_public():
    with pytest.raises(ImagePipelineError, match="non-public"):
        ImagePipeline._validate_peer(response_with_peer("127.0.0.1"), {"1.1.1.1"})
