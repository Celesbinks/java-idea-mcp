package dev.ideamcp;

import com.google.gson.*;
import java.util.*;
import static dev.ideamcp.protocol.Json.object;

/** Bounded UTF-16 stream offsets; stdout/stderr only, never process command lines. */
final class RunLogBuffer {
    private record Chunk(long start, String type, String text) { long end() { return start + text.length(); } }
    private final int capacity, maxChunks;
    private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
    private long end;
    RunLogBuffer(int capacity, int maxChunks) { this.capacity = capacity; this.maxChunks = maxChunks; }
    synchronized void append(String type, String text) {
        if (text.isEmpty()) return;
        long start = end; end += text.length();
        int skip = Math.max(0, text.length() - capacity);
        chunks.addLast(new Chunk(start + skip, type, text.substring(skip)));
        while (chunks.size() > maxChunks || chunks.getFirst().end() <= end - capacity) chunks.removeFirst();
        Chunk first = chunks.getFirst();
        int trim = (int)Math.max(0, end - capacity - first.start);
        if (trim > 0) { chunks.removeFirst(); chunks.addFirst(new Chunk(first.start + trim, first.type, first.text.substring(trim))); }
        first = chunks.getFirst();
        if (!first.text.isEmpty() && Character.isLowSurrogate(first.text.charAt(0))) {
            chunks.removeFirst();
            if (first.text.length() > 1) chunks.addFirst(new Chunk(first.start + 1, first.type, first.text.substring(1)));
        }
    }
    synchronized JsonObject read(String cursor, int maxChars) {
        long available = chunks.isEmpty() ? end : chunks.getFirst().start;
        long requested;
        try {
            if (cursor != null && !cursor.matches("0|[1-9][0-9]*")) throw new NumberFormatException();
            requested = cursor == null ? Math.max(available, end - maxChars) : Long.parseLong(cursor);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("INVALID_CURSOR: use nextCursor from the same log session"); }
        if (requested > end) throw new IllegalArgumentException("INVALID_CURSOR: cursor is beyond this log session");
        long start = Math.max(available, requested), stop = Math.min(end, start + maxChars);
        if (start < end && Character.isLowSurrogate(charAt(start))) start++;
        if (stop < end && stop > start && Character.isHighSurrogate(charAt(stop - 1))) stop--;
        JsonArray entries = new JsonArray();
        for (Chunk c : chunks) {
            if (c.end() <= start || c.start >= stop) continue;
            int from = (int)Math.max(0, start - c.start), to = (int)Math.min(c.text.length(), stop - c.start);
            entries.add(object("stream", c.type, "text", c.text.substring(from, to)));
        }
        return object("entries", entries, "startCursor", Long.toString(start), "nextCursor", Long.toString(stop),
                "availableFromCursor", Long.toString(available), "endCursor", Long.toString(end), "hasMore", stop < end,
                "truncated", requested < available, "cursorAdjusted", start != Math.max(available, requested));
    }
    private char charAt(long position) {
        for (Chunk c : chunks) if (position >= c.start && position < c.end()) return c.text.charAt((int)(position - c.start));
        throw new IllegalArgumentException("INVALID_CURSOR: unavailable character");
    }
}
