package com.reporthub.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory rowset with dataset code prefix awareness. */
public class Dataset {
    private final String code;
    private final List<Map<String, Object>> rows = new ArrayList<>();

    public Dataset(String code) {
        this.code = code;
    }

    public String code() { return code; }

    public List<Map<String, Object>> rows() { return rows; }

    public void addRow(Map<String, Object> row) {
        rows.add(new LinkedHashMap<>(row));
    }

    public Dataset copyEmpty() {
        return new Dataset(code);
    }
}
