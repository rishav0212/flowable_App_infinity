package com.example.flowable_app.service;

import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

/**
 * Global helper for Map creation.
 * usage: ${map.of('key', 'value', 'key2', 123)}
 */
@Service("map") // <--- This name makes it available as ${map...}
public class FlowableMapService {

    public Map<String, Object> of(Object... args) {
        Map<String, Object> result = new HashMap<>();
        if (args == null || args.length == 0) return result;

        if (args.length % 2 != 0) {
            throw new FlowableIllegalArgumentException("map.of() requires pairs (key, value).");
        }

        for (int i = 0; i < args.length; i += 2) {
            result.put(String.valueOf(args[i]), args[i + 1]);
        }
        return result;
    }
}