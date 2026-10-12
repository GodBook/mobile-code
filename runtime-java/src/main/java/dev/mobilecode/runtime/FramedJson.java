package dev.mobilecode.runtime;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class FramedJson {
    private final InputStream input;
    private final OutputStream output;
    public FramedJson(InputStream input, OutputStream output) { this.input=input; this.output=output; }
    public JsonObject read() throws IOException {
        int length=-1;
        while(true) {
            ByteArrayOutputStream header=new ByteArrayOutputStream();
            int c;
            while((c=input.read())!=-1 && c!='\n') { header.write(c); if(header.size()>8192) throw new IOException("Protocol header too long"); }
            if(c==-1) return null;
            String line=header.toString(StandardCharsets.US_ASCII).trim();
            if(line.isEmpty()) break;
            int colon=line.indexOf(':');
            if(colon>0 && line.substring(0,colon).equalsIgnoreCase("Content-Length")) length=Integer.parseInt(line.substring(colon+1).trim());
        }
        if(length<0 || length>16*1024*1024) throw new IOException("Invalid Content-Length");
        byte[] bytes=input.readNBytes(length);
        if(bytes.length!=length) throw new EOFException("Incomplete protocol message");
        return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
    }
    public synchronized void send(JsonObject message) throws IOException {
        byte[] bytes=message.toString().getBytes(StandardCharsets.UTF_8);
        output.write(("Content-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(bytes); output.flush();
    }
    public static JsonObject object(Object... values) {
        JsonObject object=new JsonObject(); Gson gson=new Gson();
        for(int i=0;i<values.length;i+=2) object.add((String)values[i],gson.toJsonTree(values[i+1]));
        return object;
    }
}
