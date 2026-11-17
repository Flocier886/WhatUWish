"""Python 工具侧正式 Artifact 存储 Port 与 Local/S3-compatible Backend。"""

from __future__ import annotations

import hashlib
import mimetypes
import os
import re
import shutil
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO, Mapping
from uuid import uuid4

from app.runtime.configuration import config_section, load_application_configuration
from app.runtime.models import ToolContext


LOCAL_STORAGE_TYPE = "local_artifact_storage"
S3_STORAGE_TYPE = "s3_object_storage"
ROOT_DIR = Path(__file__).resolve().parents[2]
WORKSPACE_ROOT = ROOT_DIR.parent.resolve()
DEFAULT_ARTIFACT_STORAGE_ROOT = WORKSPACE_ROOT / "data" / "artifact-storage"
DEFAULT_ARTIFACT_RETENTION_SECONDS = 7 * 24 * 60 * 60
DEFAULT_ARTIFACT_PREVIEW_LIMIT = 4000
DEFAULT_S3_REGION = "us-east-1"
DEFAULT_S3_CONNECT_TIMEOUT_SECONDS = 5
DEFAULT_S3_READ_TIMEOUT_SECONDS = 30
MAX_STORAGE_PATH_LENGTH = 240
TEMP_FILE_NAME_LENGTH = 37


class ArtifactStorageError(ValueError):
    """不携带 endpoint、bucket、object key 或凭证的受控存储失败。"""


@dataclass(frozen=True)
class StoredArtifact:
    artifact_id: str
    storage_type: str
    storage_key: str
    storage_ref: str
    file_name: str
    file_size: int
    mime_type: str
    file_hash: str


@dataclass(frozen=True)
class ArtifactStoredContent:
    size: int


@dataclass(frozen=True)
class ArtifactStorageScope:
    """Artifact 的独立存储分区；对象 key 不依赖该运行路径。"""

    execution_id: str
    caller_scope: str | None = None


@dataclass(frozen=True)
class ArtifactStorageSettings:
    active_type: str
    local_root: str
    s3_enabled: bool
    s3_endpoint: str
    s3_bucket: str
    s3_region: str
    s3_path_style: bool
    s3_access_key: str
    s3_secret_key: str
    s3_prefix: str
    s3_connect_timeout_seconds: int
    s3_read_timeout_seconds: int

    @classmethod
    def from_environment(cls) -> "ArtifactStorageSettings":
        """保留给显式环境集成测试和旧部署入口的兼容构造方式。"""
        values = {
            "type": os.getenv("AI_AGENT_ARTIFACT_STORAGE_TYPE", ""),
            "local-root": os.getenv("AI_AGENT_ARTIFACT_STORAGE_DIR", ""),
            "s3-enabled": os.getenv("AI_AGENT_ARTIFACT_S3_ENABLED", ""),
            "s3-endpoint": os.getenv("AI_AGENT_ARTIFACT_S3_ENDPOINT", ""),
            "s3-bucket": os.getenv("AI_AGENT_ARTIFACT_S3_BUCKET", ""),
            "s3-region": os.getenv("AI_AGENT_ARTIFACT_S3_REGION", ""),
            "s3-path-style": os.getenv("AI_AGENT_ARTIFACT_S3_PATH_STYLE", ""),
            "s3-access-key": os.getenv("AI_AGENT_ARTIFACT_S3_ACCESS_KEY", ""),
            "s3-secret-key": os.getenv("AI_AGENT_ARTIFACT_S3_SECRET_KEY", ""),
            "s3-prefix": os.getenv("AI_AGENT_ARTIFACT_S3_PREFIX", ""),
            "s3-connect-timeout-seconds": os.getenv("AI_AGENT_ARTIFACT_S3_CONNECT_TIMEOUT_SECONDS", ""),
            "s3-read-timeout-seconds": os.getenv("AI_AGENT_ARTIFACT_S3_READ_TIMEOUT_SECONDS", ""),
        }
        return cls._from_values(values)

    @classmethod
    def load(cls) -> "ArtifactStorageSettings":
        """从统一 YAML/本机覆盖/环境变量合并配置加载正式 Storage 运行时。"""
        return cls.from_configuration(load_application_configuration())

    @classmethod
    def from_configuration(cls, configuration: Mapping[str, Any]) -> "ArtifactStorageSettings":
        return cls._from_values(config_section(configuration, "artifact-storage"))

    @classmethod
    def _from_values(cls, values: Mapping[str, Any]) -> "ArtifactStorageSettings":
        active_type = _configured_text(values, "type", LOCAL_STORAGE_TYPE)
        if active_type not in {LOCAL_STORAGE_TYPE, S3_STORAGE_TYPE}:
            raise ArtifactStorageError("Artifact storage type 配置无效。")
        enabled_default = active_type == S3_STORAGE_TYPE
        s3_enabled = _configured_bool(values, "s3-enabled", enabled_default)
        if active_type == S3_STORAGE_TYPE and not s3_enabled:
            raise ArtifactStorageError("S3 Artifact storage 未启用。")
        settings = cls(
            active_type=active_type,
            local_root=str(_configured_storage_root(_configured_text(values, "local-root", ""))),
            s3_enabled=s3_enabled,
            s3_endpoint=_configured_text(values, "s3-endpoint", ""),
            s3_bucket=_configured_text(values, "s3-bucket", ""),
            s3_region=_configured_text(values, "s3-region", DEFAULT_S3_REGION),
            s3_path_style=_configured_bool(values, "s3-path-style", True),
            s3_access_key=_configured_text(values, "s3-access-key", ""),
            s3_secret_key=_configured_text(values, "s3-secret-key", ""),
            s3_prefix=_normalize_object_prefix(_configured_text(values, "s3-prefix", "")),
            s3_connect_timeout_seconds=_configured_positive_int(
                values, "s3-connect-timeout-seconds", DEFAULT_S3_CONNECT_TIMEOUT_SECONDS
            ),
            s3_read_timeout_seconds=_configured_positive_int(
                values, "s3-read-timeout-seconds", DEFAULT_S3_READ_TIMEOUT_SECONDS
            ),
        )
        if settings.s3_enabled:
            settings.validate_s3()
        return settings

    def validate_s3(self) -> None:
        if not self.s3_endpoint or not self.s3_bucket or not self.s3_access_key or not self.s3_secret_key:
            raise ArtifactStorageError("S3 Artifact storage 必要配置不完整。")
        if not self.s3_endpoint.startswith(("http://", "https://")):
            raise ArtifactStorageError("S3 Artifact storage endpoint 配置无效。")


class ArtifactStorageBackend(ABC):
    """Artifact 物理存储 SPI；工具和 DeepSearch 不依赖具体实现。"""

    # 当前 Backend 可处理的存储类型，随 Artifact descriptor 交给 Java Ledger。
    storage_type: str

    @abstractmethod
    def persist_bytes(self, content: bytes, file_name: str, scope: ArtifactStorageScope,
                      artifact_id: str) -> StoredArtifact:
        """将内存中的字节保存为稳定 Artifact，并返回其存储定位信息。"""
        raise NotImplementedError

    @abstractmethod
    def persist_file(self, source: Path, file_name: str, scope: ArtifactStorageScope,
                     artifact_id: str) -> StoredArtifact:
        """将已有本地文件复制或上传为稳定 Artifact。"""
        raise NotImplementedError

    @abstractmethod
    def inspect(self, storage_key: str) -> ArtifactStoredContent:
        """检查指定 Artifact 是否可用，并返回大小等物理内容信息。"""
        raise NotImplementedError

    @abstractmethod
    def read_range(self, storage_key: str, offset: int, limit: int) -> bytes:
        """按偏移量分段读取 Artifact，避免一次加载完整内容。"""
        raise NotImplementedError

    @abstractmethod
    def resolve_key(self, artifact_id: str) -> str | None:
        """根据稳定 artifactId 解析对应的 storageKey；不存在时返回 None。"""
        raise NotImplementedError

    @abstractmethod
    def remove(self, storage_key: str) -> None:
        """只供未登记新产物的失败补偿使用，不参与正常软删除。"""
        raise NotImplementedError


class LocalArtifactStorageBackend(ArtifactStorageBackend):
    storage_type = LOCAL_STORAGE_TYPE

    def __init__(self, root: Path):
        self.root = root.expanduser().resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def persist_bytes(self, content: bytes, file_name: str, scope: ArtifactStorageScope,
                      artifact_id: str) -> StoredArtifact:
        target, storage_key = self._target(file_name, scope, artifact_id)
        target.parent.mkdir(parents=True, exist_ok=True)
        temp_target = _temporary_target(target)
        try:
            temp_target.write_bytes(content)
            os.replace(temp_target, target)
        finally:
            temp_target.unlink(missing_ok=True)
        return self._stored(artifact_id, storage_key, target, file_name)

    def persist_file(self, source: Path, file_name: str, scope: ArtifactStorageScope,
                     artifact_id: str) -> StoredArtifact:
        if not source.is_file():
            raise FileNotFoundError(source)
        target, storage_key = self._target(file_name, scope, artifact_id)
        target.parent.mkdir(parents=True, exist_ok=True)
        temp_target = _temporary_target(target)
        try:
            shutil.copy2(source, temp_target)
            os.replace(temp_target, target)
        finally:
            temp_target.unlink(missing_ok=True)
        return self._stored(artifact_id, storage_key, target, file_name)

    def inspect(self, storage_key: str) -> ArtifactStoredContent:
        target = self.resolve_path(storage_key)
        if not target.is_file():
            raise ArtifactStorageError("Artifact 不存在或不可用。")
        return ArtifactStoredContent(size=target.stat().st_size)

    def read_range(self, storage_key: str, offset: int, limit: int) -> bytes:
        target = self.resolve_path(storage_key)
        if not target.is_file():
            raise ArtifactStorageError("Artifact 不存在或不可用。")
        with target.open("rb") as handle:
            handle.seek(offset)
            return handle.read(limit)

    def resolve_key(self, artifact_id: str) -> str | None:
        safe_id = _validate_artifact_id(artifact_id)
        matches = sorted(self.root.rglob(f"{safe_id}-*"), key=lambda item: item.stat().st_mtime, reverse=True)
        return self.storage_key_for_path(matches[0]) if matches else None

    def remove(self, storage_key: str) -> None:
        self.resolve_path(storage_key).unlink(missing_ok=True)

    def resolve_path(self, storage_key: str) -> Path:
        normalized = _normalize_local_storage_key(storage_key)
        target = (self.root / normalized).resolve()
        if target != self.root and self.root not in target.parents:
            raise ArtifactStorageError("Artifact 不存在或不可用。")
        return target

    def storage_key_for_path(self, path: Path) -> str:
        resolved = path.resolve()
        try:
            return resolved.relative_to(self.root).as_posix()
        except ValueError as exc:
            raise ArtifactStorageError("Artifact 路径不在受控存储中。") from exc

    def _target(self, raw_file_name: str, scope: ArtifactStorageScope, artifact_id: str) -> tuple[Path, str]:
        session_limit, run_limit = _partition_limits(self.root)
        session_key = _safe_partition(scope.caller_scope or "anonymous-session", session_limit)
        run_key = _safe_partition(scope.execution_id or "anonymous-run", run_limit)
        directory = self.root / session_key / run_key
        safe_name = _safe_file_name(raw_file_name, _max_file_name_length(directory, artifact_id))
        storage_key = PurePosixPath(session_key, run_key, f"{artifact_id}-{safe_name}").as_posix()
        return directory / f"{artifact_id}-{safe_name}", storage_key

    def _stored(self, artifact_id: str, storage_key: str, target: Path,
                logical_file_name: str) -> StoredArtifact:
        return StoredArtifact(
            artifact_id=artifact_id,
            storage_type=self.storage_type,
            storage_key=storage_key,
            storage_ref=storage_key,
            file_name=_safe_file_name(logical_file_name),
            file_size=target.stat().st_size,
            mime_type=_guess_artifact_mime(logical_file_name),
            file_hash=_sha256(target),
        )


class S3ArtifactStorageBackend(ArtifactStorageBackend):
    storage_type = S3_STORAGE_TYPE

    def __init__(self, settings: ArtifactStorageSettings, client=None):
        settings.validate_s3()
        self.settings = settings
        self.client = client or self._create_client(settings)

    def persist_bytes(self, content: bytes, file_name: str, scope: ArtifactStorageScope,
                      artifact_id: str) -> StoredArtifact:
        del scope
        file_hash = hashlib.sha256(content).hexdigest()
        mime_type = _guess_artifact_mime(file_name)
        storage_key = build_s3_object_key(artifact_id, self.settings.s3_prefix)
        try:
            self.client.put_object(
                Bucket=self.settings.s3_bucket,
                Key=storage_key,
                Body=content,
                ContentLength=len(content),
                ContentType=mime_type,
                Metadata=_s3_metadata(artifact_id, mime_type, file_hash),
            )
            self._verify_upload(storage_key, artifact_id, len(content), mime_type, file_hash)
        except Exception as exc:
            raise ArtifactStorageError("S3 Artifact 写入或校验失败。") from exc
        return self._stored(artifact_id, storage_key, file_name, len(content), mime_type, file_hash)

    def persist_file(self, source: Path, file_name: str, scope: ArtifactStorageScope,
                     artifact_id: str) -> StoredArtifact:
        del scope
        if not source.is_file():
            raise FileNotFoundError(source)
        file_size = source.stat().st_size
        file_hash = _sha256(source)
        mime_type = _guess_artifact_mime(file_name)
        storage_key = build_s3_object_key(artifact_id, self.settings.s3_prefix)
        try:
            with source.open("rb") as handle:
                self.client.upload_fileobj(
                    handle,
                    self.settings.s3_bucket,
                    storage_key,
                    ExtraArgs={
                        "ContentType": mime_type,
                        "Metadata": _s3_metadata(artifact_id, mime_type, file_hash),
                    },
                )
            self._verify_upload(storage_key, artifact_id, file_size, mime_type, file_hash)
        except Exception as exc:
            raise ArtifactStorageError("S3 Artifact 写入或校验失败。") from exc
        return self._stored(artifact_id, storage_key, file_name, file_size, mime_type, file_hash)

    def inspect(self, storage_key: str) -> ArtifactStoredContent:
        key = _normalize_s3_storage_key(storage_key, self.settings.s3_prefix)
        try:
            response = self.client.head_object(Bucket=self.settings.s3_bucket, Key=key)
            return ArtifactStoredContent(size=int(response["ContentLength"]))
        except Exception as exc:
            raise ArtifactStorageError("Artifact 不存在或对象存储不可用。") from exc

    def read_range(self, storage_key: str, offset: int, limit: int) -> bytes:
        key = _normalize_s3_storage_key(storage_key, self.settings.s3_prefix)
        if offset < 0 or limit <= 0:
            raise ArtifactStorageError("Artifact 读取范围无效。")
        try:
            response = self.client.get_object(
                Bucket=self.settings.s3_bucket,
                Key=key,
                Range=f"bytes={offset}-{offset + limit - 1}",
            )
            body: BinaryIO = response["Body"]
            try:
                return body.read(limit)
            finally:
                body.close()
        except Exception as exc:
            raise ArtifactStorageError("Artifact 不存在或对象存储不可用。") from exc

    def resolve_key(self, artifact_id: str) -> str | None:
        return build_s3_object_key(artifact_id, self.settings.s3_prefix)

    def remove(self, storage_key: str) -> None:
        key = _normalize_s3_storage_key(storage_key, self.settings.s3_prefix)
        try:
            self.client.delete_object(Bucket=self.settings.s3_bucket, Key=key)
        except Exception as exc:
            raise ArtifactStorageError("S3 Artifact 清理失败。") from exc

    def _verify_upload(self, storage_key: str, artifact_id: str, expected_size: int,
                       mime_type: str, file_hash: str) -> None:
        response = self.client.head_object(Bucket=self.settings.s3_bucket, Key=storage_key)
        metadata = {str(key).lower(): str(value) for key, value in (response.get("Metadata") or {}).items()}
        if int(response.get("ContentLength", -1)) != expected_size:
            raise ArtifactStorageError("S3 Artifact 大小校验失败。")
        expected = _s3_metadata(artifact_id, mime_type, file_hash)
        if any(metadata.get(key) != value for key, value in expected.items()):
            raise ArtifactStorageError("S3 Artifact metadata 校验失败。")

    def _stored(self, artifact_id: str, storage_key: str, file_name: str, file_size: int,
                mime_type: str, file_hash: str) -> StoredArtifact:
        return StoredArtifact(
            artifact_id=artifact_id,
            storage_type=self.storage_type,
            storage_key=storage_key,
            storage_ref=storage_key,
            file_name=_safe_file_name(file_name),
            file_size=file_size,
            mime_type=mime_type,
            file_hash=file_hash,
        )

    @staticmethod
    def _create_client(settings: ArtifactStorageSettings):
        import boto3
        from botocore.config import Config

        return boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint,
            region_name=settings.s3_region,
            aws_access_key_id=settings.s3_access_key,
            aws_secret_access_key=settings.s3_secret_key,
            config=Config(
                connect_timeout=settings.s3_connect_timeout_seconds,
                read_timeout=settings.s3_read_timeout_seconds,
                retries={"max_attempts": 3, "mode": "standard"},
                s3={"addressing_style": "path" if settings.s3_path_style else "virtual"},
            ),
        )


class ArtifactStorageRuntime:
    def __init__(self, settings: ArtifactStorageSettings):
        self.settings = settings
        backends: dict[str, ArtifactStorageBackend] = {
            LOCAL_STORAGE_TYPE: LocalArtifactStorageBackend(Path(settings.local_root)),
        }
        if settings.s3_enabled:
            backends[S3_STORAGE_TYPE] = S3ArtifactStorageBackend(settings)
        self.backends = backends

    def active_backend(self) -> ArtifactStorageBackend:
        return self.backend(self.settings.active_type)

    def backend(self, storage_type: str | None) -> ArtifactStorageBackend:
        selected = storage_type or self.settings.active_type
        backend = self.backends.get(selected)
        if backend is None:
            raise ArtifactStorageError("请求的 Artifact storage Backend 未启用。")
        return backend


def scope_from_tool_context(context: ToolContext) -> ArtifactStorageScope:
    return ArtifactStorageScope(
        execution_id=context.run_id or context.request_id or "anonymous-run",
        caller_scope=context.session_id or "anonymous-session",
    )


def artifact_storage_root() -> Path:
    return Path(ArtifactStorageSettings.load().local_root)


def artifact_retention_seconds() -> int:
    raw_value = os.getenv("AI_AGENT_ARTIFACT_RETENTION_SECONDS", "").strip()
    if not raw_value:
        return DEFAULT_ARTIFACT_RETENTION_SECONDS
    try:
        return max(0, int(raw_value))
    except ValueError:
        return DEFAULT_ARTIFACT_RETENTION_SECONDS


def artifact_preview_limit() -> int:
    raw_value = os.getenv("AI_AGENT_ARTIFACT_PREVIEW_LIMIT", "").strip()
    if not raw_value:
        return DEFAULT_ARTIFACT_PREVIEW_LIMIT
    try:
        return min(100_000, max(1, int(raw_value)))
    except ValueError:
        return DEFAULT_ARTIFACT_PREVIEW_LIMIT


def artifact_storage_runtime() -> ArtifactStorageRuntime:
    return _runtime_for_settings(ArtifactStorageSettings.load())


@lru_cache(maxsize=8)
def _runtime_for_settings(settings: ArtifactStorageSettings) -> ArtifactStorageRuntime:
    return ArtifactStorageRuntime(settings)


def reset_artifact_storage_runtime() -> None:
    """仅供配置合同测试或显式进程重载使用。"""
    _runtime_for_settings.cache_clear()


def persist_text(content: str, file_name: str, scope: ArtifactStorageScope | ToolContext) -> StoredArtifact:
    return persist_bytes(content.encode("utf-8"), file_name, scope)


def persist_file(source: Path, file_name: str | None, scope: ArtifactStorageScope | ToolContext) -> StoredArtifact:
    active = artifact_storage_runtime().active_backend()
    artifact_id = uuid4().hex
    try:
        return active.persist_file(source, file_name or source.name, _storage_scope(scope), artifact_id)
    except Exception:
        # 上传后校验失败也可能留下物理对象；新随机 ID 从未进入账本，可以安全补偿。
        try:
            key = active.resolve_key(artifact_id)
            if key:
                active.remove(key)
        except Exception:
            pass
        raise


def persist_bytes(content: bytes, file_name: str, scope: ArtifactStorageScope | ToolContext) -> StoredArtifact:
    active = artifact_storage_runtime().active_backend()
    artifact_id = uuid4().hex
    try:
        return active.persist_bytes(content, file_name, _storage_scope(scope), artifact_id)
    except Exception:
        # 与 persist_file 一致：校验失败后只回收本次随机 ID 对应的对象。
        try:
            key = active.resolve_key(artifact_id)
            if key:
                active.remove(key)
        except Exception:
            pass
        raise


def inspect_artifact(storage_key: str, storage_type: str | None = None) -> ArtifactStoredContent:
    return artifact_storage_runtime().backend(storage_type).inspect(storage_key)


def read_artifact_range(storage_key: str, offset: int, limit: int,
                        storage_type: str | None = None) -> bytes:
    return artifact_storage_runtime().backend(storage_type).read_range(storage_key, offset, limit)


def resolve_artifact_key(artifact_id: str, storage_type: str | None = None) -> str | None:
    return artifact_storage_runtime().backend(storage_type).resolve_key(artifact_id)


def remove_unregistered_artifact(storage_key: str, storage_type: str) -> None:
    """材料调用失败时清理仅由本次调用创建、尚未提交账本的对象。"""
    artifact_storage_runtime().backend(storage_type).remove(storage_key)


# 以下 Path helper 仅保留给 Local Backend 测试和历史内部调用；正式工具读取使用上面的 Port。
def resolve_storage_key(storage_key: str) -> Path:
    return LocalArtifactStorageBackend(artifact_storage_root()).resolve_path(storage_key)


def resolve_artifact_id(artifact_id: str) -> Path | None:
    backend = LocalArtifactStorageBackend(artifact_storage_root())
    storage_key = backend.resolve_key(artifact_id)
    return backend.resolve_path(storage_key) if storage_key else None


def resolve_latest_by_file_name(file_name: str) -> Path | None:
    safe_name = _safe_file_name(file_name)
    matches = sorted(artifact_storage_root().rglob(f"*-{safe_name}"), key=lambda item: item.stat().st_mtime, reverse=True)
    return matches[0] if matches else None


def storage_key_for_path(path: Path) -> str:
    return LocalArtifactStorageBackend(artifact_storage_root()).storage_key_for_path(path)


def cleanup_expired_artifacts(now: float | None = None) -> list[str]:
    """只清理显式调用的 Local 稳定对象；对象模式与迁移不会调用本方法。"""
    retention = artifact_retention_seconds()
    if retention <= 0:
        return []
    threshold = (now if now is not None else time.time()) - retention
    deleted: list[str] = []
    root = artifact_storage_root()
    for candidate in root.rglob("*"):
        if not candidate.is_file() or candidate.stat().st_mtime > threshold:
            continue
        deleted.append(storage_key_for_path(candidate))
        candidate.unlink(missing_ok=True)
    for directory in sorted((item for item in root.rglob("*") if item.is_dir()), key=lambda item: len(item.parts), reverse=True):
        try:
            directory.rmdir()
        except OSError:
            pass
    return deleted


def build_s3_object_key(artifact_id: str, optional_prefix: str = "") -> str:
    safe_id = _validate_artifact_id(artifact_id)
    prefix = _normalize_object_prefix(optional_prefix)
    parts = [part for part in (prefix, "v1", "artifacts", safe_id, "content") if part]
    return "/".join(parts)


def _storage_scope(scope: ArtifactStorageScope | ToolContext) -> ArtifactStorageScope:
    return scope if isinstance(scope, ArtifactStorageScope) else scope_from_tool_context(scope)


def _temporary_target(target: Path) -> Path:
    return target.parent / f".{uuid4().hex}.tmp"


def _sha256(target: Path) -> str:
    digest = hashlib.sha256()
    with target.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _safe_file_name(raw_file_name: str, max_length: int | None = None) -> str:
    name = Path(raw_file_name).name.strip() or "artifact.bin"
    if max_length is None or len(name) <= max_length:
        return name
    suffix = Path(name).suffix
    base_length = max_length - len(suffix)
    return name[:max_length] if base_length <= 0 else name[:base_length] + suffix


def _guess_artifact_mime(file_name: str) -> str:
    """统一跨平台 MIME；Windows 可能把 CSV 错认作旧 Excel 二进制文件。"""
    if Path(file_name).suffix.lower() == ".csv":
        return "text/csv"
    return mimetypes.guess_type(file_name)[0] or "application/octet-stream"


def _safe_partition(value: str, max_length: int = 80) -> str:
    normalized = "".join(char if char.isalnum() or char in {"-", "_"} else "-" for char in value.strip()) or "unknown"
    if len(normalized) <= max_length:
        return normalized
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:8]
    if max_length <= len(digest):
        return digest[:max_length]
    return normalized[:max_length - len(digest) - 1] + "-" + digest


def _partition_limits(root: Path) -> tuple[int, int]:
    budget = MAX_STORAGE_PATH_LENGTH - len(str(root)) - TEMP_FILE_NAME_LENGTH - 3
    if budget < 2:
        raise ArtifactStorageError("Artifact storage root 路径过长。")
    session_limit = budget // 2
    return session_limit, budget - session_limit


def _max_file_name_length(directory: Path, artifact_id: str) -> int:
    if len(str(directory)) + TEMP_FILE_NAME_LENGTH + 1 > MAX_STORAGE_PATH_LENGTH:
        raise ArtifactStorageError("Artifact storage 运行分区路径过长。")
    available = MAX_STORAGE_PATH_LENGTH - len(str(directory)) - len(artifact_id) - 2
    if available <= 0:
        raise ArtifactStorageError("Artifact storage 运行分区路径过长。")
    return available


def _normalize_local_storage_key(storage_key: str) -> Path:
    raw = PurePosixPath(storage_key.replace("\\", "/"))
    if raw.is_absolute() or any(part in {"", ".", ".."} for part in raw.parts):
        raise ArtifactStorageError("Artifact storageKey 无效。")
    return Path(*raw.parts)


def _normalize_object_prefix(prefix: str) -> str:
    value = (prefix or "").strip()
    if not value:
        return ""
    if "\\" in value or value.startswith("/") or value.endswith("/"):
        raise ArtifactStorageError("S3 Artifact prefix 配置无效。")
    parts = PurePosixPath(value).parts
    if any(part in {"", ".", ".."} for part in parts):
        raise ArtifactStorageError("S3 Artifact prefix 配置无效。")
    return "/".join(parts)


def _normalize_s3_storage_key(storage_key: str, prefix: str) -> str:
    value = (storage_key or "").strip()
    if "\\" in value or value.startswith("/"):
        raise ArtifactStorageError("S3 Artifact storageKey 无效。")
    parts = PurePosixPath(value).parts
    expected_prefix = PurePosixPath(prefix).parts if prefix else ()
    if len(parts) != len(expected_prefix) + 4 or tuple(parts[:len(expected_prefix)]) != expected_prefix:
        raise ArtifactStorageError("S3 Artifact storageKey 无效。")
    version, artifact_segment, artifact_id, content = parts[len(expected_prefix):]
    if version != "v1" or artifact_segment != "artifacts" or content != "content":
        raise ArtifactStorageError("S3 Artifact storageKey 无效。")
    _validate_artifact_id(artifact_id)
    return value


def _validate_artifact_id(artifact_id: str) -> str:
    value = (artifact_id or "").strip()
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}", value) is None:
        raise ArtifactStorageError("artifactId 无效。")
    return value


def _s3_metadata(artifact_id: str, mime_type: str, file_hash: str) -> dict[str, str]:
    return {"artifactid": artifact_id, "mimetype": mime_type, "sha256": file_hash}


def _configured_storage_root(configured: str) -> Path:
    root = Path(configured).expanduser() if configured else DEFAULT_ARTIFACT_STORAGE_ROOT
    root.mkdir(parents=True, exist_ok=True)
    return root.resolve()


def _configured_text(values: Mapping[str, Any], key: str, default: str) -> str:
    value = str(values.get(key, "")).strip()
    return value or default


def _configured_bool(values: Mapping[str, Any], key: str, default: bool) -> bool:
    raw = values.get(key)
    if raw is None or not str(raw).strip():
        return default
    if isinstance(raw, bool):
        return raw
    normalized = str(raw).strip().lower()
    if normalized in {"true", "1", "yes", "on"}:
        return True
    if normalized in {"false", "0", "no", "off"}:
        return False
    raise ArtifactStorageError(f"Artifact storage {key} 必须是布尔值。")


def _configured_positive_int(values: Mapping[str, Any], key: str, default: int) -> int:
    raw = values.get(key)
    if raw is None or not str(raw).strip():
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise ArtifactStorageError(f"Artifact storage {key} 必须是正整数。") from exc
    if value <= 0:
        raise ArtifactStorageError(f"Artifact storage {key} 必须是正整数。")
    return value
