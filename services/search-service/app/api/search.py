import hmac
import time
from uuid import UUID

from fastapi import APIRouter, Depends, File, Header, HTTPException, Query, UploadFile, status
from pydantic import BaseModel, Field

from app.config import Settings, get_settings
from app.persistence.repository import SearchRepository
from app.providers.factory import build_provider
from app.services.errors import ImagePipelineError
from app.services.image_pipeline import ImagePipeline
from app.services.text_embeddings import TextEmbedder

router = APIRouter(prefix="/internal/v1", tags=["search"])


def require_token(
    x_internal_service_token: str = Header(default="", alias="X-Internal-Service-Token"),
    x_internal_token: str = Header(default="", alias="X-Internal-Token"),
    settings: Settings = Depends(get_settings),
) -> None:
    supplied = x_internal_service_token or x_internal_token
    if not settings.internal_service_token or not hmac.compare_digest(supplied, settings.internal_service_token):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid internal service token")


class SearchFilters(BaseModel):
    categoryId: UUID | None = None
    categorySlug: str | None = None
    brandId: UUID | None = None
    brandSlug: str | None = None
    gender: str | None = None
    sportType: str | None = None
    productType: str | None = None
    surface: str | None = None
    color: str | None = None
    size: str | None = None
    minPrice: float | None = Field(default=None, ge=0)
    maxPrice: float | None = Field(default=None, ge=0)
    discount: str | None = None
    inStockOnly: bool = True


class TextSearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=300)
    page: int = Field(default=1, ge=1)
    limit: int = Field(default=20, ge=1, le=100)
    filters: SearchFilters = Field(default_factory=SearchFilters)


@router.post("/search", dependencies=[Depends(require_token)])
@router.post("/product-search", dependencies=[Depends(require_token)], include_in_schema=False)
async def search_text(request: TextSearchRequest, settings: Settings = Depends(get_settings)) -> dict:
    if not settings.search_enabled:
        raise HTTPException(status_code=503, detail="Search is disabled")
    started = time.perf_counter()
    try:
        vector = await TextEmbedder(settings).embed(request.query)
        rows = await SearchRepository(settings).text_search(
            request.query, vector, min(400, request.page * request.limit * 2),
            request.filters.model_dump(mode="json"),
        )
    except Exception as error:
        raise HTTPException(status_code=503, detail="Search is temporarily unavailable") from error
    start = (request.page - 1) * request.limit
    selected = rows[start:start + request.limit]
    return {
        "items": [{
            "productId": str(row["product_id"]),
            "keywordRank": row.get("keyword_rank"),
            "semanticRank": row.get("semantic_rank"),
            "semanticScore": row.get("semantic_score"),
            "fusionScore": float(row["fusion_score"]),
            "matchedReasons": row.get("matched_reasons") or [],
        } for row in selected],
        "total": len(rows),
        "parsedQuery": {
            "normalized": " ".join(request.query.casefold().split()),
            "semanticText": request.query,
            "category": request.filters.categorySlug,
            "brand": request.filters.brandSlug,
            "gender": request.filters.gender,
            "sportType": request.filters.sportType,
            "productType": request.filters.productType,
            "surface": request.filters.surface,
            "colorFamily": request.filters.color,
            "size": request.filters.size,
            "minPrice": request.filters.minPrice,
            "maxPrice": request.filters.maxPrice,
            "featureHints": [],
        },
        "searchMode": "HYBRID" if vector else "KEYWORD",
        "processingTimeMs": round((time.perf_counter() - started) * 1000),
        "cacheHit": False,
    }


@router.post("/search/image", dependencies=[Depends(require_token)])
@router.post("/visual-search", dependencies=[Depends(require_token)], include_in_schema=False)
async def search_image(
    image: UploadFile = File(...), limit: int = Query(default=20, ge=1, le=50),
    settings: Settings = Depends(get_settings),
) -> dict:
    if not settings.search_enabled or not settings.visual_search_enabled:
        raise HTTPException(status_code=503, detail="Image search is disabled")
    try:
        content = await image.read(settings.max_upload_bytes + 1)
        normalized = ImagePipeline(settings).normalize(content, image.content_type)
    except ImagePipelineError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    try:
        provider = build_provider(settings)
        result = (await provider.embed_query(normalized.content)).validate_dimensions(settings.visual_embedding_dims)
        rows = await SearchRepository(settings).image_search(result.vector, limit)
    except Exception as error:
        raise HTTPException(status_code=503, detail="Image search is temporarily unavailable") from error
    return {"candidates": [{
        "product_id": str(row["product_id"]), "image_id": str(row["image_id"]),
        "matched_image_url": row["image_url"], "similarity": float(row["similarity"]),
    } for row in rows]}
