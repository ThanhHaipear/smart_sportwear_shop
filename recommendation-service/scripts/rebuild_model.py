import argparse

from app.config import get_settings
from app.repository import PostgresRepository
from app.service import DEFAULT_MIN_CONFIDENCE, DEFAULT_MIN_SUPPORT, RecommendationService


parser = argparse.ArgumentParser()
parser.add_argument("model_version")
parser.add_argument("--min-support", type=float, default=DEFAULT_MIN_SUPPORT)
parser.add_argument("--min-confidence", type=float, default=DEFAULT_MIN_CONFIDENCE)
parser.add_argument("--min-lift", type=float, default=1.0)
args = parser.parse_args()
settings = get_settings()
service = RecommendationService(PostgresRepository(settings.recommendation_database_url))
print({"modelVersion": args.model_version, "ruleCount": service.rebuild(
    args.model_version, args.min_support, args.min_confidence, args.min_lift)})
