import hashlib
import io

import pytest

from app.runtime.artifact_storage import (
    ArtifactStorageError,
    ArtifactStorageScope,
    ArtifactStorageSettings,
    S3ArtifactStorageBackend,
    build_s3_object_key,
)


class FakeS3Client:
    def __init__(self):
        self.objects = {}
        self.fail_put = False
        self.reported_size_delta = 0

    def put_object(self, **request):
        if self.fail_put:
            raise RuntimeError("credential endpoint bucket details")
        self.objects[request["Key"]] = {
            "body": bytes(request["Body"]),
            "metadata": dict(request["Metadata"]),
            "content_type": request["ContentType"],
        }
        return {"ETag": '"multipart-etag-2"'}

    def upload_fileobj(self, handle, bucket, key, ExtraArgs):
        del bucket
        self.objects[key] = {
            "body": handle.read(),
            "metadata": dict(ExtraArgs["Metadata"]),
            "content_type": ExtraArgs["ContentType"],
        }

    def head_object(self, Bucket, Key):
        del Bucket
        if Key not in self.objects:
            raise RuntimeError("missing object and private key")
        item = self.objects[Key]
        return {
            "ContentLength": len(item["body"]) + self.reported_size_delta,
            "Metadata": item["metadata"],
            "ETag": '"multipart-etag-2"',
        }

    def get_object(self, Bucket, Key, Range):
        del Bucket
        if Key not in self.objects:
            raise RuntimeError("missing object and private key")
        start, end = [int(value) for value in Range.removeprefix("bytes=").split("-", 1)]
        return {"Body": io.BytesIO(self.objects[Key]["body"][start:end + 1])}


def settings() -> ArtifactStorageSettings:
    return ArtifactStorageSettings(
        active_type="s3_object_storage",
        local_root="unused",
        s3_enabled=True,
        s3_endpoint="http://127.0.0.1:9000",
        s3_bucket="artifacts",
        s3_region="us-east-1",
        s3_path_style=True,
        s3_access_key="access",
        s3_secret_key="secret",
        s3_prefix="project",
        s3_connect_timeout_seconds=5,
        s3_read_timeout_seconds=30,
    )


def test_key_builder_is_versioned_deterministic_and_rejects_paths():
    assert build_s3_object_key("artifact_01") == "v1/artifacts/artifact_01/content"
    assert build_s3_object_key("artifact-02", "project/runtime") == \
           "project/runtime/v1/artifacts/artifact-02/content"

    for invalid in ("", "../id", "folder/id", "report.md", "/absolute", "中文"):
        with pytest.raises(ArtifactStorageError):
            build_s3_object_key(invalid)


def test_s3_backend_persists_bytes_with_sha_metadata_and_stable_retry_key():
    client = FakeS3Client()
    backend = S3ArtifactStorageBackend(settings(), client)
    scope = ArtifactStorageScope(execution_id="run-1", caller_scope="session-1")

    first = backend.persist_bytes(b"artifact body", "report.md", scope, "artifact-1")
    second = backend.persist_bytes(b"artifact body", "renamed.md", scope, "artifact-1")

    assert first.storage_type == "s3_object_storage"
    assert first.storage_key == "project/v1/artifacts/artifact-1/content"
    assert second.storage_key == first.storage_key
    assert first.file_hash == hashlib.sha256(b"artifact body").hexdigest()
    stored = client.objects[first.storage_key]
    assert stored["metadata"]["sha256"] == first.file_hash
    assert stored["metadata"]["artifactid"] == "artifact-1"
    assert stored["metadata"]["mimetype"] == "text/markdown"
    assert stored.get("ETag") is None


def test_s3_backend_streams_file_and_reads_controlled_range(tmp_path):
    source = tmp_path / "evidence.txt"
    source.write_text("abcdefghij", encoding="utf-8")
    client = FakeS3Client()
    backend = S3ArtifactStorageBackend(settings(), client)

    stored = backend.persist_file(source, source.name, ArtifactStorageScope("run-1"), "artifact-2")

    assert backend.inspect(stored.storage_key).size == 10
    assert backend.read_range(stored.storage_key, 4, 3) == b"efg"
    assert backend.resolve_key("artifact-2") == stored.storage_key


def test_s3_backend_fails_without_publishing_when_put_or_head_validation_fails():
    client = FakeS3Client()
    backend = S3ArtifactStorageBackend(settings(), client)
    client.fail_put = True

    with pytest.raises(ArtifactStorageError, match="写入或校验失败") as put_error:
        backend.persist_bytes(b"body", "report.md", ArtifactStorageScope("run-1"), "artifact-3")
    assert "credential" not in str(put_error.value)

    client.fail_put = False
    client.reported_size_delta = 1
    with pytest.raises(ArtifactStorageError, match="写入或校验失败"):
        backend.persist_bytes(b"body", "report.md", ArtifactStorageScope("run-1"), "artifact-4")


def test_s3_backend_maps_missing_object_without_exposing_locator():
    backend = S3ArtifactStorageBackend(settings(), FakeS3Client())

    with pytest.raises(ArtifactStorageError) as error:
        backend.read_range("project/v1/artifacts/missing/content", 0, 10)

    assert "project" not in str(error.value)
    assert "bucket" not in str(error.value)
