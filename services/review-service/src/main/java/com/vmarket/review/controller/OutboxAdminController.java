package com.vmarket.review.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.review.service.ReviewOutboxRecovery;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin review outbox", description = "[Admin] Giám sát và phát lại sự kiện ReviewCreated bị DEAD")
@RestController
@RequestMapping("/api/reviews/admin/outbox")
@RequiredArgsConstructor
@Validated
public class OutboxAdminController {

	private final ReviewOutboxRecovery recovery;

	public record Replay(@NotBlank @Size(max = 500) String reason) {
	}

	@Operation(summary = "Số sự kiện đang chờ / DEAD")
	@GetMapping("/status")
	public ReviewOutboxRecovery.Status status() {
		return recovery.status();
	}

	@Operation(summary = "Danh sách sự kiện DEAD (cũ nhất trước)")
	@GetMapping("/dead")
	public List<ReviewOutboxRecovery.DeadEvent> dead(@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return recovery.dead(page, size);
	}

	@Operation(summary = "Phát lại một sự kiện DEAD")
	@PostMapping("/{id}/replay")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void replay(@PathVariable String id, @AuthenticationPrincipal String admin,
			@Valid @RequestBody Replay request) {
		recovery.replay(id, admin, request.reason());
	}

	@Operation(summary = "Phát lại toàn bộ sự kiện DEAD (sau khi broker đã hồi phục)")
	@PostMapping("/replay-dead")
	public Map<String, Integer> replayAll(@AuthenticationPrincipal String admin, @Valid @RequestBody Replay request) {
		return Map.of("replayed", recovery.replayAll(admin, request.reason()));
	}
}
