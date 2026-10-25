package com.dunghaiquyen.ecommerce.visualsearch.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record VisualSearchCreateModelRequest(
        @NotBlank String provider,
        @NotBlank String model,
        @NotBlank String modelVersion,
        @NotBlank String preprocessingVersion,
        @Min(1) int dimensions) {
}
