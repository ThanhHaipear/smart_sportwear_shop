import hashlib
import json
from pathlib import Path

from PIL import Image
import pytest

from scripts.benchmark_search import (
    detected_content_type,
    resolve_dataset,
    validate_and_join_cases,
)


def write_dataset(root: Path, *, expected_hash: str | None = None) -> Path:
    assets = root / "assets"
    assets.mkdir()
    image_path = assets / "query.webp"
    Image.new("RGB", (8, 8), "red").save(image_path, format="JPEG")
    image_hash = hashlib.sha256(image_path.read_bytes()).hexdigest()
    manifest = {
        "schemaVersion": 1,
        "dataset": {"name": "fixture", "version": "1", "split": "DEV", "status": "FROZEN"},
        "samples": [{
            "id": "query-1", "image": "assets/query.webp", "sha256": expected_hash or image_hash,
            "group": "tops",
        }],
    }
    labels = {
        "schemaVersion": 1,
        "dataset": "fixture",
        "cases": [{"queryId": "query-1", "judgmentStatus": "COMPLETE", "relevantProducts": []}],
    }
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    (root / "labels.json").write_text(json.dumps(labels), encoding="utf-8")
    return image_path


def test_runner_validates_frozen_dataset_and_sniffs_content_instead_of_extension(tmp_path: Path) -> None:
    image_path = write_dataset(tmp_path)
    root, _, manifest, labels = resolve_dataset(tmp_path)

    cases = validate_and_join_cases(root, manifest, labels)

    assert len(cases) == 1
    assert detected_content_type(image_path) == "image/jpeg"


def test_runner_rejects_asset_checksum_mismatch(tmp_path: Path) -> None:
    write_dataset(tmp_path, expected_hash="0" * 64)
    root, _, manifest, labels = resolve_dataset(tmp_path)

    with pytest.raises(ValueError, match="checksum mismatch"):
        validate_and_join_cases(root, manifest, labels)


def test_runner_rejects_non_frozen_dataset(tmp_path: Path) -> None:
    write_dataset(tmp_path)
    manifest_path = tmp_path / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["dataset"]["status"] = "DRAFT"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(ValueError, match="status=FROZEN"):
        resolve_dataset(tmp_path)
