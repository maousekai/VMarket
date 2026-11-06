package com.vmarket.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Chỉ xoá token khi FCM nói rõ token không còn đăng ký. */
class FcmPushSenderTest {

	@Test
	void notFoundOrUnregisteredMeansInvalidToken() {
		assertThat(FcmPushSender.isUnregistered(404, "")).isTrue();
		assertThat(FcmPushSender.isUnregistered(400, """
				{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[
				 {"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}
				""")).isTrue();
	}

	@Test
	void invalidPayloadKeepsToken() {
		assertThat(FcmPushSender.isUnregistered(400, """
				{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"Invalid value at 'message.data'",
				 "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"INVALID_ARGUMENT"}]}}
				""")).isFalse();
		assertThat(FcmPushSender.isUnregistered(400, "not json")).isFalse();
		assertThat(FcmPushSender.isUnregistered(429, "{}")).isFalse();
	}
}
