package dev.ideamcp;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RunLogBufferTest {
    @Test public void cursorPagingRetainsStreamsAndDoesNotSplitEmoji() {
        RunLogBuffer buffer=new RunLogBuffer(100,10);buffer.append("stdout","A😀B");buffer.append("stderr","error\n");
        JsonObject first=buffer.read("0",2);assertEquals("A",text(first));assertEquals("1",first.get("nextCursor").getAsString());
        JsonObject second=buffer.read("1",2);assertEquals("😀",text(second));assertEquals("3",second.get("nextCursor").getAsString());
        JsonObject rest=buffer.read("3",20);assertEquals("Berror\n",text(rest));assertFalse(rest.get("hasMore").getAsBoolean());
        assertEquals("stderr",rest.getAsJsonArray("entries").get(1).getAsJsonObject().get("stream").getAsString());
        assertEquals("",text(buffer.read(rest.get("nextCursor").getAsString(),20)));
        assertTrue(buffer.read("2",20).get("cursorAdjusted").getAsBoolean());
    }
    @Test public void boundedCapacityAndChunkCountReportDroppedOutput() {
        RunLogBuffer buffer=new RunLogBuffer(5,2);buffer.append("stdout","abcdef");
        JsonObject result=buffer.read("0",20);assertEquals("bcdef",text(result));assertTrue(result.get("truncated").getAsBoolean());assertEquals("1",result.get("availableFromCursor").getAsString());
        buffer.append("stdout","g");buffer.append("stderr","h");result=buffer.read("0",20);assertEquals("gh",text(result));assertEquals("6",result.get("availableFromCursor").getAsString());
        buffer.append("stdout","123456789");assertEquals("56789",text(buffer.read("0",20)));
    }
    @Test public void tailEmptyAndInvalidCursors() {
        RunLogBuffer buffer=new RunLogBuffer(100,10);assertEquals("0",buffer.read(null,10).get("nextCursor").getAsString());
        buffer.append("stdout","hello world");assertEquals("world",text(buffer.read(null,5)));
        for(String cursor:new String[]{"-1","1.5","01","99999999999999999999999999999999","12"})assertThrows(IllegalArgumentException.class,()->buffer.read(cursor,10));
    }
    static String text(JsonObject result) {StringBuilder text=new StringBuilder();for(JsonElement e:result.getAsJsonArray("entries"))text.append(e.getAsJsonObject().get("text").getAsString());return text.toString();}
}
