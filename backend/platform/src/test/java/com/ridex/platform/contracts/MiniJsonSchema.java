package com.ridex.platform.contracts;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * A deliberately small JSON-Schema checker: just the keywords the RideX event schemas use (type, required, properties, enum,
 * format uuid / date-time, minimum, minLength, maxLength). Enough to make the schema files an enforced contract rather than
 * documentation that drifts, without pulling a validator library into the build.
 * Unknown properties are always allowed (tolerant reader), as the schemas themselves say.
 */
public final class MiniJsonSchema {

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");

    private MiniJsonSchema() {
    }

    /** Returns what is wrong, one message per problem; empty means valid. */
    public static List<String> validate(JsonNode schema, JsonNode value) {
        List<String> problems = new ArrayList<>();
        check(schema, value, "$", problems);
        return problems;
    }

    private static void check(JsonNode schema, JsonNode value, String path, List<String> problems) {
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) {
                found |= option.equals(value);
            }
            if (!found) {
                problems.add(path + ": " + value + " is not one of " + schema.get("enum"));
            }
            return;
        }
        if (schema.has("type") && !typeMatches(schema.get("type"), value)) {
            problems.add(path + ": expected " + schema.get("type") + " but found " + value.getNodeType());
            return;
        }
        if (value.isNull()) {
            return;
        }
        if (value.isString()) {
            String format = schema.path("format").asString("");
            String text = value.asString();
            if ("uuid".equals(format) && !UUID.matcher(text).matches()) {
                problems.add(path + ": not a uuid: " + text);
            }
            if ("date-time".equals(format) && !DATE_TIME.matcher(text).matches()) {
                problems.add(path + ": not an ISO date-time: " + text);
            }
            if (schema.has("minLength") && text.length() < schema.get("minLength").asInt()) {
                problems.add(path + ": shorter than " + schema.get("minLength").asInt());
            }
            if (schema.has("maxLength") && text.length() > schema.get("maxLength").asInt()) {
                problems.add(path + ": longer than " + schema.get("maxLength").asInt());
            }
        }
        if (value.isNumber() && schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble()) {
            problems.add(path + ": below " + schema.get("minimum"));
        }
        if (value.isObject()) {
            for (JsonNode name : schema.path("required")) {
                if (!value.has(name.asString())) {
                    problems.add(path + ": missing required field '" + name.asString() + "'");
                }
            }
            Iterator<Map.Entry<String, JsonNode>> properties = schema.path("properties").properties().iterator();
            while (properties.hasNext()) {
                Map.Entry<String, JsonNode> property = properties.next();
                if (value.has(property.getKey())) {
                    check(property.getValue(), value.get(property.getKey()), path + "." + property.getKey(), problems);
                }
            }
        }
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode option : type) {
                if (typeMatches(option, value)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asString()) {
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "null" -> value.isNull();
            default -> false;
        };
    }
}
