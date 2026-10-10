package com.dunghaiquyen.ecommerce.modules.product.controller;

import com.dunghaiquyen.ecommerce.common.response.ApiResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.AdminProductListItemResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.AdminProductListQuery;
import com.dunghaiquyen.ecommerce.modules.product.dto.ImageCreateRequest;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductCreateRequest;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductDetailResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductImageResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductImageUploadResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductUpdateRequest;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductVariantResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.VariantCreateRequest;
import com.dunghaiquyen.ecommerce.modules.product.service.ProductImageService;
import com.dunghaiquyen.ecommerce.modules.product.service.ProductService;
import com.dunghaiquyen.ecommerce.modules.product.service.ProductVariantService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/products")
@PreAuthorize("hasRole('ADMIN')")
public class AdminProductController {

    private final ProductService productService;
    private final ProductVariantService productVariantService;
    private final ProductImageService productImageService;

    public AdminProductController(
            ProductService productService,
            ProductVariantService productVariantService,
            ProductImageService productImageService) {
        this.productService = productService;
        this.productVariantService = productVariantService;
        this.productImageService = productImageService;
    }

    /** Full data set (DRAFT/ACTIVE/INACTIVE) - deliberately a separate query/response shape from the public listing, not a thin reuse of it. */
    @GetMapping
    public ApiResponse<List<AdminProductListItemResponse>> list(@ModelAttribute AdminProductListQuery query) {
        ProductService.AdminListResult result = productService.listAdmin(query);
        return ApiResponse.ok(result.items(), result.meta());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductDetailResponse>> create(@Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Product created", productService.create(request)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<ProductDetailResponse> update(
            @PathVariable UUID id, @Valid @RequestBody ProductUpdateRequest request) {
        return ApiResponse.ok("Product updated", productService.update(id, request));
    }

    // Not in API_SPEC_PHASE1.md section 9, added for the admin edit screen to load
    // current product+variants+images (same shape as the public detail response).
    @GetMapping("/{id}")
    public ApiResponse<ProductDetailResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(productService.getAdminDetail(id));
    }

    @PostMapping("/{id}/variants")
    public ResponseEntity<ApiResponse<ProductVariantResponse>> createVariant(
            @PathVariable UUID id, @Valid @RequestBody VariantCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Variant created", productVariantService.create(id, request)));
    }

    @PostMapping("/{id}/images")
    public ResponseEntity<ApiResponse<ProductImageResponse>> addImage(
            @PathVariable UUID id, @Valid @RequestBody ImageCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Image added", productImageService.addImage(id, request)));
    }

    /** Cloudinary integration - uploads the file itself, as opposed to addImage's "I already have a URL" flow. */
    @PostMapping(value = "/{id}/images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProductImageUploadResponse>> uploadImage(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String altText,
            @RequestParam(required = false) String color,
            @RequestParam(required = false) Boolean isPrimary,
            @RequestParam(required = false) Integer sortOrder) {
        ProductImageUploadResponse response = productImageService.uploadImage(id, file, altText, color, isPrimary, sortOrder);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Image uploaded", response));
    }

    @DeleteMapping("/{id}/images/{imageId}")
    public ApiResponse<List<ProductImageResponse>> deleteImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        return ApiResponse.ok("Image deleted", productImageService.deleteImage(id, imageId));
    }

    @PatchMapping("/{id}/images/{imageId}/primary")
    public ApiResponse<List<ProductImageResponse>> setPrimaryImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        return ApiResponse.ok("Primary image updated", productImageService.setPrimary(id, imageId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        productService.delete(id);
        return ApiResponse.ok("Product deleted", null);
    }
}
