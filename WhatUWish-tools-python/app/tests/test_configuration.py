from pathlib import Path

from app.deepsearch.settings import DeepSearchRuntimeSettings
from app.image_generation.settings import ImageGenerationSettings
from app.runtime.artifact_storage import ArtifactStorageSettings, S3_STORAGE_TYPE
from app.runtime.configuration import load_application_configuration
from app.tools.search_provider import SearchProviderSettings


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def test_application_local_yaml_overrides_defaults_without_exposing_secrets(tmp_path: Path) -> None:
    _write(tmp_path / "config" / "application.yaml", """
web-search:
  provider: tavily
  api-base: https://api.tavily.com/search
  timeout-seconds: 20
research-runtime:
  provider: openai-compatible
  max-iterations: 3
  max-searches: 4
  max-results-per-search: 10
  max-fetches: 6
""")
    _write(tmp_path / "config" / "application.local.yaml", """
web-search:
  api-key: tavily-secret
research-runtime:
  model: local-model
  api-base: https://llm.example/v1
  api-key: llm-secret
""")

    configuration = load_application_configuration(root=tmp_path, environ={})
    search = SearchProviderSettings.from_configuration(configuration)
    research = DeepSearchRuntimeSettings.from_configuration(configuration)

    assert search.api_key == "tavily-secret"
    assert research.model == "local-model"
    assert research.max_searches == 4
    assert "llm-secret" not in str(research.safe_metadata())


def test_environment_has_highest_precedence_over_local_yaml(tmp_path: Path) -> None:
    _write(tmp_path / "config" / "application.yaml", "web-search:\n  provider: tavily\n")
    _write(tmp_path / "config" / "application.local.yaml", "web-search:\n  api-key: local-key\n")

    configuration = load_application_configuration(
        root=tmp_path,
        environ={"WEB_SEARCH_API_KEY": "runtime-key", "WEB_SEARCH_API_BASE": "https://search.example/api"},
    )

    assert SearchProviderSettings.from_configuration(configuration).api_key == "runtime-key"


def test_comment_only_local_section_keeps_the_default_section(tmp_path: Path) -> None:
    _write(tmp_path / "config" / "application.yaml", "web-search:\n  api-base: https://api.tavily.com/search\n")
    _write(tmp_path / "config" / "application.local.yaml", "web-search:\n  # api-key: fill-me\n")

    configuration = load_application_configuration(root=tmp_path, environ={})

    assert configuration["web-search"]["api-base"] == "https://api.tavily.com/search"


def test_legacy_openai_environment_variables_only_fill_missing_yaml_values(tmp_path: Path) -> None:
    _write(tmp_path / "config" / "application.yaml", "research-runtime:\n  model: yaml-model\n")

    configuration = load_application_configuration(
        root=tmp_path,
        environ={"OPENAI_MODEL": "ignored-model", "OPENAI_BASE_URL": "https://llm.example/v1", "OPENAI_API_KEY": "legacy-key"},
    )

    settings = DeepSearchRuntimeSettings.from_configuration(configuration)
    assert settings.model == "yaml-model"
    assert settings.base_url == "https://llm.example/v1"


def test_deepsearch_writer_defaults_keep_a_single_long_synthesis_budget() -> None:
    settings = DeepSearchRuntimeSettings.from_configuration({
        "deepsearch-runtime": {
            "model": "writer-model",
            "api-base": "https://llm.example/v1",
            "api-key": "writer-secret",
        }
    })

    assert settings.timeout_seconds == 240.0
    assert settings.max_retries == 0
    assert settings.max_output_tokens == 3200
    assert settings.max_context_chars == 48_000


def test_image_generation_runtime_uses_yaml_defaults_and_environment_override(tmp_path: Path) -> None:
    _write(tmp_path / "config" / "application.yaml", """
research-runtime:
  model: inherited-image-model
  api-base: https://image.example/v1
  api-key: inherited-secret
image-generation-runtime:
  timeout-seconds: 900
  max-reference-bytes: 10485760
  max-output-count: 4
""")

    configuration = load_application_configuration(
        root=tmp_path,
        environ={
            "IMAGE_GENERATION_API_STYLE": "images",
            "IMAGE_GENERATION_MODEL": "deployment-image-model",
            "IMAGE_GENERATION_MAX_OUTPUT_COUNT": "6",
        },
    )
    settings = ImageGenerationSettings.from_configuration(configuration)

    assert settings.model == "deployment-image-model"
    assert settings.api_style == "images"
    assert settings.max_output_count == 6
    assert settings.timeout_seconds == 900
    assert "inherited-secret" not in str(settings.safe_metadata())


def test_artifact_storage_uses_yaml_and_environment_override_without_exposing_s3_details(tmp_path: Path) -> None:
    _write(tmp_path / "config" / "application.yaml", """
artifact-storage:
  type: s3_object_storage
  s3-enabled: true
  s3-endpoint: http://object-store.example
  s3-bucket: artifact-bucket
  s3-access-key: yaml-access
  s3-secret-key: yaml-secret
  s3-prefix: yaml-prefix
""")

    configuration = load_application_configuration(
        root=tmp_path,
        environ={
            "AI_AGENT_ARTIFACT_S3_PREFIX": "runtime-prefix",
            "AI_AGENT_ARTIFACT_S3_READ_TIMEOUT_SECONDS": "45",
        },
    )
    settings = ArtifactStorageSettings.from_configuration(configuration)

    assert settings.active_type == S3_STORAGE_TYPE
    assert settings.s3_prefix == "runtime-prefix"
    assert settings.s3_read_timeout_seconds == 45
    assert settings.s3_secret_key == "yaml-secret"
