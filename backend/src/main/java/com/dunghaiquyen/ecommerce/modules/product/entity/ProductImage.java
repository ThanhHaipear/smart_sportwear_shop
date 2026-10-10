package com.dunghaiquyen.ecommerce.modules.product.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.Color;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "product_images")
public class ProductImage extends AbstractAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "product_id", referencedColumnName = "product_id", insertable = false, updatable = false),
        @JoinColumn(name = "variant_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private ProductVariant variant;

    @Column(name = "variant_id")
    private java.util.UUID variantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "color_id")
    private Color colorDefinition;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_provider", nullable = false, length = 20)
    private ImageStorageProvider storageProvider = ImageStorageProvider.CLOUDINARY;

    @Column(name = "public_id", nullable = false, length = 255)
    private String publicId;

    @Column(name = "asset_version", nullable = false, length = 50)
    private String assetVersion;

    @Column(name = "secure_url", nullable = false, columnDefinition = "text")
    private String secureUrl;

    @Column(nullable = false, length = 20)
    private String format;

    private Long bytes;

    @Column(name = "alt_text", length = 255)
    private String altText;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(length = 64, columnDefinition = "char(64)")
    private String checksum;

    private Integer width;
    private Integer height;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 20)
    private ImageSourceKind sourceKind;

    @Column(name = "source_uri", columnDefinition = "text")
    private String sourceUri;

    @Column(name = "license_code", nullable = false, length = 50)
    private String licenseCode;

    @Column(name = "source_group_code", length = 100)
    private String sourceGroupCode;

    @Transient
    public String getImageUrl() {
        return secureUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.secureUrl = imageUrl;
    }

    @Transient
    public String getColor() {
        return colorDefinition == null ? null : colorDefinition.getName();
    }

    public void setColor(String ignored) {
        // Compatibility only. ProductImageService resolves the color FK.
    }

    public void setVariant(ProductVariant variant) {
        this.variant = variant;
        this.variantId = variant == null ? null : variant.getId();
    }
}
