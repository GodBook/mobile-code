#include <jni.h>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

// JDWP transport ABI, from OpenJDK's public jdwpTransport.h, version 1.0.
extern "C" {
typedef jint jdwpTransportError;
struct Env;
struct Callback { void* (*alloc)(jint); void (*free)(void*); };
struct Capabilities { unsigned can_timeout_attach:1; unsigned can_timeout_accept:1; unsigned can_timeout_handshake:1; unsigned reserved:13; };
struct CommandPacket { jint len; jint id; jbyte flags; jbyte cmdSet; jbyte cmd; jbyte* data; };
struct ReplyPacket { jint len; jint id; jbyte flags; jshort errorCode; jbyte* data; };
union Packet { CommandPacket cmd; ReplyPacket reply; };
struct Interface {
    void* reserved1;
    jdwpTransportError (*GetCapabilities)(Env*,Capabilities*);
    jdwpTransportError (*Attach)(Env*,const char*,jlong,jlong);
    jdwpTransportError (*StartListening)(Env*,const char*,char**);
    jdwpTransportError (*StopListening)(Env*);
    jdwpTransportError (*Accept)(Env*,jlong,jlong);
    jboolean (*IsOpen)(Env*);
    jdwpTransportError (*Close)(Env*);
    jdwpTransportError (*ReadPacket)(Env*,Packet*);
    jdwpTransportError (*WritePacket)(Env*,const Packet*);
    jdwpTransportError (*GetLastError)(Env*,char**);
};
struct Env { const Interface* functions; };
static int stream = -1;
static Callback callback;
static bool transfer(int fd, void* ptr, size_t size, bool write) {
    auto* bytes = static_cast<unsigned char*>(ptr);
    while (size) {
        ssize_t n = write ? send(fd, bytes, size, MSG_NOSIGNAL) : read(fd, bytes, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        bytes += n; size -= static_cast<size_t>(n);
    }
    return true;
}
static uint32_t integer(const unsigned char* p) { return (uint32_t(p[0])<<24)|(uint32_t(p[1])<<16)|(uint32_t(p[2])<<8)|p[3]; }
static void put_integer(unsigned char* p, uint32_t v) { p[0]=v>>24; p[1]=v>>16; p[2]=v>>8; p[3]=v; }
static jint capabilities(Env*, Capabilities* c) { memset(c,0,sizeof(*c)); c->can_timeout_handshake=1; return 0; }
static jint attach(Env*, const char* address, jlong, jlong timeout) {
    if (!address) return 103;
    char* end; long fd = strtol(address,&end,10); if (*end || fd < 3 || fd > 65535) return 103;
    stream=static_cast<int>(fd);
    const char handshake[]="JDWP-Handshake";
    if (!transfer(stream,const_cast<char*>(handshake),14,true)) return 202;
    pollfd p{stream,POLLIN,0}; if (poll(&p,1,timeout > 0 ? static_cast<int>(timeout) : 5000) <= 0) return 203;
    char reply[14]; if (!transfer(stream,reply,14,false) || memcmp(reply,handshake,14)) return 202;
    return 0;
}
static jint unsupported(Env*,const char*,char**) { return 204; }
static jint stop(Env*) { return 0; }
static jint accept_stream(Env*,jlong,jlong) { return 204; }
static jboolean is_open(Env*) { return stream >= 0; }
static jint close_stream(Env*) { if(stream>=0) { shutdown(stream,SHUT_RDWR); close(stream); stream=-1; } return 0; }
static jint read_packet(Env*,Packet* packet) {
    unsigned char header[11];
    if (!transfer(stream,header,11,false)) { packet->cmd.len=0; return 0; }
    uint32_t length=integer(header); if(length<11 || length>16*1024*1024) return 202;
    memset(packet,0,sizeof(*packet)); packet->cmd.len=length; packet->cmd.id=integer(header+4); packet->cmd.flags=header[8];
    jbyte** data;
    if(header[8]&0x80) { packet->reply.errorCode=static_cast<jshort>((header[9]<<8)|header[10]); data=&packet->reply.data; }
    else { packet->cmd.cmdSet=header[9]; packet->cmd.cmd=header[10]; data=&packet->cmd.data; }
    if(length>11) { *data=static_cast<jbyte*>(callback.alloc(length-11)); if(!*data) return 110; if(!transfer(stream,*data,length-11,false)) { callback.free(*data); *data=nullptr; return 202; } }
    return 0;
}
static jint write_packet(Env*,const Packet* packet) {
    if(packet->cmd.len<11 || packet->cmd.len>16*1024*1024) return 103;
    unsigned char header[11]; put_integer(header,packet->cmd.len); put_integer(header+4,packet->cmd.id); header[8]=packet->cmd.flags;
    const jbyte* data;
    if(header[8]&0x80) { header[9]=packet->reply.errorCode>>8; header[10]=packet->reply.errorCode; data=packet->reply.data; }
    else { header[9]=packet->cmd.cmdSet; header[10]=packet->cmd.cmd; data=packet->cmd.data; }
    return transfer(stream,header,11,true) && transfer(stream,const_cast<jbyte*>(data),packet->cmd.len-11,true) ? 0 : 202;
}
static jint last_error(Env*,char** value) { const char* error="Private FD JDWP transport failed"; *value=static_cast<char*>(callback.alloc(strlen(error)+1)); if(!*value) return 110; strcpy(*value,error); return 0; }
static const Interface functions={nullptr,capabilities,attach,unsupported,stop,accept_stream,is_open,close_stream,read_packet,write_packet,last_error};
static Env environment{&functions};
JNIEXPORT jint JNICALL jdwpTransport_OnLoad(JavaVM*,Callback* callbacks,jint version,Env** out) {
    if(version!=0x00010000) return JNI_EVERSION;
    callback=*callbacks; *out=&environment; return JNI_OK;
}

JNIEXPORT jint JNICALL Java_dev_mobilecode_runtime_FdConnection_readBytes(JNIEnv* env,jclass,jint fd,jbyteArray bytes,jint offset,jint length) {
    if(offset<0 || length<0 || offset+length>env->GetArrayLength(bytes)) return -1;
    auto* data=env->GetByteArrayElements(bytes,nullptr); ssize_t n;
    do { n=read(fd,data+offset,length); } while(n<0 && errno==EINTR);
    env->ReleaseByteArrayElements(bytes,data,0); return static_cast<jint>(n);
}
JNIEXPORT jboolean JNICALL Java_dev_mobilecode_runtime_FdConnection_writeBytes(JNIEnv* env,jclass,jint fd,jbyteArray bytes) {
    auto* data=env->GetByteArrayElements(bytes,nullptr); bool ok=transfer(fd,data,env->GetArrayLength(bytes),true);
    env->ReleaseByteArrayElements(bytes,data,JNI_ABORT); return ok;
}
JNIEXPORT void JNICALL Java_dev_mobilecode_runtime_FdConnection_closeDescriptor(JNIEnv*,jclass,jint fd) { shutdown(fd,SHUT_RDWR); close(fd); }
}
