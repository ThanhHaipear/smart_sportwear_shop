package com.dunghaiquyen.ecommerce.modules.product.search;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProductSuggestionService {
    private final JdbcTemplate jdbcTemplate;

    public ProductSuggestionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SearchSuggestionResponse> suggest(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String pattern = "%" + query.trim() + "%";
        return jdbcTemplate.query(
                """
                with suggestions as (
                    select 'CATEGORY' type, c.name label, c.name value, c.slug,
                           null::text thumbnail, null::numeric min_price, 'Danh mục' subtitle, 1 priority
                    from categories c
                    where c.status='ACTIVE' and c.name ilike ?
                    union all
                    select 'BRAND', b.name, b.name, b.slug, null, null, 'Thương hiệu', 2
                    from brands b where b.status='ACTIVE' and b.name ilike ?
                    union all
                    select 'PRODUCT', p.name, p.id::text, p.slug,
                           (select pi.secure_url from product_images pi where pi.product_id=p.id
                            order by pi.is_primary desc,pi.sort_order limit 1),
                           min(pv.price), concat(b.name,' · ',c.name), 3
                    from products p
                    join brands b on b.id=p.brand_id
                    join categories c on c.id=p.category_id
                    left join product_variants pv on pv.product_id=p.id and pv.status='ACTIVE'
                    where p.status='ACTIVE'
                      and (p.name ilike ? or b.name ilike ? or c.name ilike ?)
                    group by p.id,b.name,c.name
                )
                select type,label,value,slug,thumbnail,min_price,subtitle
                from suggestions order by priority,label limit 10
                """,
                (rs, row) -> new SearchSuggestionResponse(
                        rs.getString("type"), rs.getString("label"), rs.getString("value"),
                        rs.getString("value"),
                        rs.getString("slug"), rs.getString("thumbnail"),
                        rs.getBigDecimal("min_price"), rs.getString("subtitle")),
                pattern, pattern, pattern, pattern, pattern);
    }
}
