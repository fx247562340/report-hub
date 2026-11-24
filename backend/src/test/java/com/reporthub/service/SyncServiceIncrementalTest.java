package com.reporthub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.domain.ApiEndpointDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyncServiceIncrementalTest {

    @Test
    void crossMonthFiltersAppendMonthsSplitsIntoSeparateRequests() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SyncService svc = new SyncService(null, null, null, null, null, null, null, mapper, null, null, null);

        ApiEndpointDef ep = new ApiEndpointDef();
        ep.setCode("u9c_ship_l");
        ep.setBodyTemplate("""
                {
                  "EntityFullName": "UFIDA.U9.SM.Ship.ShipLine",
                  "PageSize": {{pageSize}},
                  "PageIndex": {{page}},
                  "Filters": [
                    {"Field": "Wh", "Operator": "eq", "Value": "1002605241220237"}
                  ]
                }
                """);

        String spec = "{\"filtersAppendMonths\": {\"Field\": \"Ship.DocNo\", \"Prefix\": \"SM\", \"SinceYM\": \"{{sinceYM}}\"}}";
        List<ApiEndpointDef> expanded = svc.expandIncrementalEndpoints(ep, spec, "2026-10-30", "202611");

        assertEquals(2, expanded.size(), "跨月应拆为 2 个独立请求，不能合并到一个 Filters 里 AND");

        JsonNode b1 = mapper.readTree(expanded.get(0).getBodyTemplate().replace("{{pageSize}}", "500").replace("{{page}}", "1"));
        JsonNode b2 = mapper.readTree(expanded.get(1).getBodyTemplate().replace("{{pageSize}}", "500").replace("{{page}}", "1"));

        assertEquals(2, b1.get("Filters").size());
        assertEquals("SM202610%", b1.get("Filters").get(1).get("Value").asText());

        assertEquals(2, b2.get("Filters").size());
        assertEquals("SM202611%", b2.get("Filters").get(1).get("Value").asText());
    }
}
