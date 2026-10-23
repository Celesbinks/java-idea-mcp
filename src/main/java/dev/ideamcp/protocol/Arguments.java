package dev.ideamcp.protocol;

import com.google.gson.*;

public final class Arguments {
    private Arguments() {}
    public static String string(JsonObject args, String key, String fallback) {
        JsonElement value = args.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new IllegalArgumentException(key + " must be a non-empty string");
        return value.getAsString();
    }
    public static String required(JsonObject args, String key) {
        String value = string(args, key, null);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
    public static int integer(JsonObject args, String key, int fallback, int min, int max) {
        JsonElement value = args.get(key);
        if (value == null) return fallback;
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            int number = value.getAsBigDecimal().intValueExact();
            if (number < min || number > max) throw new ArithmeticException();
            return number;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max);
        }
    }
    public static boolean bool(JsonObject args, String key, boolean fallback) {
        JsonElement value = args.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(key + " must be a boolean");
        return value.getAsBoolean();
    }
}
