package com.dunghaiquyen.ecommerce.modules.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.common.storage.ImageStorageService;
import com.dunghaiquyen.ecommerce.common.storage.UploadedImage;
import com.dunghaiquyen.ecommerce.modules.catalog.service.CatalogReferenceService;
import com.dunghaiquyen.ecommerce.modules.product.dto.ImageCreateRequest;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductImageUploadResponse;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.mapper.ProductMapper;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.dunghaiquyen.ecommerce.visualsearch.outbox.CatalogEventType;
import com.dunghaiquyen.ecommerce.visualsearch.outbox.CatalogOutboxService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Pure business-logic unit tests (Mockito, no Spring context, no DB) for the
 * two edge cases that matter most for Cloudinary integration and are awkward
 * to assert deterministically through a full HTTP+Postgres integration test:
 * cleanup-after-failed-save, and delete-tolerates-remote-failure while only
 * calling the remote provider AFTER COMMIT. Validation rules are covered here
 * too since they need no DB at all.
 */
@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductImageRepository imageRepository;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ImageStorageService imageStorageService;

    @Mock
    private CatalogOutboxService catalogOutboxService;

    @Mock
    private CatalogReferenceService catalogReferenceService;

    private ProductImageService service;
    private UUID productId;
    private Product product;

    @BeforeEach
    void setUp() {
        service = new ProductImageService(
                productRepository,
                imageRepository,
                productMapper,
                imageStorageService,
                catalogOutboxService,
                catalogReferenceService,
                "res.cloudinary.com");
        productId = UUID.randomUUID();
        product = new Product();
        product.setId(productId);
    }

    private MultipartFile validImageFile() {
        return new MockMultipartFile("file", "shirt.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});
    }

    // ===== validation =====

    // validateImageFile runs before the product lookup, so none of these three
    // ever touch productRepository/imageStorageService - asserted via the
    // never()-upload check, deliberately without stubbing findById at all.

    @Test
    void uploadImage_emptyFile_rejectedBeforeAnyUploadOrDbCall() {
        MultipartFile empty = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.uploadImage(productId, empty, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
        verify(imageStorageService, never()).upload(any());
    }

    @Test
    void uploadImage_nonImageContentType_rejected() {
        MultipartFile textFile = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.uploadImage(productId, textFile, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
        verify(imageStorageService, never()).upload(any());
    }

    @Test
    void uploadImage_unsupportedImageMimeRejectedBeforeUpload() {
        MultipartFile svg = new MockMultipartFile(
                "file", "unsafe.svg", "image/svg+xml", "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.uploadImage(productId, svg, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
        verify(imageStorageService, never()).upload(any());
    }

    @Test
    void uploadImage_oversizedFile_rejected() {
        byte[] tooBig = new byte[6 * 1024 * 1024];
        MultipartFile big = new MockMultipartFile("file", "big.jpg", "image/jpeg", tooBig);

        assertThatThrownBy(() -> service.uploadImage(productId, big, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
        verify(imageStorageService, never()).upload(any());
    }

    @Test
    void uploadImage_productNotFound_returns404_beforeUploading() {
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadImage(productId, validImageFile(), null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(imageStorageService, never()).upload(any());
    }

    // ===== cleanup after a failed DB save =====

    @Test
    void uploadImage_dbSaveFails_cleansUpTheJustUploadedRemoteAsset() {
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        UploadedImage uploaded = new UploadedImage("products/abc123", "https://cdn.test/abc123.jpg", 800, 600);
        when(imageStorageService.upload(any())).thenReturn(uploaded);
        when(imageRepository.saveAndFlush(any(ProductImage.class))).thenThrow(new RuntimeException("DB is down"));

        assertThatThrownBy(() -> service.uploadImage(productId, validImageFile(), "alt", null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB is down");

        verify(imageStorageService).delete("products/abc123");
    }

    @Test
    void uploadImage_dbSaveFails_andCleanupAlsoFails_originalExceptionStillSurfaces() {
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        UploadedImage uploaded = new UploadedImage("products/abc123", "https://cdn.test/abc123.jpg", null, null);
        when(imageStorageService.upload(any())).thenReturn(uploaded);
        when(imageRepository.saveAndFlush(any(ProductImage.class))).thenThrow(new RuntimeException("DB is down"));
        org.mockito.Mockito.doThrow(new RuntimeException("Cloudinary also unreachable"))
                .when(imageStorageService)
                .delete("products/abc123");

        // The cleanup failure must never mask/replace the original DB failure.
        assertThatThrownBy(() -> service.uploadImage(productId, validImageFile(), null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB is down");
    }

    @Test
    void uploadImage_success_returnsWidthAndHeightFromUpload() {
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        UploadedImage uploaded = new UploadedImage("products/xyz", "https://cdn.test/xyz.jpg", 1024, 768);
        when(imageStorageService.upload(any())).thenReturn(uploaded);

        ProductImage saved = new ProductImage();
        saved.setId(UUID.randomUUID());
        saved.setProduct(product);
        saved.setImageUrl(uploaded.secureUrl());
        saved.setPublicId(uploaded.publicId());
        saved.setSortOrder(0);
        when(imageRepository.saveAndFlush(any(ProductImage.class))).thenReturn(saved);

        ProductImageUploadResponse response = service.uploadImage(productId, validImageFile(), "alt text", null, true, 2);

        assertThat(response.publicId()).isEqualTo("products/xyz");
        assertThat(response.imageUrl()).isEqualTo("https://cdn.test/xyz.jpg");
        assertThat(response.width()).isEqualTo(1024);
        assertThat(response.height()).isEqualTo(768);
        verify(catalogOutboxService)
                .append(CatalogEventType.PRODUCT_IMAGE_CREATED, productId, saved.getId());
    }

    @Test
    void addImage_approvedHttpsUrl_savesAndEnqueuesVisualIndexing() {
        UUID imageId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        ProductImage saved = new ProductImage();
        saved.setId(imageId);
        saved.setProduct(product);
        saved.setImageUrl("https://res.cloudinary.com/demo/image/upload/products/shirt.jpg");
        saved.setSortOrder(0);
        when(imageRepository.saveAndFlush(any(ProductImage.class))).thenReturn(saved);

        service.addImage(
                productId,
                new ImageCreateRequest(saved.getImageUrl(), "products/shirt", "shirt", null, 0, false));

        verify(catalogOutboxService).append(CatalogEventType.PRODUCT_IMAGE_CREATED, productId, imageId);
    }

    @Test
    void addImage_unapprovedOrUnsafeUrl_rejectedBeforeDbWrite() {
        List<String> unsafeUrls = List.of(
                "https://placehold.co/700x700/test.jpg",
                "http://res.cloudinary.com/demo/image/upload/test.jpg",
                "https://user:password@res.cloudinary.com/demo/image/upload/test.jpg",
                "https://res.cloudinary.com:8443/demo/image/upload/test.jpg");

        for (String unsafeUrl : unsafeUrls) {
            assertThatThrownBy(() -> service.addImage(
                            productId,
                            new ImageCreateRequest(unsafeUrl, null, null, null, 0, false)))
                    .isInstanceOf(BusinessRuleException.class);
        }

        verify(productRepository, never()).findById(any());
        verify(imageRepository, never()).saveAndFlush(any());
        verify(catalogOutboxService, never()).append(any(), any(), any());
    }

    // ===== delete tolerates a failing remote cleanup =====

    @Test
    void deleteImage_remoteCleanupRunsAfterCommit_only() {
        UUID imageId = UUID.randomUUID();
        ProductImage image = new ProductImage();
        image.setId(imageId);
        image.setProduct(product);
        image.setPublicId("products/leftover");

        when(imageRepository.findById(imageId)).thenReturn(Optional.of(image));
        when(imageRepository.findAllByProductIdOrderBySortOrderAsc(productId)).thenReturn(List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            List<?> result = service.deleteImage(productId, imageId);

            assertThat(result).isEmpty();
            verify(imageRepository).delete(image);
            verify(imageStorageService, never()).delete("products/leftover");

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(imageStorageService).delete("products/leftover");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteImage_remoteCleanupThrowsAfterCommit_dbDeleteStillSucceeds_noExceptionPropagates() {
        UUID imageId = UUID.randomUUID();
        ProductImage image = new ProductImage();
        image.setId(imageId);
        image.setProduct(product);
        image.setPublicId("products/leftover");

        when(imageRepository.findById(imageId)).thenReturn(Optional.of(image));
        when(imageRepository.findAllByProductIdOrderBySortOrderAsc(productId)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("Cloudinary unreachable"))
                .when(imageStorageService)
                .delete("products/leftover");

        TransactionSynchronizationManager.initSynchronization();
        try {
            List<?> result = service.deleteImage(productId, imageId);

            assertThat(result).isEmpty();
            verify(imageRepository).delete(image);
            verify(imageStorageService, never()).delete("products/leftover");

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(imageStorageService).delete("products/leftover");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteImage_imageBelongsToDifferentProduct_returns404_doesNotDelete() {
        UUID imageId = UUID.randomUUID();
        Product otherProduct = new Product();
        otherProduct.setId(UUID.randomUUID());
        ProductImage image = new ProductImage();
        image.setId(imageId);
        image.setProduct(otherProduct);

        when(imageRepository.findById(imageId)).thenReturn(Optional.of(image));

        assertThatThrownBy(() -> service.deleteImage(productId, imageId)).isInstanceOf(ResourceNotFoundException.class);
        verify(imageRepository, never()).delete(any(ProductImage.class));
        verify(imageStorageService, never()).delete(any());
    }

    @Test
    void deleteImage_noPublicId_skipsRemoteCleanup() {
        UUID imageId = UUID.randomUUID();
        ProductImage image = new ProductImage();
        image.setId(imageId);
        image.setProduct(product);
        image.setPublicId(null);

        when(imageRepository.findById(imageId)).thenReturn(Optional.of(image));
        when(imageRepository.findAllByProductIdOrderBySortOrderAsc(productId)).thenReturn(List.of());

        service.deleteImage(productId, imageId);

        verify(imageStorageService, never()).delete(any());
    }
}
