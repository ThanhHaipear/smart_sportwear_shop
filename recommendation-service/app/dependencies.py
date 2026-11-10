import hmac

from fastapi import Depends, Header, HTTPException

from app.config import Settings, get_settings
from app.repository import PostgresRepository
from app.service import RecommendationService


def repository(settings: Settings = Depends(get_settings)):
    if not settings.recommendation_enabled:
        raise HTTPException(status_code=503, detail="recommendation-service is disabled")
    return PostgresRepository(settings.recommendation_database_url)


def service(repo=Depends(repository), settings: Settings = Depends(get_settings)):
    return RecommendationService(repo, settings.recommendation_fallback_mode)


def internal_auth(x_internal_service_token: str = Header(default=""), settings: Settings = Depends(get_settings)):
    if not settings.recommendation_internal_token or not hmac.compare_digest(x_internal_service_token, settings.recommendation_internal_token):
        raise HTTPException(status_code=401, detail="invalid internal service token")
