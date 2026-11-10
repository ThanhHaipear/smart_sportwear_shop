from __future__ import annotations

import time

from app.clients import catalog_api_client, search_api_client
from app.db import chat_history_repository
from app.retrieval.product.parser.query_parser import ParsedQuery, parse_query
from app.retrieval.product.query_rewrite import llm_rewriter
from app.retrieval.product.query_rewrite.ambiguity_detector import needs_pre_retrieval_rewrite
from app.schemas.product import AppliedFilters, ProductSearchItem, ProductSearchResult

_LIMIT = 5


def _search_filters(parsed: ParsedQuery) -> dict:
    return {
        "brandSlug": parsed.brand,
        "gender": parsed.gender,
        "sportType": parsed.sport_type_hint,
        "productType": parsed.product_type,
        "surface": parsed.surface,
        "color": parsed.color_family or parsed.color,
        "size": parsed.size,
        "minPrice": parsed.price_min,
        "maxPrice": parsed.price_max,
        "inStockOnly": True,
    }


def _to_item(document: dict) -> ProductSearchItem | None:
    variants = [v for v in document.get("variants", [])
                if v.get("status") == "ACTIVE" and int(v.get("availableQuantity") or 0) > 0]
    if not variants:
        return None
    prices = [float(v["price"]) for v in variants]
    images = sorted(document.get("images") or [], key=lambda image: image.get("sortOrder", 0))
    return ProductSearchItem(
        productId=str(document["productId"]), name=document.get("name") or "",
        slug=document.get("slug") or str(document["productId"]),
        category=document.get("category") or "", brandName=document.get("brand") or "",
        sportType=(document.get("sportCodes") or [None])[0], gender=document.get("gender"),
        priceMin=min(prices), priceMax=max(prices),
        availableColors=sorted({v["color"] for v in variants if v.get("color")}),
        availableSizes=sorted({v["size"] for v in variants if v.get("size")}),
        totalAvailable=sum(int(v.get("availableQuantity") or 0) for v in variants),
        primaryImage=images[0].get("url") if images else None,
    )


async def search(query: str, limit: int = _LIMIT,
                 parsed_query: ParsedQuery | dict | None = None,
                 session_id: str | None = None) -> ProductSearchResult:
    """RAG retrieval through Search API, enriched only through Catalog API."""
    parsed = parsed_query if isinstance(parsed_query, ParsedQuery) else parse_query(query)
    if needs_pre_retrieval_rewrite(query, parsed):
        rewritten = await llm_rewriter.rewrite_ambiguous_need(query)
        if rewritten.strip().casefold() != query.strip().casefold():
            return await _pipeline(rewritten, limit, parsed)
    return await _pipeline(query, limit, parsed, session_id=session_id)


async def _pipeline(query: str, limit: int, parsed: ParsedQuery | None = None,
                    session_id: str | None = None) -> ProductSearchResult:
    started = time.perf_counter()
    parsed = parsed or parse_query(query)
    response = await search_api_client.search_products(
        query, limit=limit, filters=_search_filters(parsed)
    )
    candidate_ids = [str(item["productId"]) for item in response.get("items", [])]
    documents = await catalog_api_client.get_index_documents(candidate_ids)
    by_id = {str(document["productId"]): document for document in documents}
    items = [item for product_id in candidate_ids
             if product_id in by_id and (item := _to_item(by_id[product_id])) is not None]
    await chat_history_repository.save_retrieval_trace(
        session_id, query, candidate_ids, response.get("searchMode"),
        round((time.perf_counter() - started) * 1000),
    )
    return ProductSearchResult(
        items=items, total=int(response.get("total") or len(items)),
        appliedFilters=AppliedFilters(
            keyword=query, productType=parsed.product_type, sportType=parsed.sport_type_hint,
            gender=parsed.gender, priceMin=parsed.price_min, priceMax=parsed.price_max,
        ),
    )


async def search_internal(request) -> dict:
    """Temporary response adapter; canonical endpoint is owned by search-service."""
    return await search_api_client.search_products(
        request.query, limit=request.limit,
        filters=request.filters.model_dump(exclude_none=True),
    )
