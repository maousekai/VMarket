package com.vmarket.delivery.service;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.assignment-retry.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AssignmentRetryJob {
    private final AssignmentRetryWorker worker;

    @Scheduled(fixedDelayString = "${app.assignment-retry.delay:5000}")
    public void run() {
        worker.runBatch();
    }
}
