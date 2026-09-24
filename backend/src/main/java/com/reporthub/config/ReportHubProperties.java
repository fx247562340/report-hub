package com.reporthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reporthub")
public class ReportHubProperties {
    private Jwt jwt = new Jwt();
    private Security security = new Security();
    private Engine engine = new Engine();
    private boolean seed = true;

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }
    public Engine getEngine() { return engine; }
    public void setEngine(Engine engine) { this.engine = engine; }
    public boolean isSeed() { return seed; }
    public void setSeed(boolean seed) { this.seed = seed; }

    public static class Jwt {
        private String secret = "report-hub-dev-secret-change-me-32bytes-min!";
        private long ttlHours = 24;
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getTtlHours() { return ttlHours; }
        public void setTtlHours(long ttlHours) { this.ttlHours = ttlHours; }
    }

    public static class Security {
        private String corsAllowedOrigins = "http://localhost:5173";
        public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
        public void setCorsAllowedOrigins(String corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
    }

    public static class Engine {
        private int lookupConcurrency = 8;
        private int maxKeysPerBatch = 200;
        private int maxRootRows = 5000;
        /** 完整结果缓存秒数；0 关闭 */
        private int resultCacheTtlSeconds = 600;
        /** 查询后后台预热全量缓存，便于紧接着导出秒开 */
        private boolean warmOnQuery = true;
        /** 首页看板聚合结果缓存秒数 */
        private int dashboardTtlSeconds = 90;
        public int getLookupConcurrency() { return lookupConcurrency; }
        public void setLookupConcurrency(int lookupConcurrency) { this.lookupConcurrency = lookupConcurrency; }
        public int getMaxKeysPerBatch() { return maxKeysPerBatch; }
        public void setMaxKeysPerBatch(int maxKeysPerBatch) { this.maxKeysPerBatch = maxKeysPerBatch; }
        public int getMaxRootRows() { return maxRootRows; }
        public void setMaxRootRows(int maxRootRows) { this.maxRootRows = maxRootRows; }
        public int getResultCacheTtlSeconds() { return resultCacheTtlSeconds; }
        public void setResultCacheTtlSeconds(int resultCacheTtlSeconds) { this.resultCacheTtlSeconds = resultCacheTtlSeconds; }
        public boolean isWarmOnQuery() { return warmOnQuery; }
        public void setWarmOnQuery(boolean warmOnQuery) { this.warmOnQuery = warmOnQuery; }
        public int getDashboardTtlSeconds() { return dashboardTtlSeconds; }
        public void setDashboardTtlSeconds(int dashboardTtlSeconds) { this.dashboardTtlSeconds = dashboardTtlSeconds; }
    }
}
