package com.vmarket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Phản hồi chung cho các thao tác chỉ cần xác nhận thành công, không trả dữ liệu. */
@Schema(name = "MessageResponse")
public record MessageResponse(String message) {
}
