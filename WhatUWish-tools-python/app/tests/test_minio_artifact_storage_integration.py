import os
from uuid import uuid4

import pytest

from app.runtime.artifact_storage import (
    ArtifactStorageScope,
    ArtifactStorageSettings,
    S3ArtifactStorageBackend,
)


pytestmark = pytest.mark.skipif(
    os.getenv("AI_AGENT_ARTIFACT_S3_INTEGRATION", "").lower() != "true",
    reason="需要显式 AI_AGENT_ARTIFACT_S3_INTEGRATION=true",
)


def test_put_head_get_and_stream_close_against_explicit_minio_environment():
    settings = ArtifactStorageSettings.from_environment()
    if not settings.s3_enabled:
        pytest.skip("S3 Backend 未显式启用")
    backend = S3ArtifactStorageBackend(settings)
    artifact_id = os.getenv("AI_AGENT_ARTIFACT_S3_PROBE_ID", "").strip() or f"python-probe-{uuid4().hex}"
    content = f"artifact-minio-probe:{artifact_id}".encode("utf-8")

    stored = backend.persist_bytes(
        content,
        "probe.txt",
        ArtifactStorageScope(execution_id="integration", caller_scope="python"),
        artifact_id,
    )

    assert backend.inspect(stored.storage_key).size == len(content)
    assert backend.read_range(stored.storage_key, 0, len(content)) == content
