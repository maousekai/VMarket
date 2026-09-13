package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.vmarket.auth.service.UsernameGenerator;

class UsernameGeneratorTest {

	private static final Predicate<String> NONE_TAKEN = u -> false;

	@Test
	void usesEmailLocalPart_whenFree() {
		assertThat(UsernameGenerator.generate("an.nguyen@example.com", NONE_TAKEN)).isEqualTo("an.nguyen");
	}

	@Test
	void stripsInvalidChars_andLowercases() {
		assertThat(UsernameGenerator.generate("An Nguyen+tag@x.com", NONE_TAKEN)).isEqualTo("annguyentag");
	}

	@Test
	void collapsesRuns_andTrimsSeparators() {
		assertThat(UsernameGenerator.generate("an..nguyen@x.com", NONE_TAKEN)).isEqualTo("an.nguyen");
		assertThat(UsernameGenerator.generate("-a-@x.com", NONE_TAKEN)).isEqualTo("user");
		assertThat(UsernameGenerator.generate("...@x.com", NONE_TAKEN)).isEqualTo("user");
	}

	@Test
	void fallsBackToUser_whenLocalPartTooShort() {
		String u = UsernameGenerator.generate("a@x.com", NONE_TAKEN);
		assertThat(u).isEqualTo("user");
	}

	@Test
	void appendsDigits_onCollision() {
		Predicate<String> baseTaken = "an.nguyen"::equals;
		String u = UsernameGenerator.generate("an.nguyen@example.com", baseTaken);
		assertThat(u).matches("an\\.nguyen\\d{4}");
	}

	@Test
	void neverExceeds50Chars_evenWithCollisionSuffix() {
		String longLocal = "a".repeat(80);
		// base bị coi là trùng -> phải thêm hậu tố số, kết quả vẫn <= 50
		String u = UsernameGenerator.generate(longLocal + "@x.com", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"::equals);
		assertThat(u.length()).isLessThanOrEqualTo(50);
	}
}
