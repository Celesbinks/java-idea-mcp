package dev.ideamcp;

import com.google.gson.JsonObject;
import com.sun.jdi.*;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Map;
import static dev.ideamcp.protocol.Json.object;
import static org.junit.Assert.*;

public final class DebugValueTest {
    @SuppressWarnings("unchecked") private static <T> T mirror(Class<T> type,Map<String,Object> values){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(proxy,method,args)->{if(values.containsKey(method.getName())){Object value=values.get(method.getName());if(value instanceof Throwable t)throw t;return value;}throw new AssertionError("Unexpected JDI access: "+method.getName());});}
    @Test public void readsNullAndPrimitiveMirrors(){
        assertEquals("null",DebugService.value(null,object()).get("kind").getAsString());
        Type type=mirror(Type.class,Map.of("name","int"));IntegerValue value=mirror(IntegerValue.class,Map.of("type",type,"toString","7"));
        assertEquals("7",DebugService.value(value,object()).get("value").getAsString());
    }
    @Test public void objectAndArrayRenderingNeverInvokeMethods(){
        ReferenceType type=mirror(ReferenceType.class,Map.of("name","Sensitive"));
        ObjectReference instance=mirror(ObjectReference.class,Map.of("referenceType",type,"uniqueID",42L));
        JsonObject result=DebugService.value(instance,object());assertEquals("object",result.get("kind").getAsString());assertEquals("42",result.get("objectId").getAsString());
        ArrayReference array=mirror(ArrayReference.class,Map.of("referenceType",type,"uniqueID",43L,"length",3));assertEquals(3,DebugService.value(array,object()).get("length").getAsInt());
    }
    @Test public void stringTruncationPreservesUtf16Pairs(){
        StringReference text=mirror(StringReference.class,Map.of("value","A😀B"));JsonObject value=DebugService.value(text,object("maxStringLength",2));assertEquals("A",value.get("value").getAsString());assertTrue(value.get("truncated").getAsBoolean());assertEquals(4,value.get("length").getAsInt());
        assertEquals("A😀B",DebugService.value(text,object("maxStringLength",4)).get("value").getAsString());
    }
    @Test public void missingLocalDebugInformationFallsBackToArguments(){
        Type type=mirror(Type.class,Map.of("name","int"));IntegerValue number=mirror(IntegerValue.class,Map.of("type",type,"toString","7"));
        Map<String,Object> values=new java.util.HashMap<>();values.put("thisObject",null);values.put("visibleVariables",new AbsentInformationException());values.put("getArgumentValues",java.util.List.of(number));
        JsonObject result=DebugService.frameVariables(mirror(StackFrame.class,values),object());assertFalse(result.get("localInformationAvailable").getAsBoolean());assertEquals(1,result.get("total").getAsInt());assertEquals("arg0",result.getAsJsonArray("variables").get(0).getAsJsonObject().get("name").getAsString());
    }
}
