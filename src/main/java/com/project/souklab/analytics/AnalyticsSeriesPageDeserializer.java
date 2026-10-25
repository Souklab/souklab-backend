package com.project.souklab.analytics;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.souklab.dto.common.PaginatedResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deserializes the nested analytics series page and its grouped metric keys. */
public class AnalyticsSeriesPageDeserializer
        extends JsonDeserializer<PaginatedResponse<Map<AnalyticsMetric.Series.Key, Object>>> {

    @Override
    public PaginatedResponse<Map<AnalyticsMetric.Series.Key, Object>> deserialize(
            JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        List<Map<AnalyticsMetric.Series.Key, Object>> content = new ArrayList<>();
        for (JsonNode row : root.path("content")) {
            Map<AnalyticsMetric.Series.Key, Object> typedRow = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : row.properties()) {
                typedRow.put(AnalyticsMetric.Series.fromValue(field.getKey()),
                        mapper.convertValue(field.getValue(), Object.class));
            }
            content.add(typedRow);
        }
        return PaginatedResponse.<Map<AnalyticsMetric.Series.Key, Object>>builder()
                .content(content)
                .pageNumber(root.path("pageNumber").asInt())
                .pageSize(root.path("pageSize").asInt())
                .totalElements(root.path("totalElements").asLong())
                .totalPages(root.path("totalPages").asInt())
                .last(root.path("last").asBoolean())
                .build();
    }
}
