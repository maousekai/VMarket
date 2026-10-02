package com.vmarket.auth;

import org.springframework.boot.test.context.SpringBootTest;

/** {@link OtpRaceScenarios} trên H2 (MODE=PostgreSQL) — luôn chạy, không cần Docker. */
@SpringBootTest
class OtpConcurrencyTest extends OtpRaceScenarios {
}
