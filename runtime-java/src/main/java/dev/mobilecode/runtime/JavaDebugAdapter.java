package dev.mobilecode.runtime;

import com.google.gson.*;
import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import static dev.mobilecode.runtime.FramedJson.object;

public final class JavaDebugAdapter {
    private final JsonObject config;
    private final FramedJson protocol;
    private final VirtualMachine vm;
    private final Path sourceRoot;
    private final Map<String,Path> sourcePaths=new HashMap<>();
    private final Map<String,List<Point>> breakpoints=new ConcurrentHashMap<>();
    private final Map<Integer,com.sun.jdi.StackFrame> frames=new ConcurrentHashMap<>();
    private final Map<Integer,Object> references=new ConcurrentHashMap<>();
    private final Map<Integer,ThreadReference> threads=new ConcurrentHashMap<>();
    private final AtomicInteger sequence=new AtomicInteger(), identities=new AtomicInteger(1000);
    private final AtomicBoolean ended=new AtomicBoolean();
    private volatile boolean configured,paused,closing;
    private volatile ThreadReference stoppedThread;
    private volatile EventSet startup;
    private record Point(int id,int line) { }
    private record Locals(com.sun.jdi.StackFrame frame) { }
    private record Statics(ReferenceType type) { }

    public JavaDebugAdapter(JsonObject config,FramedJson protocol) throws Exception {
        this.config=config; this.protocol=protocol; sourceRoot=Path.of(config.get("source").getAsString()).toRealPath();
        try(var walk=Files.walk(sourceRoot)) {
            for(Path path:walk.filter(p->p.toString().endsWith(".java")).toList()) {
                Matcher packageName=Pattern.compile("(?m)^\\s*package\\s+([^;\\s]+)\\s*;").matcher(Files.readString(path));
                String logical=(packageName.find()?packageName.group(1).replace('.','/')+"/":"")+path.getFileName();
                sourcePaths.put(logical,path); sourcePaths.putIfAbsent(path.getFileName().toString(),path);
            }
        }
        FdConnection connection=new FdConnection(config.get("jdwpFd").getAsInt(),config.get("transportLibrary").getAsString());
        vm=Bootstrap.virtualMachineManager().createVirtualMachine(connection);
        ClassPrepareRequest prepare=vm.eventRequestManager().createClassPrepareRequest();
        exclusions(prepare); prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL); prepare.enable();
    }
    private static void exclusions(ClassPrepareRequest request) {
        for(String pattern:List.of("java.*","javax.*","jdk.*","sun.*","com.sun.*","dev.mobilecode.runtime.*")) request.addClassExclusionFilter(pattern);
    }
    private void send(JsonObject message) throws IOException { message.addProperty("seq",sequence.incrementAndGet()); protocol.send(message); }
    private void event(String name,JsonObject body) {
        try { send(object("type","event","event",name,"body",body)); } catch(IOException ignored) { closing=true; }
    }
    private void response(JsonObject request,JsonObject body,Throwable error) throws IOException {
        JsonObject result=object("type","response","request_seq",request.get("seq").getAsInt(),"command",request.get("command").getAsString(),"success",error==null,"body",body);
        if(error!=null) result.addProperty("message",error.getMessage()==null?error.toString():error.getMessage()); send(result);
    }
    private int reference(Object value) { if(value==null) return 0; int id=identities.incrementAndGet(); references.put(id,value); return id; }
    private int threadId(ThreadReference thread) {
        for(var item:threads.entrySet()) if(item.getValue().uniqueID()==thread.uniqueID()) return item.getKey();
        int id=identities.incrementAndGet(); threads.put(id,thread); return id;
    }
    private Path source(ReferenceType type) {
        try { for(String path:type.sourcePaths(null)) { Path match=sourcePaths.get(path.replace('\\','/')); if(match!=null) return match; } return sourcePaths.get(type.sourceName()); }
        catch(AbsentInformationException ignored) { return null; }
    }
    private void install(ReferenceType type,boolean notify) {
        Path path=source(type); if(path==null) return;
        List<Point> points=breakpoints.get(path.toString()); if(points==null) return;
        try {
            List<Location> locations=type.allLineLocations();
            for(Point point:points) {
                Location location=locations.stream().filter(l->l.lineNumber()>=point.line).min(Comparator.comparingInt(Location::lineNumber)).orElse(null);
                if(location==null) continue;
                boolean duplicate=vm.eventRequestManager().breakpointRequests().stream().anyMatch(r->Objects.equals(r.getProperty("pointId"),point.id) && r.location().equals(location));
                if(duplicate) continue;
                BreakpointRequest request=vm.eventRequestManager().createBreakpointRequest(location);
                request.putProperty("path",path.toString()); request.putProperty("pointId",point.id); request.setSuspendPolicy(EventRequest.SUSPEND_ALL); request.enable();
                if(notify) event("breakpoint",object("reason","changed","breakpoint",object("id",point.id,"verified",true,"line",location.lineNumber(),"source",object("path",path.toString()))));
            }
        } catch(AbsentInformationException | VMDisconnectedException ignored) { }
    }
    private void stopped(ThreadReference thread,String reason) {
        stoppedThread=thread; paused=true; frames.clear(); references.clear();
        event("stopped",object("reason",reason,"threadId",threadId(thread),"allThreadsStopped",true));
    }
    private void events() {
        try {
            while(!closing) {
                EventSet set=vm.eventQueue().remove(); boolean hold=false;
                for(Event value:set) {
                    if(value instanceof VMStartEvent) { if(!configured) { startup=set; hold=true; } }
                    else if(value instanceof ClassPrepareEvent prepared) install(prepared.referenceType(),true);
                    else if(value instanceof BreakpointEvent hit) { hold=true; stopped(hit.thread(),"breakpoint"); }
                    else if(value instanceof StepEvent step) {
                        vm.eventRequestManager().deleteEventRequest(step.request()); hold=true; stopped(step.thread(),"step");
                    } else if(value instanceof VMDeathEvent || value instanceof VMDisconnectEvent) { finish(); return; }
                }
                if(!hold) set.resume();
            }
        } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
        catch(VMDisconnectedException ignored) { finish(); }
    }
    private void finish() { if(ended.compareAndSet(false,true)) event("terminated",object()); closing=true; }
    private String display(Value value) {
        if(value==null) return "null";
        if(value instanceof StringReference string) return '"'+string.value().substring(0,Math.min(1024,string.value().length()))+'"';
        if(value instanceof ArrayReference array) return array.referenceType().name()+" ("+array.length()+")";
        if(value instanceof ObjectReference object) return object.referenceType().name()+"@"+Long.toHexString(object.uniqueID());
        return value.toString();
    }
    private JsonObject variable(String name,Value value) {
        return object("name",name,"value",display(value),"type",value==null?"null":value.type().name(),"variablesReference",value instanceof ObjectReference?reference(value):0);
    }
    private Value evaluate(String expression,com.sun.jdi.StackFrame frame) throws Exception {
        Matcher tokens=Pattern.compile("([\\p{L}_$][\\p{L}\\p{N}_$]*)|\\.([\\p{L}_$][\\p{L}\\p{N}_$]*)|\\[(\\d+)\\]").matcher(expression);
        int end=0; Value value=null; boolean first=true;
        while(tokens.find()) {
            if(tokens.start()!=end) throw new IllegalArgumentException("观察项只支持变量、字段和数组下标"); end=tokens.end();
            if(first) {
                if(tokens.group(1)==null) throw new IllegalArgumentException("观察项必须以变量开头");
                String name=tokens.group(1); LocalVariable local=frame.visibleVariableByName(name);
                if(name.equals("this")) value=frame.thisObject();
                else if(local!=null) value=frame.getValue(local);
                else {
                    Field field=frame.location().declaringType().fieldByName(name);
                    if(field==null) throw new IllegalArgumentException("没有变量 "+name);
                    value=field.isStatic()?field.declaringType().getValue(field):frame.thisObject().getValue(field);
                }
                first=false;
            } else if(tokens.group(2)!=null && value instanceof ObjectReference object) {
                Field field=object.referenceType().fieldByName(tokens.group(2)); if(field==null) throw new IllegalArgumentException("没有该字段"); value=object.getValue(field);
            } else if(tokens.group(3)!=null && value instanceof ArrayReference array) value=array.getValue(Integer.parseInt(tokens.group(3)));
            else throw new IllegalArgumentException("不支持该观察路径");
        }
        if(first || end!=expression.length()) throw new IllegalArgumentException("观察项只支持变量、字段和数组下标");
        return value;
    }
    private JsonObject request(String command,JsonObject args) throws Exception {
        switch(command) {
            case "initialize": return object("supportsConfigurationDoneRequest",true,"supportsEvaluateForHovers",true,"supportsTerminateRequest",true,"supportsConditionalBreakpoints",false,"supportsSetVariable",false);
            case "launch": return object();
            case "configurationDone": configured=true; if(startup!=null) { startup.resume(); startup=null; } return object();
            case "setExceptionBreakpoints": return object("breakpoints",List.of());
            case "setBreakpoints": {
                Path path=Path.of(args.getAsJsonObject("source").get("path").getAsString()).toRealPath();
                if(!path.startsWith(sourceRoot)) throw new IllegalArgumentException("断点不在项目源码中");
                vm.eventRequestManager().deleteEventRequests(vm.eventRequestManager().breakpointRequests().stream().filter(r->path.toString().equals(r.getProperty("path"))).toList());
                List<Point> points=new ArrayList<>(); for(JsonElement item:args.getAsJsonArray("breakpoints")) points.add(new Point(identities.incrementAndGet(),item.getAsJsonObject().get("line").getAsInt()));
                breakpoints.put(path.toString(),points); for(ReferenceType type:vm.allClasses()) if(type.isPrepared()) install(type,false);
                List<JsonObject> result=new ArrayList<>();
                for(Point point:points) {
                    BreakpointRequest bound=vm.eventRequestManager().breakpointRequests().stream().filter(r->Objects.equals(r.getProperty("pointId"),point.id)).findFirst().orElse(null);
                    result.add(object("id",point.id,"verified",bound!=null,"line",bound==null?point.line:bound.location().lineNumber(),"source",object("path",path.toString())));
                }
                return object("breakpoints",result);
            }
            case "threads": return object("threads",vm.allThreads().stream().map(t->object("id",threadId(t),"name",t.name())).toList());
            case "stackTrace": {
                ThreadReference thread=threads.get(args.get("threadId").getAsInt()); if(thread==null) thread=stoppedThread;
                List<JsonObject> result=new ArrayList<>(); frames.clear();
                for(com.sun.jdi.StackFrame frame:thread.frames()) {
                    Path path=source(frame.location().declaringType()); if(path==null) continue;
                    int id=identities.incrementAndGet(); frames.put(id,frame);
                    result.add(object("id",id,"name",frame.location().declaringType().name()+"."+frame.location().method().name(),"line",Math.max(1,frame.location().lineNumber()),"column",1,"source",object("path",path.toString(),"name",path.getFileName().toString())));
                    if(result.size()>=100) break;
                }
                return object("stackFrames",result,"totalFrames",result.size());
            }
            case "scopes": {
                com.sun.jdi.StackFrame frame=frames.get(args.get("frameId").getAsInt());
                List<JsonObject> scopes=new ArrayList<>(); scopes.add(object("name","局部变量","variablesReference",reference(new Locals(frame)),"expensive",false));
                if(frame.thisObject()!=null) scopes.add(object("name","this","variablesReference",reference(frame.thisObject()),"expensive",false));
                scopes.add(object("name","静态字段","variablesReference",reference(new Statics(frame.location().declaringType())),"expensive",false));
                return object("scopes",scopes);
            }
            case "variables": {
                Object target=references.get(args.get("variablesReference").getAsInt()); List<JsonObject> values=new ArrayList<>();
                if(target instanceof Locals locals) {
                    try { for(LocalVariable local:locals.frame.visibleVariables()) values.add(variable(local.name(),locals.frame.getValue(local))); }
                    catch(AbsentInformationException ignored) { }
                } else if(target instanceof ArrayReference array) { for(int i=0;i<Math.min(array.length(),100);++i) values.add(variable("["+i+"]",array.getValue(i))); }
                else if(target instanceof ObjectReference object) {
                    for(Field field:object.referenceType().allFields()) { if(!field.isStatic()) values.add(variable(field.name(),object.getValue(field))); if(values.size()>=100) break; }
                } else if(target instanceof Statics statics) {
                    for(Field field:statics.type.allFields()) if(field.isStatic()) { values.add(variable(field.name(),field.declaringType().getValue(field))); if(values.size()>=100) break; }
                }
                return object("variables",values);
            }
            case "evaluate": {
                com.sun.jdi.StackFrame frame=frames.get(args.get("frameId").getAsInt()); Value value=evaluate(args.get("expression").getAsString(),frame);
                return object("result",display(value),"type",value==null?"null":value.type().name(),"variablesReference",value instanceof ObjectReference?reference(value):0);
            }
            case "pause": {
                vm.suspend(); ThreadReference selected=null;
                for(ThreadReference thread:vm.allThreads()) try { if(thread.frames().stream().anyMatch(f->source(f.location().declaringType())!=null)) { selected=thread; break; } } catch(IncompatibleThreadStateException ignored) { }
                if(selected==null) { vm.resume(); throw new IllegalStateException("暂无可暂停的用户线程"); }
                stopped(selected,"pause"); return object();
            }
            case "continue", "next", "stepIn", "stepOut": {
                if(!paused || stoppedThread==null) throw new IllegalStateException("程序尚未暂停");
                vm.eventRequestManager().deleteEventRequests(new ArrayList<>(vm.eventRequestManager().stepRequests()));
                if(!command.equals("continue")) {
                    int depth=command.equals("next")?StepRequest.STEP_OVER:command.equals("stepIn")?StepRequest.STEP_INTO:StepRequest.STEP_OUT;
                    StepRequest step=vm.eventRequestManager().createStepRequest(stoppedThread,StepRequest.STEP_LINE,depth);
                    for(String pattern:List.of("java.*","javax.*","jdk.*","sun.*","com.sun.*","dev.mobilecode.runtime.*")) step.addClassExclusionFilter(pattern);
                    step.addCountFilter(1); step.setSuspendPolicy(EventRequest.SUSPEND_ALL); step.enable();
                }
                paused=false; frames.clear(); references.clear();
                event("continued",object("threadId",threadId(stoppedThread),"allThreadsContinued",true));
                vm.resume();
                return object("allThreadsContinued",true);
            }
            case "terminate", "disconnect": closing=true; try { vm.exit(0); } catch(VMDisconnectedException ignored) { } finish(); return object();
            default: throw new IllegalArgumentException("不支持的调试请求: "+command);
        }
    }
    public void serve() throws IOException {
        Thread events=new Thread(this::events,"JDI events"); events.setDaemon(true); events.start();
        try {
            while(!closing) {
                JsonObject message=protocol.read(); if(message==null) break;
                String command=message.get("command").getAsString(); JsonObject args=message.has("arguments")?message.getAsJsonObject("arguments"):object();
                try {
                    JsonObject body=request(command,args); response(message,body,null);
                    if(command.equals("launch")) event("initialized",object());
                } catch(Exception error) { response(message,object(),error); }
            }
        } finally { closing=true; try { vm.dispose(); } catch(VMDisconnectedException ignored) { } }
    }
}
