package com.dunghaiquyen.ecommerce.common.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Real provider, active only when app.storage.provider=cloudinary. All
 * product images are uploaded into a single fixed "products" folder - no
 * per-tenant/per-category folder structure is needed at this project's scale,
 * and adding one would be speculative for a requirement nobody has asked for.
 */
@Service
@ConditionalOnProperty(prefix = "app.storage", name = "provider", havingValue = "cloudinary")
public class CloudinaryImageStorageService implements ImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryImageStorageService.class);
    private static final String FOLDER = "products";

    private final Cloudinary cloudinary;

    public CloudinaryImageStorageService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    @Override
    public UploadedImage upload(MultipartFile file) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = cloudinary.uploader()
                    .upload(file.getBytes(), ObjectUtils.asMap("folder", FOLDER));
            String publicId = (String) result.get("public_id");
            String secureUrl = (String) result.get("secure_url");
            Integer width = toInteger(result.get("width"));
            Integer height = toInteger(result.get("height"));
            String assetVersion = String.valueOf(result.getOrDefault("version", "unversioned"));
            String format = String.valueOf(result.getOrDefault("format", "unknown"));
            Long bytes = toLong(result.get("bytes"));
            return new UploadedImage(publicId, secureUrl, assetVersion, format, bytes, width, height);
        } catch (IOException ex) {
            throw new ImageStorageException("Failed to upload image to Cloudinary", ex);
        }
    }

    @Override
    public void delete(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
            // Cloudinary returns result="not found" (not an error) for an already-gone
            // asset - both "ok" and "not found" mean the asset is gone either way, so
            // neither is treated as a failure here.
            Object status = result.get("result");
            log.debug("Cloudinary destroy({}) -> {}", publicId, status);
        } catch (IOException ex) {
            throw new ImageStorageException("Failed to delete image " + publicId + " from Cloudinary", ex);
        }
    }

    private Integer toInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
