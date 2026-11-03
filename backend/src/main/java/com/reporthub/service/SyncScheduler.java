package com.reporthub.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟检查同步调度是否到点 */
@Component
public class SyncScheduler {
    private final SyncService syncService;

    public SyncScheduler(SyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void tick() {
        try {
            syncService.tickSchedule();
        } catch (Exception ignored) {
        }
    }
}
