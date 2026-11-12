package com.tgyozalyan.channelmanager.util;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import tools.jackson.databind.json.JsonMapper;

public class JsonObjectMapperFactory {
    public static final JsonMapper OBJECT_MAPPER = create();

    public static JsonMapper create() {
        //        mapper.registeredModules().addAll(
//                new SimpleModule().addDeserializer(String.class, new TrimStringDeserializer(String.class)));
//        )
//        mapper.registerModule(new JavaTimeModule());
        // All java.time objects (like Instant, LocalDate, etc.)
        // are serialized as human-readable ISO-8601 strings instead of raw timestamps, e.g:
        // Instant: "2025-01-02T00:00:00Z"
        // LocalDate: "2025-01-02"
        // LocalDateTime: "2025-01-02T10:15:30"
        // ZonedDateTime: "2024-12-25T10:15:30+02:00[Europe/Kyiv]"
//        mapper.disable(WRITE_DATES_AS_TIMESTAMPS);
        return JsonMapper.builder()
                // will detect any (including private) field, getter, isGetter, setter, creator, scalar-constructor
                .changeDefaultVisibility((h) -> h.with(JsonAutoDetect.Visibility.ANY)).build();
    }
    public static tools.jackson.databind.node.ObjectNode json() {
        return JsonObjectMapperFactory.OBJECT_MAPPER.createObjectNode();
    }

//    private static class TrimStringDeserializer extends StdScalarDeserializer<String> {
//        public TrimStringDeserializer(Class<?> vc) {
//            super(vc);
//        }
//
//        @Override
//        public String deserialize(JsonParser p, DeserializationContext cxt) throws IOException {
//            String s = p.getValueAsString();
//            return s == null ? s : s.trim();
//        }
//    }
}
