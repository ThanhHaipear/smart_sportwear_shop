import hmac

from fastapi import Depends, Header, HTTPException

from app.catalog import HttpCatalogClient
from app.config import Settings, get_settings
from app.enrichment import EventEnricher
from app.interactions import InteractionRateLimiter
from app.repository import PostgresRepository
from app.service import RecommendationService


def repository(settings: Settings = Depends(get_settings)):
    if not settings.recommendation_enabled:
        raise HTTPException(status_code=503, detail="recommendation-service is disabled")
    return PostgresRepository(settings.recommendation_database_url)


def service(repo=Depends(repository), settings: Settings = Depends(get_settings)):
    return RecommendationService(repo, settings.recommendation_fallback_mode, settings.recommendation_content_mode)


_interaction_limiters: dict[tuple[int, int], InteractionRateLimiter] = {}


def interaction_limiter(settings: Settings = Depends(get_settings)) -> InteractionRateLimiter:
    # One limiter per configuration for the lifetime of the process (state must persist across requests).
    key = (settings.interaction_rate_limit_per_identity, settings.interaction_rate_limit_per_ip)
    if key not in _interaction_limiters:
        _interaction_limiters[key] = InteractionRateLimiter(*key)
    return _interaction_limiters[key]


def catalog_client(settings: Settings = Depends(get_settings)):
    return HttpCatalogClient(settings)


def enricher(repo=Depends(repository), catalog=Depends(catalog_client)):
    return EventEnricher(catalog, repo)


def internal_auth(x_internal_service_token: str = Header(default=""), settings: Settings = Depends(get_settings)):
    if not settings.recommendation_internal_token or not hmac.compare_digest(x_internal_service_token, settings.recommendation_internal_token):
        raise HTTPException(status_code=401, detail="invalid internal service token")
