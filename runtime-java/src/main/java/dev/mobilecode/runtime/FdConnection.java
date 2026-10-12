package dev.mobilecode.runtime;

import com.sun.jdi.connect.spi.Connection;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class FdConnection extends Connection {
    private final int fd;
    private volatile boolean open=true;
    private static native int readBytes(int fd,byte[] bytes,int offset,int length);
    private static native boolean writeBytes(int fd,byte[] bytes);
    private static native void closeDescriptor(int fd);
    public FdConnection(int fd,String library) throws IOException {
        System.load(library); this.fd=fd;
        byte[] handshake="JDWP-Handshake".getBytes(StandardCharsets.US_ASCII);
        if(!writeBytes(fd,handshake)) throw new IOException("JDWP handshake write failed");
        byte[] received=new byte[14]; readFully(received,0,14);
        if(!Arrays.equals(handshake,received)) throw new IOException("Invalid JDWP handshake");
    }
    private void readFully(byte[] bytes,int offset,int length) throws IOException {
        while(length>0) { int n=readBytes(fd,bytes,offset,length); if(n<=0) throw new EOFException("JDWP disconnected"); offset+=n; length-=n; }
    }
    @Override public byte[] readPacket() throws IOException {
        byte[] length=new byte[4]; readFully(length,0,4); int size=ByteBuffer.wrap(length).getInt();
        if(size<11 || size>16*1024*1024) throw new IOException("Invalid JDWP packet");
        byte[] packet=new byte[size]; System.arraycopy(length,0,packet,0,4); readFully(packet,4,size-4); return packet;
    }
    @Override public synchronized void writePacket(byte[] bytes) throws IOException {
        if(!open || bytes.length<11 || !writeBytes(fd,bytes)) throw new IOException("JDWP write failed");
    }
    @Override public boolean isOpen() { return open; }
    @Override public void close() { if(open) { open=false; closeDescriptor(fd); } }
}
