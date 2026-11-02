package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.common.BizException;
import com.reporthub.domain.ZnLedger;
import com.reporthub.repo.ZnLedgerRepo;
import com.reporthub.service.ExcelExportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * 锌耗日结台账（液位法）。
 * 量程 K = 锅长 × 锅宽 × 密度（kg/mm）；读数从上沿往下量，读数变大 = 液面下降。
 * 当日锌耗 = 加锌合计 + (期末-期初) × K。
 */
@RestController
@RequestMapping("/api/zn-ledger")
public class ZnLedgerController {
    private final ZnLedgerRepo repo;

    public ZnLedgerController(ZnLedgerRepo repo) {
        this.repo = repo;
    }

    public record AddItem(String t, BigDecimal w) {}

    public record SaveReq(
            Long id,
            LocalDate workDate,
            BigDecimal potLength,
            BigDecimal potWidth,
            BigDecimal zincDensity,
            BigDecimal h0,
            BigDecimal h1,
            List<AddItem> adds,
            String note
    ) {}

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        List<ZnLedger> rows;
        if (start != null && !start.isBlank() && end != null && !end.isBlank()) {
            rows = repo.findByWorkDateBetweenOrderByWorkDateDesc(LocalDate.parse(start), LocalDate.parse(end));
        } else {
            rows = repo.findAllByOrderByWorkDateDesc();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ZnLedger r : rows) out.add(toMap(r));
        return ApiResponse.ok(out);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        List<ZnLedger> rows;
        if (start != null && !start.isBlank() && end != null && !end.isBlank()) {
            rows = repo.findByWorkDateBetweenOrderByWorkDateDesc(LocalDate.parse(start), LocalDate.parse(end));
        } else {
            rows = repo.findAllByOrderByWorkDateDesc();
        }
        // 台账习惯按日期升序导出
        List<ZnLedger> asc = new ArrayList<>(rows);
        asc.sort(Comparator.comparing(ZnLedger::getWorkDate));

        List<Map<String, Object>> cols = List.of(
                Map.of("key", "work_date", "label", "日期"),
                Map.of("key", "h0", "label", "班次开工前读数mm"),
                Map.of("key", "h1", "label", "班次结束时读数mm"),
                Map.of("key", "level_drop_mm", "label", "液面变化mm"),
                Map.of("key", "add_zinc_kg", "label", "加锌kg"),
                Map.of("key", "day_zinc_kg", "label", "当日锌耗kg"),
                Map.of("key", "day_zinc_t", "label", "当日锌耗t"),
                Map.of("key", "pot_length", "label", "锅长m"),
                Map.of("key", "pot_width", "label", "锅宽m"),
                Map.of("key", "zinc_density", "label", "密度g/cm3"),
                Map.of("key", "k_kg_per_mm", "label", "量程K_kg_per_mm"),
                Map.of("key", "adds_text", "label", "加锌明细"),
                Map.of("key", "note", "label", "备注")
        );
        List<Map<String, Object>> body = new ArrayList<>();
        for (ZnLedger r : asc) {
            Map<String, Object> m = toMap(r);
            m.put("day_zinc_t", r.getDayZincKg().divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP));
            m.put("adds_text", addsText(r.getAddsJson()));
            body.add(m);
        }
        byte[] bytes = ExcelExportService.toXlsx(cols, body, "锌耗台账");
        String filename = URLEncoder.encode("锌耗台账.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + filename)
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(bytes);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody SaveReq req) {
        if (req.workDate() == null) throw new BizException("日期不能为空");
        if (req.potLength() == null || req.potLength().signum() <= 0) throw new BizException("锅长要填正数");
        if (req.potWidth() == null || req.potWidth().signum() <= 0) throw new BizException("锅宽要填正数");
        if (req.zincDensity() == null || req.zincDensity().signum() <= 0) throw new BizException("密度要填正数");
        if (req.h0() == null || req.h1() == null) throw new BizException("班次开工前/结束读数都要填");

        if (repo.existsByWorkDateAndIdNot(req.workDate(), req.id() == null ? -1L : req.id())) {
            throw new BizException("日期 " + req.workDate() + " 已有记录，请改用更新或换日期");
        }

        BigDecimal k = req.potLength().multiply(req.potWidth()).multiply(req.zincDensity())
                .setScale(3, RoundingMode.HALF_UP);
        BigDecimal drop = req.h1().subtract(req.h0()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lv = drop.multiply(k).setScale(2, RoundingMode.HALF_UP);
        BigDecimal add = BigDecimal.ZERO;
        List<Map<String, Object>> adds = new ArrayList<>();
        if (req.adds() != null) {
            for (AddItem a : req.adds()) {
                if (a.w() == null || a.w().signum() == 0) continue;
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("t", a.t() == null ? "" : a.t());
                it.put("w", a.w());
                adds.add(it);
                add = add.add(a.w());
            }
        }
        add = add.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tot = add.add(lv).setScale(2, RoundingMode.HALF_UP);

        ZnLedger e;
        if (req.id() != null) {
            e = repo.findById(req.id()).orElseThrow(() -> new BizException("记录不存在"));
        } else {
            e = new ZnLedger();
            e.setCreatedAt(Instant.now());
        }
        e.setWorkDate(req.workDate());
        e.setPotLength(req.potLength());
        e.setPotWidth(req.potWidth());
        e.setZincDensity(req.zincDensity());
        e.setH0(req.h0());
        e.setH1(req.h1());
        e.setLevelDropMm(drop);
        e.setkKgPerMm(k);
        e.setAddZincKg(add);
        e.setLevelZincKg(lv);
        e.setDayZincKg(tot);
        e.setAddsJson(toJson(adds));
        e.setNote(req.note() == null ? "" : req.note().trim());
        e.setUpdatedAt(Instant.now());
        repo.save(e);
        return ApiResponse.ok(toMap(e));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) throw new BizException("记录不存在");
        repo.deleteById(id);
        return ApiResponse.ok("ok");
    }

    private static Map<String, Object> toMap(ZnLedger r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("work_date", r.getWorkDate().toString());
        m.put("pot_length", r.getPotLength());
        m.put("pot_width", r.getPotWidth());
        m.put("zinc_density", r.getZincDensity());
        m.put("h0", r.getH0());
        m.put("h1", r.getH1());
        m.put("level_drop_mm", r.getLevelDropMm());
        m.put("k_kg_per_mm", r.getkKgPerMm());
        m.put("add_zinc_kg", r.getAddZincKg());
        m.put("level_zinc_kg", r.getLevelZincKg());
        m.put("day_zinc_kg", r.getDayZincKg());
        m.put("adds", parseAdds(r.getAddsJson()));
        m.put("note", r.getNote());
        m.put("created_at", r.getCreatedAt().toString());
        m.put("updated_at", r.getUpdatedAt().toString());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseAdds(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json == null || json.isBlank() ? "[]" : json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String toJson(Object v) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.writeValueAsString(v);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String addsText(String json) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> a : parseAdds(json)) {
            if (sb.length() > 0) sb.append(" / ");
            Object t = a.get("t");
            Object w = a.get("w");
            if (t != null && !String.valueOf(t).isBlank()) sb.append(t).append(' ');
            sb.append(w).append("kg");
        }
        return sb.toString();
    }
}
