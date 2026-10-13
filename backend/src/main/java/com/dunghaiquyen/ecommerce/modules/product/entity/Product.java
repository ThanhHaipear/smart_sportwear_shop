package com.dunghaiquyen.ecommerce.modules.product.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import com.dunghaiquyen.ecommerce.modules.brand.entity.Brand;
import com.dunghaiquyen.ecommerce.modules.category.entity.Category;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.ProductTypeDefinition;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.SizeSystem;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.Sport;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.UseCase;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.Technology;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.JoinTable;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Aggregate root for product + variant + image: all three are edited together
 * and share one transactional boundary, so variants/images cascade with the product.
 */
@Getter
@Setter
@Entity
@Table(name = "products")
public class Product extends AbstractAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_type_id", nullable = false)
    private ProductTypeDefinition productTypeDefinition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "size_system_id", nullable = false)
    private SizeSystem sizeSystem;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, unique = true, length = 300)
    private String slug;

    @Column(name = "short_description", length = 500)
    private String shortDescription;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender_code", nullable = false, length = 12)
    private Gender gender;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "product_sports",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "sport_id"))
    private Set<Sport> sports = new LinkedHashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "product_use_cases",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "use_case_id"))
    private Set<UseCase> useCases = new LinkedHashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "product_technologies",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "technology_id"))
    private Set<Technology> technologies = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status = ProductStatus.DRAFT;

    @Transient
    private boolean featured = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> attributes = new LinkedHashMap<>();

    // Advanced only by CatalogOutboxService. Keeping it out of Hibernate UPDATE
    // statements prevents a managed Product with an older value from undoing the
    // atomic JDBC increment performed when an event is appended.
    @Column(name = "catalog_version", nullable = false, updatable = false)
    private long catalogVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", nullable = false, length = 20, updatable = false)
    private ProductDataSource dataSource = ProductDataSource.REAL;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<ProductVariant> variants = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    @Transient
    public String getSportType() {
        return sports.stream().findFirst().map(Sport::getCode).orElse(null);
    }

    public void setSportType(String ignored) {
        // Compatibility only. ProductService resolves and assigns the Sport entity.
    }

    @Transient
    public ProductType getProductType() {
        return productTypeDefinition == null ? null : ProductType.fromCatalogCode(productTypeDefinition.getCode());
    }

    public void setProductType(ProductType ignored) {
        // Compatibility only. ProductService resolves and assigns the lookup entity.
    }
}
