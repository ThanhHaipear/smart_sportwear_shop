from __future__ import annotations

from typing import Any
from app.tools.registry import ToolDefinition
from app.services import product_search_service

DEFINITION = ToolDefinition(
    name="search_products",
    description="Tìm sản phẩm theo từ khóa, danh mục, thể thao, giới tính, khoảng giá.",
    capability="product_search",
    requires_auth=False,
    requires_confirmation=False,
    input_schema={"query": "string"},
)


async def run(args: dict[str, Any]) -> dict[str, Any]:
    query = args.get("query", "")
    result = await product_search_service.search(
        query, parsed_query=args.get("parsed_query"), session_id=args.get("_session_id")
    )
    return result.model_dump()
