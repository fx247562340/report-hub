package com.reporthub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每 30 分钟预冷筛选选项缓存（值变化后最多半小时内下拉自动更新） */
@Component
public class FilterOptionScheduler {
    private static final Logger log = LoggerFactory.getLogger(FilterOptionScheduler.class);
    private final FilterOptionService filterOptionService;

    public FilterOptionScheduler(FilterOptionService filterOptionService) {
        this.filterOptionService = filterOptionService;
    }

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 120_000)
    public void refresh() {
        try {
            int n = filterOptionService.refreshAll();
            if (n > 0) log.info("filter options refreshed: {}", n);
        } catch (Exception ignored) {
        }
    }
}
