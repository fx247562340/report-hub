package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.domain.*;
import com.reporthub.service.ConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminConfigController {
    private final ConfigService configService;

    public AdminConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/datasources")
    public ApiResponse<List<DataSourceDef>> listDs() {
        return ApiResponse.ok(configService.listDatasources());
    }

    @PostMapping("/datasources")
    public ApiResponse<DataSourceDef> saveDs(@RequestBody DataSourceDef def) {
        return ApiResponse.ok(configService.saveDatasource(def));
    }

    @DeleteMapping("/datasources/{code}")
    public ApiResponse<Void> delDs(@PathVariable String code) {
        configService.deleteDatasource(code);
        return ApiResponse.ok(null);
    }

    @PostMapping("/datasources/test-login")
    public ApiResponse<Map<String, Object>> testLogin(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(configService.testLogin(body.get("code")));
    }

    @GetMapping("/endpoints")
    public ApiResponse<List<ApiEndpointDef>> listEp() {
        return ApiResponse.ok(configService.listEndpoints());
    }

    @PostMapping("/endpoints")
    public ApiResponse<ApiEndpointDef> saveEp(@RequestBody ApiEndpointDef def) {
        return ApiResponse.ok(configService.saveEndpoint(def));
    }

    @DeleteMapping("/endpoints/{code}")
    public ApiResponse<Void> delEp(@PathVariable String code) {
        configService.deleteEndpoint(code);
        return ApiResponse.ok(null);
    }

    @PostMapping("/endpoints/test")
    public ApiResponse<Map<String, Object>> testEndpoint(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(configService.testEndpoint(body));
    }

    @GetMapping("/datasets")
    public ApiResponse<List<DatasetDef>> listDsSets() {
        return ApiResponse.ok(configService.listDatasets());
    }

    @GetMapping("/datasets/{code}/mappings")
    public ApiResponse<List<FieldMappingDef>> mappings(@PathVariable String code) {
        return ApiResponse.ok(configService.listMappings(code));
    }

    @PostMapping("/datasets")
    public ApiResponse<DatasetDef> saveDataset(@RequestBody DatasetPayload payload) {
        return ApiResponse.ok(configService.saveDataset(payload.dataset(), payload.mappings()));
    }

    @DeleteMapping("/datasets/{code}")
    public ApiResponse<Void> delDataset(@PathVariable String code) {
        configService.deleteDataset(code);
        return ApiResponse.ok(null);
    }

    @GetMapping("/reports/{code}/relations")
    public ApiResponse<List<RelationDef>> relations(@PathVariable String code) {
        return ApiResponse.ok(configService.listRelations(code));
    }

    @PostMapping("/relations")
    public ApiResponse<RelationDef> saveRel(@RequestBody RelationDef def) {
        return ApiResponse.ok(configService.saveRelation(def));
    }

    @DeleteMapping("/relations/{code}")
    public ApiResponse<Void> delRel(@PathVariable String code) {
        configService.deleteRelation(code);
        return ApiResponse.ok(null);
    }

    @GetMapping("/reports")
    public ApiResponse<List<ReportDef>> reports() {
        return ApiResponse.ok(configService.listReports());
    }

    @PostMapping("/reports")
    public ApiResponse<ReportDef> saveReport(@RequestBody ReportDef def) {
        return ApiResponse.ok(configService.saveReport(def));
    }

    @DeleteMapping("/reports/{code}")
    public ApiResponse<Void> delReport(@PathVariable String code) {
        configService.deleteReport(code);
        return ApiResponse.ok(null);
    }

    public record DatasetPayload(DatasetDef dataset, List<FieldMappingDef> mappings) {}
}
