package com.reporthub.engine;

import com.reporthub.domain.RelationDef;
import com.reporthub.service.ContractScrollService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportRelationRegistryTest {

    @Test
    void providesBuiltInJoinRelationsForMultiTableReports() {
        List<RelationDef> hangRels = ReportRelationRegistry.relationsFor("rpt_hang_complete");
        assertEquals(3, hangRels.size());
        assertEquals("rel_hang_u9c_complete", hangRels.get(0).getCode());
        assertEquals("rel_complete_u9c_mo", hangRels.get(1).getCode());
        assertEquals("rel_hang_zinc", hangRels.get(2).getCode());

        List<RelationDef> wdiffRels = ReportRelationRegistry.relationsFor("rpt_white_weight_diff");
        assertEquals(1, wdiffRels.size());
        assertEquals("rel_wdiff_hang_u9c", wdiffRels.get(0).getCode());

        assertTrue(ReportRelationRegistry.relationsFor("rpt_cust_month_in").isEmpty());
        assertTrue(ReportRelationRegistry.relationsFor("rpt_cust_month_out").isEmpty());
    }

    @Test
    void providesBuiltInLineageForContractScrollReport() {
        ReportRelationRegistry.BuiltinLineage lineage =
                ReportRelationRegistry.builtinLineageFor(ContractScrollService.REPORT_CODE);
        assertNotNull(lineage);
        assertEquals(3, lineage.stages().size());
        assertEquals(9, lineage.inputs().size());
        assertEquals("contract_scroll_baselines", lineage.baselineTable());
        assertNull(ReportRelationRegistry.builtinLineageFor("rpt_hang_complete"));
    }
}
