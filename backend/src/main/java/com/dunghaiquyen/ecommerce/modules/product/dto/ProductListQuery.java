package com.dunghaiquyen.ecommerce.modules.product.dto;

import com.dunghaiquyen.ecommerce.modules.product.entity.Gender;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Query params per API_SPEC_PHASE1.md 5.3, extended in Phase N3 with q/
 * categorySlug/brandSlug/sort (kept alongside the original keyword/
 * categoryId/brandId/sortBy/sortOrder, never replacing them, so any existing
 * caller keeps working unchanged). Every field is optional - all nullable.
 *
 * <p>"size" already means variant-size filter here (e.g. "M", "40") and
 * predates Phase N3; Phase N3's literal request for a "size" param meaning
 * page size was deliberately NOT added under that name - it would silently
 * collide with this existing facet (numeric shoe/pants sizes like "40" are
 * exactly as plausible as a page-size value). Page size stays on the
 * existing "limit" param instead - see PHASE_N3 notes in ProductService.
 */
public record ProductListQuery(
        Integer page,
        Integer limit,
        String keyword,
        String q,
        UUID categoryId,
        UUID brandId,
        String categorySlug,
        String brandSlug,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        String size,
        String color,
        /** Product-level discount band derived from variant price/compareAtPrice. */
        String discount,
        Gender gender,
        String sportType,
        /** Football-boot surface facet: FG (natural grass), TF/AG (artificial), IC (futsal). */
        String surface,
        String sortBy,
        String sortOrder,
        String sort,
        /** Schema 1A product type; legacy aliases remain accepted during client migration. */
        ProductType productType,
        /** Catalog V2: filter by collection slug (e.g. "bst-mua-he-2026"). */
        String collection,
        /** Filter by featured flag - used by storefront homepage gallery. */
        Boolean featured) {
}
