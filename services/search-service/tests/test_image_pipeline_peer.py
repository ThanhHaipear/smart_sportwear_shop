from unittest.mock import Mock

import pytest

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


def test_private_peer_is_rejected_even_if_dns_snapshot_was_public():
    with pytest.raises(ImagePipelineError, match="non-public"):
        ImagePipeline._validate_peer(response_with_peer("127.0.0.1"), {"1.1.1.1"})
