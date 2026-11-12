package com.tgyozalyan.channelmanager.util;


import tools.jackson.core.type.TypeReference;

/** With version 3.x Jackson doesn't through checked exceptions, so maybe this utility isn't necessary? */
public interface JsonUtil {
    static String writeValueAsString(Object o) {
        return JsonObjectMapperFactory.OBJECT_MAPPER.writeValueAsString(o);
    }

    static <T> T readValue(String s, Class<T> cl) {
        return JsonObjectMapperFactory.OBJECT_MAPPER.readValue(s, cl);
    }

    static <T> T readValue(String s, TypeReference<T> cl) {
        return JsonObjectMapperFactory.OBJECT_MAPPER.readValue(s, cl);
    }
}
