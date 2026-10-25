import hmac
import time

from fastapi import APIRouter, Depends, File, Header, HTTPException, Query, UploadFile, status
from pydantic import BaseModel
from psycopg import Error as PsycopgError

from app.config import Settings, get_settings
from app.persistence.repository import RepositoryUnavailableError, VisualSearchRepository
from app.providers.factory import build_provider
from app.providers.siglip2 import Siglip2ProviderError
from app.providers.voyage import VoyageProviderError
from app.services.errors import ImagePipelineError
from app.services.image_pipeline import ImagePipeline

router = APIRouter(prefix="/internal/v1", tags=["visual-search"])


class SearchCandidateResponse(BaseModel):
    product_id: str
    image_id: str
    matched_image_url: str
    similarity: float


class SearchResponse(BaseModel):
    candidates: list[SearchCandidateResponse]


def require_internal_token(
    x_internal_service_token: str = Header(default=""),
    settings: Settings = Depends(get_settings),
) -> None:
    if not settings.internal_service_token or not hmac.compare_digest(
        x_internal_service_token, settings.internal_service_token
    ):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid internal service token")


@router.post("/search", response_model=SearchResponse, dependencies=[Depends(require_internal_token)])
async def search_by_image(
    image: UploadFile = File(...),
    # The public API returns at most 20 results, but Spring intentionally asks
    # for a wider pool before applying category, gender, price and stock filters.
    limit: int = Query(default=20, ge=1, le=50),
    settings: Settings = Depends(get_settings),
) -> SearchResponse:
    if not settings.visual_search_enabled:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Visual search is disabled")

    declared_type = (image.content_type or "").lower()
    try:
        content = await image.read(settings.max_upload_bytes + 1)
        normalized = ImagePipeline(settings).normalize(content, declared_type or None)
    except ImagePipelineError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

    repository = VisualSearchRepository(settings)
    try:
        if settings.visual_embedding_provider != "hugging_face_local":
            monthly_cost = await repository.current_month_cost()
            if settings.monthly_budget_usd == 0 or monthly_cost >= settings.monthly_budget_usd:
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail="Visual search monthly AI budget is exhausted",
                )
        async with await repository._connect() as connection:
            model = await repository.active_model_on(connection)
            if model is None:
                raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="No active visual model")
            if settings.visual_embedding_provider == "hugging_face_local" and not (
                model.provider == "HUGGING_FACE_LOCAL"
                and model.model == settings.visual_embedding_model
                and model.model_version == settings.visual_embedding_revision
                and model.preprocessing_version == settings.visual_embedding_preprocessing
                and model.dimensions == settings.visual_embedding_dims
            ):
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    detail="Active visual model does not match the configured SigLIP2 index",
                )
            started = time.perf_counter()
            result = (await build_provider(settings).embed_query(normalized.content)).validate_dimensions(model.dimensions)
            latency_ms = round((time.perf_counter() - started) * 1000)
            candidates = await repository.search_on(
                connection, model.id, result.vector, limit, normalized.color_signature
            )
            if settings.visual_embedding_provider != "hugging_face_local":
                await repository.record_query_usage_on(connection, model, result, latency_ms)
            await connection.commit()
    except (RepositoryUnavailableError, PsycopgError) as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Visual search is unavailable") from exc
    except (VoyageProviderError, Siglip2ProviderError, ValueError, RuntimeError) as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Visual embedding provider is unavailable",
        ) from exc

    return SearchResponse(
        candidates=[
            SearchCandidateResponse(
                product_id=str(candidate.product_id),
                image_id=str(candidate.image_id),
                matched_image_url=candidate.matched_image_url,
                similarity=candidate.similarity,
            )
            for candidate in candidates
        ]
    )
