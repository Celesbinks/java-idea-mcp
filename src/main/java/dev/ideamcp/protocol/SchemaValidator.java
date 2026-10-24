package dev.ideamcp.protocol;

import com.google.gson.*;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Validates published schemas before an IDE implementation receives any arguments.
 */
public final class SchemaValidator {
    private SchemaValidator() {
    }

    public static void validate(JsonObject schema, JsonObject value) {
        check(schema, value, schema, "arguments", 0, true);
    }

    private static void check(JsonElement element, JsonElement value, JsonObject root, String path, int depth, boolean top) {
        if (depth > 64) fail(path, "schema nesting is too deep");
        if (element.isJsonPrimitive()) {
            if (!element.getAsBoolean()) fail(path, "is not allowed");
            return;
        }
        JsonObject s = element.getAsJsonObject();
        if (s.has("$ref")) {
            String ref = s.get("$ref").getAsString();
            if (!ref.startsWith("#/")) fail(path, "external schema references are unsupported");
            JsonElement target = root;
            for (String part : ref.substring(2).split("/")) {
                if (!target.isJsonObject() || !target.getAsJsonObject().has(part.replace("~1", "/").replace("~0", "~")))
                    fail(path, "unresolved schema reference");
                target = target.getAsJsonObject().get(part.replace("~1", "/").replace("~0", "~"));
            }
            check(target, value, root, path, depth + 1, top);
        }
        for (String op : new String[]{"allOf", "anyOf", "oneOf"})
            if (s.has(op)) {
                int matched = 0, count = s.getAsJsonArray(op).size();
                for (JsonElement branch : s.getAsJsonArray(op))
                    try {
                        check(branch, value, root, path, depth + 1, false);
                        matched++;
                    } catch (IllegalArgumentException ignored) {
                    }
                if ((op.equals("allOf") && matched != count) || (op.equals("anyOf") && matched == 0) || (op.equals("oneOf") && matched != 1))
                    fail(path, "does not match " + op);
            }
        if (s.has("not")) {
            boolean matches = true;
            try {
                check(s.get("not"), value, root, path, depth + 1, false);
            } catch (IllegalArgumentException e) {
                matches = false;
            }
            if (matches) fail(path, "matches a prohibited schema");
        }
        if (s.has("const") && !s.get("const").equals(value)) fail(path, "has an invalid value");
        if (s.has("enum") && !s.getAsJsonArray("enum").asList().contains(value))
            fail(path, "has an invalid enum value");
        if (s.has("type")) {
            JsonElement types = s.get("type");
            boolean matches = false;
            if (types.isJsonArray())
                for (JsonElement t : types.getAsJsonArray()) matches |= type(t.getAsString(), value);
            else matches = type(types.getAsString(), value);
            if (!matches) fail(path, "has an incorrect type");
        }
        if (value.isJsonNull()) return;
        if (value.isJsonObject()) {
            JsonObject o = value.getAsJsonObject(), properties = s.has("properties") ? s.getAsJsonObject("properties") : new JsonObject();
            if (s.has("required")) for (JsonElement required : s.getAsJsonArray("required"))
                if (!o.has(required.getAsString())) fail(path, "missing " + required.getAsString());
            for (String key : o.keySet()) {
                if (properties.has(key))
                    check(properties.get(key), o.get(key), root, path + "." + key, depth + 1, false);
                else if (top || (s.has("additionalProperties") && s.get("additionalProperties").isJsonPrimitive() && !s.get("additionalProperties").getAsBoolean()))
                    fail(path, "unknown argument " + key);
                else if (s.has("additionalProperties") && s.get("additionalProperties").isJsonObject())
                    check(s.get("additionalProperties"), o.get(key), root, path + "." + key, depth + 1, false);
            }
        } else if (value.isJsonArray()) {
            JsonArray a = value.getAsJsonArray();
            bounds(s, "minItems", "maxItems", BigDecimal.valueOf(a.size()), path);
            if (s.has("uniqueItems") && s.get("uniqueItems").getAsBoolean() && a.asList().stream().distinct().count() != a.size())
                fail(path, "contains duplicate items");
            if (s.has("items")) for (int i = 0; i < a.size(); i++)
                check(s.get("items"), a.get(i), root, path + "[" + i + "]", depth + 1, false);
        } else if (value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            bounds(s, "minLength", "maxLength", BigDecimal.valueOf(text.codePointCount(0, text.length())), path);
            if (s.has("pattern") && !Pattern.compile(s.get("pattern").getAsString()).matcher(text).find())
                fail(path, "does not match the required pattern");
        } else if (value.getAsJsonPrimitive().isNumber()) {
            BigDecimal n;
            try {
                n = value.getAsBigDecimal();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(path + " must be a finite number");
            }
            bounds(s, "minimum", "maximum", n, path);
            if (s.has("exclusiveMinimum") && n.compareTo(s.get("exclusiveMinimum").getAsBigDecimal()) <= 0)
                fail(path, "is below its lower bound");
            if (s.has("exclusiveMaximum") && n.compareTo(s.get("exclusiveMaximum").getAsBigDecimal()) >= 0)
                fail(path, "is above its upper bound");
        }
    }

    private static boolean type(String type, JsonElement v) {
        return switch (type) {
            case "null" -> v.isJsonNull();
            case "object" -> v.isJsonObject();
            case "array" -> v.isJsonArray();
            case "string" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
            case "boolean" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
            case "number" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
            case "integer" -> {
                try {
                    yield v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            default -> throw new IllegalArgumentException("Unsupported schema type: " + type);
        };
    }

    private static void bounds(JsonObject s, String low, String high, BigDecimal n, String path) {
        if (s.has(low) && n.compareTo(s.get(low).getAsBigDecimal()) < 0) fail(path, "is below " + low);
        if (s.has(high) && n.compareTo(s.get(high).getAsBigDecimal()) > 0) fail(path, "is above " + high);
    }

    private static void fail(String path, String message) {
        throw new IllegalArgumentException(path + ": " + message);
    }
}
