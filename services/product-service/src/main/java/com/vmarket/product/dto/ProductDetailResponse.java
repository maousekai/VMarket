package com.vmarket.product.dto;

import java.util.List;

public record ProductDetailResponse(ProductResponse product, List<ProductResponse> similarProducts) {
}
