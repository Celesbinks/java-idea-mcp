package dev.ideamcp.protocol;

import com.google.gson.*;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private Json() {}
    public static JsonObject object(Object... entries) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < entries.length; i += 2) result.add((String) entries[i], GSON.toJsonTree(entries[i + 1]));
        return result;
    }
}
