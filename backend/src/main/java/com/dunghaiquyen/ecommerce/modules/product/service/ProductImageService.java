package com.dunghaiquyen.ecommerce.modules.product.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.common.storage.ImageStorageService;
import com.dunghaiquyen.ecommerce.common.storage.UploadedImage;
import com.dunghaiquyen.ecommerce.modules.catalog.service.CatalogReferenceService;
import com.dunghaiquyen.ecommerce.modules.product.dto.ImageCreateRequest;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductImageResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductImageUploadResponse;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.entity.ImageSourceKind;
import com.dunghaiquyen.ecommerce.modules.product.mapper.ProductMapper;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.dunghaiquyen.ecommerce.visualsearch.outbox.CatalogEventType;
import com.dunghaiquyen.ecommerce.visualsearch.outbox.CatalogOutboxService;
import java.net.URI;
import java.net.URISyntaxException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductImageService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> SUPPORTED_IMAGE_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final ProductMapper productMapper;
    private final ImageStorageService imageStorageService;
    private final CatalogOutboxService catalogOutboxService;
    private final Set<String> catalogImageAllowedHosts;
    private final CatalogReferenceService catalogReferenceService;

    public ProductImageService(
            ProductRepository productRepository,
            ProductImageRepository imageRepository,
            ProductMapper productMapper,
            ImageStorageService imageStorageService,
            CatalogOutboxService catalogOutboxService,
            CatalogReferenceService catalogReferenceService,
            @Value("${app.visual-search.catalog-image-allowed-hosts:res.cloudinary.com}")
                    String catalogImageAllowedHosts) {
        this.productRepository = productRepository;
        this.imageRepository = imageRepository;
        this.productMapper = productMapper;
        this.imageStorageService = imageStorageService;
        this.catalogOutboxService = catalogOutboxService;
        this.catalogReferenceService = catalogReferenceService;
        this.catalogImageAllowedHosts = Arrays.stream(catalogImageAllowedHosts.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Legacy flow: admin pastes an already-hosted image URL directly (no upload). Kept side by side with uploadImage - see class javadoc on uploadImage for the tradeoff. */
    @Transactional
    public ProductImageResponse addImage(UUID productId, ImageCreateRequest request) {
        validateCatalogImageUrl(request.imageUrl());
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        boolean makePrimary = request.isPrimary() != null && request.isPrimary();
        ProductImage image = insertImageRow(
                product, request.imageUrl(), request.publicId(), request.altText(), request.color(),
                request.sortOrder(), makePrimary, null, null);
        return productMapper.toImageResponse(image);
    }

    /**
     * New flow (Cloudinary integration): uploads the file to the configured
     * ImageStorageService first, then writes the product_images row. Both
     * flows are kept side by side rather than replacing addImage - a real
     * deployment may still want to import already-hosted images (CDN
     * migration, bulk seed, etc.) without re-uploading them through this
     * server, and removing that capability was never asked for.
     *
     * <p>If the DB write fails after a successful upload, the just-uploaded
     * remote asset is deleted best-effort so a failed request does not leak
     * storage forever - if that cleanup itself fails, it is only logged
     * (never thrown), since the request is already failing for the original
     * DB reason and a second, unrelated failure would just obscure it.
     */
    @Transactional
    public ProductImageUploadResponse uploadImage(
            UUID productId, MultipartFile file, String altText, String color, Boolean isPrimary, Integer sortOrder) {
        validateImageFile(file);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        UploadedImage uploaded = imageStorageService.upload(file);
        boolean makePrimary = isPrimary != null && isPrimary;
        try {
            ProductImage image =
                    insertImageRow(product, uploaded.secureUrl(), uploaded.publicId(), altText, color,
                            sortOrder, makePrimary, uploaded, file);
            return new ProductImageUploadResponse(
                    image.getId(),
                    image.getImageUrl(),
                    image.getPublicId(),
                    image.getAltText(),
                    image.getColor(),
                    image.isPrimary(),
                    image.getSortOrder(),
                    uploaded.width(),
                    uploaded.height());
        } catch (RuntimeException ex) {
            attemptRemoteCleanup(uploaded.publicId());
            throw ex;
        }
    }

    /**
     * The DB row is still the source of truth, but the actual remote delete is
     * deferred until AFTER COMMIT. Calling Cloudinary here, inside the open
     * transaction, would still create the exact inconsistency this method is
     * trying to avoid: if a late JPA/DB failure aborted commit after the remote
     * asset had already been deleted, the DB row would survive with a now-dead
     * URL. afterCommit closes that gap. A remote-delete failure (already gone,
     * network error, etc.) is logged and swallowed - the delete the caller asked
     * for (the DB row) already succeeded and must not be reported as a failure.
     */
    @Transactional
    public List<ProductImageResponse> deleteImage(UUID productId, UUID imageId) {
        ProductImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found"));
        if (!image.getProduct().getId().equals(productId)) {
            throw new ResourceNotFoundException("Image not found");
        }

        String publicId = image.getPublicId();
        catalogOutboxService.append(CatalogEventType.PRODUCT_IMAGE_DELETED, productId, imageId);
        imageRepository.delete(image);
        if (publicId != null && !publicId.isBlank()) {
            scheduleRemoteDeleteAfterCommit(publicId);
        }

        return imageRepository.findAllByProductIdOrderBySortOrderAsc(productId).stream()
                .map(productMapper::toImageResponse)
                .toList();
    }

    /**
     * Promote an already-uploaded image to the product's primary/featured image.
     * Unsets the current primary first and flushes before setting the new one, so
     * the partial unique index (V2, "max 1 primary per product") is never even
     * momentarily violated - the same ordering {@link #insertImageRow} relies on.
     */
    @Transactional
    public List<ProductImageResponse> setPrimary(UUID productId, UUID imageId) {
        productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        ProductImage target = imageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found"));
        if (!target.getProduct().getId().equals(productId)) {
            throw new ResourceNotFoundException("Image not found");
        }
        if (!target.isPrimary()) {
            List<ProductImage> currentPrimaries = imageRepository.findAllByProductIdAndPrimaryTrue(productId);
            currentPrimaries.forEach(img -> img.setPrimary(false));
            imageRepository.saveAll(currentPrimaries);
            imageRepository.flush();
            target.setPrimary(true);
            try {
                imageRepository.saveAndFlush(target);
            } catch (DataIntegrityViolationException ex) {
                throw new BusinessRuleException("Another image was just set as primary for this product, please retry");
            }
        }
        return imageRepository.findAllByProductIdOrderBySortOrderAsc(productId).stream()
                .map(productMapper::toImageResponse)
                .toList();
    }

    /**
     * ERD_PHASE1.md 6.9: "mỗi product nên có tối đa 1 ảnh primary". The unset-old-
     * primary step below is a read-modify-write, which by itself cannot close a
     * concurrency race: two requests adding a primary image for the same product
     * at the same time can both read "no conflicting primary yet" and both insert
     * with is_primary = true. The partial unique index added in
     * V2__product_images_primary_unique_index.sql is the actual safety net - this
     * service-level step only handles the common sequential case cleanly (and
     * gives a normal 200, not a retry) without leaning on the DB exception path.
     * saveAndFlush forces the INSERT to run here, inside the try, instead of
     * being deferred to commit (UUID ids do not need an immediate flush to get an
     * id back, so a plain save() would let the violation surface after this
     * method already returned, uncaught).
     */
    private ProductImage insertImageRow(
            Product product,
            String imageUrl,
            String publicId,
            String altText,
            String color,
            Integer sortOrder,
            boolean makePrimary,
            UploadedImage uploaded,
            MultipartFile sourceFile) {
        if (makePrimary) {
            product = productRepository.findByIdForUpdate(product.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
            List<ProductImage> currentPrimaries = imageRepository.findAllByProductIdAndPrimaryTrue(product.getId());
            currentPrimaries.forEach(img -> img.setPrimary(false));
            imageRepository.saveAll(currentPrimaries);
            // Without this, Hibernate's default flush ordering runs INSERTs before
            // UPDATEs, so the new primary=true row would hit the DB before this
            // unset-UPDATE does and trip the partial unique index even in the
            // normal, non-racing, single-request case (caught by testing).
            imageRepository.flush();
        }

        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setImageUrl(imageUrl);
        image.setPublicId(publicId != null && !publicId.isBlank()
                ? publicId.trim()
                : "external/" + UUID.randomUUID());
        image.setAssetVersion(uploaded != null ? uploaded.assetVersion() : "external");
        image.setFormat(uploaded != null ? uploaded.format() : formatFromUrl(imageUrl));
        image.setBytes(uploaded != null && uploaded.bytes() != null
                ? uploaded.bytes()
                : sourceFile != null ? sourceFile.getSize() : null);
        image.setWidth(uploaded != null ? uploaded.width() : null);
        image.setHeight(uploaded != null ? uploaded.height() : null);
        image.setChecksum(sourceFile != null ? sha256(sourceFile) : null);
        image.setSourceKind(uploaded != null ? ImageSourceKind.OWNED : ImageSourceKind.LICENSED);
        image.setSourceUri(uploaded == null ? imageUrl : null);
        image.setLicenseCode(uploaded != null ? "OWNED_BY_SHOP" : "EXTERNAL_DECLARED");
        image.setAltText(altText);
        image.setColorDefinition(color != null && !color.isBlank()
                ? catalogReferenceService.requireColor(color)
                : null);
        image.setSortOrder(sortOrder != null ? sortOrder : 0);
        image.setPrimary(makePrimary);

        try {
            ProductImage saved = imageRepository.saveAndFlush(image);
            catalogOutboxService.append(CatalogEventType.PRODUCT_IMAGE_CREATED, product.getId(), saved.getId());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException("Another image was just set as primary for this product, please retry");
        }
    }

    private String formatFromUrl(String imageUrl) {
        String path = imageUrl.split("[?#]", 2)[0];
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "unknown";
    }

    private String sha256(MultipartFile file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(file.getBytes());
            return java.util.HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Could not fingerprint image file");
        }
    }

    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Image file is required");
        }
        String contentType = file.getContentType();
        if (contentType == null || !SUPPORTED_IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessRuleException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only JPEG, PNG and WebP product images are allowed");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Image file must be at most 5MB");
        }
    }

    /**
     * Reject a legacy URL import before persisting it when the visual worker
     * would reject the same source. Redirect targets and resolved IPs are still
     * revalidated by the worker at download time.
     */
    private void validateCatalogImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Image URL is required");
        }
        final URI uri;
        try {
            uri = new URI(imageUrl);
        } catch (URISyntaxException ex) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Image URL is invalid");
        }
        String host = uri.getHost();
        boolean safe = "https".equalsIgnoreCase(uri.getScheme())
                && host != null
                && uri.getUserInfo() == null
                && uri.getPort() == -1
                && catalogImageAllowedHosts.contains(host.toLowerCase(Locale.ROOT));
        if (!safe) {
            throw new BusinessRuleException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Image URL must use HTTPS and an approved catalog image host");
        }
    }

    private void attemptRemoteCleanup(String publicId) {
        try {
            imageStorageService.delete(publicId);
        } catch (RuntimeException ex) {
            log.warn("Failed to clean up remote image asset {}: {}", publicId, ex.getMessage());
        }
    }

    private void scheduleRemoteDeleteAfterCommit(String publicId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            attemptRemoteCleanup(publicId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                attemptRemoteCleanup(publicId);
            }
        });
    }
}
