package dev.mobilecode.runtime;

import com.google.gson.*;
import javax.tools.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static dev.mobilecode.runtime.FramedJson.object;

public final class Main {
    public static void main(String[] args) throws Exception {
        if(args.length<2) throw new IllegalArgumentException("Expected mode and JSON config");
        JsonObject config=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        switch(args[0]) {
            case "compile" -> System.exit(compile(config));
            case "debug-adapter" -> new JavaDebugAdapter(config,new FramedJson(System.in,System.out)).serve();
            case "run" -> run(config);
            default -> throw new IllegalArgumentException("Unknown Java runtime mode");
        }
    }
    public static int compile(JsonObject config) throws IOException {
        Path source=Path.of(config.get("source").getAsString()), output=Path.of(config.get("build").getAsString());
        Files.createDirectories(output);
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null) throw new IllegalStateException("Bundled JDK compiler is missing");
        List<Path> files;
        Set<Path> selected = new TreeSet<>();
        JsonArray roots = config.getAsJsonArray("sourceRoots");
        if(roots==null) roots = new JsonArray();
        if(roots.isEmpty()) roots.add(".");
        for(JsonElement relative: roots) {
            Path root = source.resolve(relative.getAsString()).normalize();
            if(!root.startsWith(source)) throw new IllegalArgumentException("Source root escapes project");
            if(Files.isDirectory(root)) try(Stream<Path> walk=Files.walk(root)) {
                walk.filter(p->Files.isRegularFile(p) && p.toString().endsWith(".java")).forEach(selected::add);
            }
        }
        files = List.copyOf(selected);
        if(files.isEmpty()) throw new IllegalArgumentException("No Java source files");
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        boolean success;
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)) {
            List<String> options=List.of("--release","17","-g","-encoding","UTF-8","-d",output.toString());
            success=compiler.getTask(new PrintWriter(System.err,true),manager,diagnostics,options,null,manager.getJavaFileObjectsFromPaths(files)).call();
        }
        for(Diagnostic<? extends JavaFileObject> diagnostic:diagnostics.getDiagnostics()) {
            int line=(int)Math.max(0,diagnostic.getLineNumber()-1), column=(int)Math.max(0,diagnostic.getColumnNumber()-1);
            JsonObject range=object("start",object("line",line,"character",column),"end",object("line",line,"character",column+1));
            System.err.println("MC_DIAGNOSTIC:"+object("path",diagnostic.getSource()==null?"":Path.of(diagnostic.getSource().toUri()).toString(),"range",range,"severity",diagnostic.getKind()==Diagnostic.Kind.ERROR?1:2,"source","javac","message",diagnostic.getMessage(Locale.ROOT)));
        }
        if(success) {
            List<String> entries=new ArrayList<>();
            try(URLClassLoader loader=new URLClassLoader(new URL[]{output.toUri().toURL()},ClassLoader.getPlatformClassLoader()); Stream<Path> walk=Files.walk(output)) {
                for(Path file:walk.filter(p->p.toString().endsWith(".class") && !p.getFileName().toString().contains("$")).toList()) {
                    String name=output.relativize(file).toString().replace(File.separatorChar,'.').replaceAll("\\.class$","");
                    try { Method main=Class.forName(name,false,loader).getMethod("main",String[].class); if(Modifier.isStatic(main.getModifiers()) && main.getReturnType()==void.class) entries.add(name); }
                    catch(ReflectiveOperationException | LinkageError ignored) { }
                }
            }
            System.out.println("MC_ENTRIES:"+new Gson().toJson(entries));
        }
        return success?0:1;
    }
    private static void run(JsonObject config) throws Exception {
        String entry=config.get("entry").getAsString();
        String[] args=new Gson().fromJson(config.get("arguments"),String[].class);
        try { Class.forName(entry).getMethod("main",String[].class).invoke(null,(Object)(args==null?new String[0]:args)); }
        catch(InvocationTargetException error) { Throwable cause=error.getCause(); cause.printStackTrace(); System.exit(1); }
    }
}
