import asyncio, json, logging
logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s: %(message)s")
from app.config.settings import settings
from app.services.recommendation_service import recommend_related
result = asyncio.run(recommend_related("11111111-1111-4111-8111-111111111111", limit=3))
print(json.dumps({"RECOMMENDATION_API_BASE_URL": settings.RECOMMENDATION_API_BASE_URL,
                  "requestId": result.requestId,
                  "items": [(i.productId, i.name, i.reason) for i in result.items], "error": result.error},
                 ensure_ascii=False, indent=1))
