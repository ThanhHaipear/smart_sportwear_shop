from fastapi import APIRouter
from app.api.health import router as health_router
from app.api.chat import router as chat_router
from app.api.chat_stream import router as chat_stream_router
from app.api.admin import router as admin_router

router = APIRouter()
router.include_router(health_router)
router.include_router(chat_router)
router.include_router(chat_stream_router)
router.include_router(admin_router)
