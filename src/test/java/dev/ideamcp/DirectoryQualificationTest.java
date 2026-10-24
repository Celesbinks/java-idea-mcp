package dev.ideamcp;

import com.google.gson.*;
import com.intellij.mcpserver.McpSessionInvocationMode;
import com.intellij.mcpserver.settings.*;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;
import static dev.ideamcp.protocol.Json.object;

public final class DirectoryQualificationTest extends JavaCodeInsightFixtureTestCase {
    private McpSessionInvocationMode oldMode;
    private Map<String, McpToolDisallowListSettings.ToolState> oldStates;
    @Override protected void setUp() throws Exception {
        super.setUp(); Files.createDirectories(root());
        var filter=McpToolFilterSettings.getInstance(); oldMode=filter.getInvocationMode();filter.setInvocationMode(McpSessionInvocationMode.DIRECT);
        var settings=McpToolDisallowListSettings.getInstance();oldStates=settings.getToolStates();settings.setToolStates(Map.of());
    }
    @Override protected void tearDown() throws Exception {
        try { if(oldMode!=null)McpToolFilterSettings.getInstance().setInvocationMode(oldMode);if(oldStates!=null)McpToolDisallowListSettings.getInstance().setToolStates(oldStates); }
        finally {super.tearDown();}
    }
    private Path root(){return Path.of(getProject().getBasePath());}
    private void files(String directory,int count)throws Exception{
        Files.createDirectories(root().resolve(directory));for(int i=0;i<count;i++)Files.writeString(root().resolve(directory).resolve("file"+i+".txt"),"fixture");
    }
    private JsonObject args(String directory,int depth){return object("projectPath",root().toString(),"directoryPath",directory,"maxDepth",depth,"timeout",10000);}
    private OfficialMcpAdapter adapter(boolean enabled){return new OfficialMcpAdapter(true,true,kotlinx.coroutines.Dispatchers.getDefault(),enabled);}
    private void verify(String directory,int depth,boolean eligible)throws Exception{
        if(eligible)warm(directory);
        var input=args(directory,depth);
        background(()->{
            if(eligible)for(int i=0;i<3;i++)DirectoryTree.tryCall(getProject(),input,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
            try(var timing=QueryTiming.open(true)){
            var fast=DirectoryTree.tryCall(getProject(),input,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
            assertEquals(input+" "+QueryTiming.current().snapshot(),eligible,fast!=null);
            var expected=adapter(false).call("list_directory_tree",input);
            var actual=adapter(true).call("list_directory_tree",input);
            assertEquals(expected,actual);if(fast!=null)assertEquals(expected,fast);return null;}
        });
    }
    private void warm(String directory){
        WriteAction.run(()->{
            var vf=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root().resolve(directory));vf.refresh(false,true);
            VfsUtilCore.iterateChildrenRecursively(vf,null,file->{if(file.isDirectory())file.getChildren();return true;});
        });
    }

    public void testVisibleFileBoundaryAndShallowDepth()throws Exception{
        files("below",31);files("exact",32);files("partial",600);
        verify("below",100,false);verify("exact",2,true);verify("partial",100,true);
        files("nested/a",256);
        for(int d=0;d<31;d++)Files.createDirectories(root().resolve("nested/z"+d));
        verify("nested",2,false);verify("nested",3,true);verify("nested",100,true);
        for(int d=0;d<31;d++)files("narrow/d"+d,64);
        verify("narrow",100,false);
        files("mixed-flat/branch",1);files("mixed-flat",128);verify("mixed-flat",2,true);verify("mixed-flat",100,true);
    }
    public void testDirectoryHeavyAndProbeLimitUseOfficialResults()throws Exception{
        for(int i=0;i<520;i++)Files.createDirectories(root().resolve("dense/d"+i));
        verify("dense",100,false);
        for(int i=0;i<64;i++)files("mixed/d"+i,1);
        verify("mixed",100,false);
    }
    public void testStaleCompleteSmallWideVfsDoesNotDropDiskFiles()throws Exception{
        for(int d=0;d<64;d++)files("small/d"+d,1);
        warm("small");files("small/d0",400);
        verify("small",100,false);
        var expected=background(()->adapter(true).call("list_directory_tree",args("small",100)));
        assertTrue(expected.toString().contains("file399.txt"));
        warm("small");verify("small",100,true);
    }
    public void testProbeSnapshotSurvivesPartialListingAndCancellation()throws Exception{
        files("flat",600);verify("flat",100,true);
        Files.move(root().resolve("flat/file0.txt"),root().resolve("flat/renamed.txt"));verify("flat",100,true);
        Files.delete(root().resolve("flat/file1.txt"));verify("flat",100,true);
        assertNull(background(()->DirectoryTree.tryCall(getProject(),args("flat",100),System.nanoTime())));
        background(()->{
            Thread.currentThread().interrupt();
            try {DirectoryTree.tryCall(getProject(),args("flat",100),System.nanoTime()+TimeUnit.SECONDS.toNanos(20));fail("Interrupted traversal returned");}
            catch(InterruptedException expected){}
            finally{Thread.interrupted();}return null;
        });
    }

    /** Isolates only the released directory class and its nested classes, not IDE services. */
    private OfficialMcpAdapter.TreeQuery legacy()throws Exception{
        String path=System.getProperty("idea.mcp.test.baselineJar","");
        if(path.isBlank())throw new IllegalStateException("Pass baselinePluginJar pointing to the released 0.6.7 JAR");
        Map<String,byte[]> bytes=new HashMap<>();
        try(var jar=new JarFile(path)){
            for(var entry:Collections.list(jar.entries()))if(entry.getName().matches("dev/ideamcp/DirectoryTree(?:\\$[^/]+)?\\.class")){
                try(var stream=jar.getInputStream(entry)){bytes.put(entry.getName().replace('/','.').replaceFirst("\\.class$",""),stream.readAllBytes());}
            }
        }
        var loader=new ClassLoader(getClass().getClassLoader()){
            @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(!bytes.containsKey(name))return super.loadClass(name,resolve);
                Class<?> type=findLoadedClass(name);if(type==null){byte[] data=bytes.get(name);type=defineClass(name,data,0,data.length);}if(resolve)resolveClass(type);return type;
            }
        };
        var method=loader.loadClass("dev.ideamcp.DirectoryTree").getDeclaredMethod("tryCall",com.intellij.openapi.project.Project.class,JsonObject.class,long.class);method.setAccessible(true);
        return (project,input,deadline)->{
            try{return (JsonObject)method.invoke(null,project,input,deadline);}
            catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof Exception cause)throw cause;throw (Error)failure.getCause();}
        };
    }

    public void testThreeWayQualificationPerformance()throws Exception{
        if(!Boolean.getBoolean("idea.mcp.test.directoryQualificationBenchmark"))return;
        Path repository=Path.of(System.getProperty("user.dir"));
        copy(repository.resolve("src"),root().resolve("src"));
        copy(repository.resolve("build/benchmarks/068-qualification/input/natural"),root().resolve("natural-cold"));
        copy(repository.resolve("build/benchmarks/068-qualification/input/natural"),root().resolve("natural-warm"));
        for(int d=0;d<32;d++)files("large/d"+d,128);
        files("flat-large",1024);files("below",255);files("exact",256);
        files("gate-below",63);files("gate-exact",64);files("flat-127",127);files("flat-128",128);
        for(int d=0;d<64;d++)files("dense/d"+d,1);
        warm("src");warm("natural-warm");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var old=legacy();
        Map<String,OfficialMcpAdapter> adapters=Map.of("official",adapter(false),"legacy",new OfficialMcpAdapter(true,true,kotlinx.coroutines.Dispatchers.getDefault(),true,old),"current",adapter(true));
        List<String> cases=List.of("src","natural-cold","natural-warm","gate-below","gate-exact","flat-127","flat-128","below","exact","dense","flat-large","large");
        JsonArray rows=new JsonArray();JsonObject scopes=new JsonObject();
        background(()->{
            String token="qualification_fixture_AAAAAAAAAAAAAAA";
            Map<String,McpHttpServer> servers=new HashMap<>();
            try(var client=HttpClient.newHttpClient()){
                for(var p:adapters.entrySet()){var server=server(p.getValue(),token);server.start();servers.put(p.getKey(),server);}
                var permutations=List.of(List.of("official","legacy","current"),List.of("legacy","current","official"),List.of("current","official","legacy"),List.of("official","current","legacy"),List.of("current","legacy","official"),List.of("legacy","official","current"));
                for(int pass=0;pass<2;pass++)for(String directory:pass==0?cases:cases.reversed()){
                    var input=args(directory,100);var expected=adapters.get("official").call("list_directory_tree",input);
                    var data=expected.getAsJsonObject("structuredContent");assertTrue(data.getAsJsonArray("errors").isEmpty());
                    scopes.add(directory,object("nodes",data.get("tree").getAsString().stripTrailing().lines().count(),"characters",data.get("tree").getAsString().length()));
                    for(int round=-3;round<84;round++){
                        var order=permutations.get(Math.floorMod(round+pass,6));
                        for(int position=0;position<order.size();position++){
                            String p=order.get(position);var request=HttpRequest.newBuilder(URI.create(servers.get(p).url())).timeout(java.time.Duration.ofSeconds(20))
                                    .header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Accept","application/json, text/event-stream")
                                    .POST(HttpRequest.BodyPublishers.ofString(object("jsonrpc","2.0","id",1,"method","tools/call","params",object("name","list_directory_tree","arguments",input)).toString())).build();
                            long start=System.nanoTime();var response=client.send(request,HttpResponse.BodyHandlers.ofString());double ms=(System.nanoTime()-start)/1_000_000.0;
                            assertEquals(200,response.statusCode());var envelope=JsonParser.parseString(response.body()).getAsJsonObject();assertFalse(envelope.has("error"));assertEquals(expected,envelope.get("result"));
                            String timings=response.headers().firstValue("Server-Timing").orElse("");
                            JsonObject phases=new JsonObject();var matcher=java.util.regex.Pattern.compile("([a-z_]+);dur=([0-9.]+)").matcher(timings);while(matcher.find())phases.addProperty(matcher.group(1),Double.parseDouble(matcher.group(2)));
                            if(round>=0)rows.add(object("pass",pass,"directory",directory,"p",p,"round",round,"position",position,"ms",ms,"phases",phases));
                        }
                    }
                    Files.writeString(repository.resolve("build/benchmarks/068-qualification/progress.json"),object("pass",pass,"directory",directory,"formal",rows.size()).toString());
                }
            }finally{for(var server:servers.values())server.close();}
            return null;
        });
        Files.writeString(repository.resolve("build/benchmarks/068-qualification/sandbox.json"),object("rows",rows,"scopes",scopes,"perProvider",84,"warmup",3).toString());
    }
    private void copy(Path source,Path target)throws Exception{
        try(var files=Files.walk(source)){for(Path file:files.filter(Files::isRegularFile).toList()){Path to=target.resolve(source.relativize(file));Files.createDirectories(to.getParent());Files.copy(file,to);}}
    }
    private McpHttpServer server(OfficialMcpAdapter adapter,String token)throws Exception{
        var backend=new RpcHandler.Backend(){
            public JsonObject call(String name,JsonObject args){throw new UnsupportedOperationException();}
            public JsonObject tool(String name){return adapter.tool(name);}
            public JsonObject callTool(String name,JsonObject args)throws Exception{return adapter.call(name,args);}
        };
        return new McpHttpServer(0,token,new RpcHandler(backend),true);
    }
    private <T>T background(Callable<T> action)throws Exception{
        var future=AppExecutorUtil.getAppExecutorService().submit(action);
        try{return PlatformTestUtil.waitForFuture(future,600_000);}finally{if(!future.isDone())future.cancel(true);}
    }
}
