package com.dunghaiquyen.ecommerce.common.storage;

/** Provider metadata persisted with product_images; nullable fields support test/fallback adapters. */
public record UploadedImage(
        String publicId,
        String secureUrl,
        String assetVersion,
        String format,
        Long bytes,
        Integer width,
        Integer height) {

    public UploadedImage(String publicId, String secureUrl, Integer width, Integer height) {
        this(publicId, secureUrl, "unversioned", extensionOf(secureUrl), null, width, height);
    }

    private static String extensionOf(String url) {
        if (url == null) return "unknown";
        String path = url.split("[?#]", 2)[0];
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1).toLowerCase() : "unknown";
    }
}
