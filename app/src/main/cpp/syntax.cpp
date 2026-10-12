#include <jni.h>
#include <tree_sitter/api.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <unordered_set>
#include <vector>
#include "unicode.h"

extern "C" const TSLanguage* tree_sitter_c();
extern "C" const TSLanguage* tree_sitter_cpp();
extern "C" const TSLanguage* tree_sitter_python();
extern "C" const TSLanguage* tree_sitter_java();
struct Document { TSParser* parser; TSTree* tree=nullptr; std::string text; };
static TSPoint point(const std::string& text,size_t end) {
    TSPoint p{0,0}; for(size_t i=0;i<end;++i) { if(text[i]=='\n') { ++p.row; p.column=0; } else ++p.column; } return p;
}
static const std::unordered_set<std::string> keywords={
    "if","else","for","while","do","switch","case","break","continue","return","class","struct","enum","union","typedef","const","static","extern","volatile","sizeof","namespace","using","template","typename","public","private","protected","virtual","override","new","delete","try","catch","throw","throws","import","package","extends","implements","interface","final","abstract","synchronized","void","int","long","short","char","float","double","bool","boolean","unsigned","signed","auto","def","async","await","from","as","in","is","not","and","or","with","lambda","yield","pass","raise","except","finally","assert","global","nonlocal","del","True","False","None","true","false","null"
};
static void collect(TSNode node,const std::string& text,std::vector<jint>& spans) {
    const char* type=ts_node_type(node); int style=0;
    std::string t(type);
    if(t.find("comment")!=std::string::npos) style=3;
    else if(t.find("string")!=std::string::npos || t=="char_literal" || t=="character_literal") style=2;
    else if(t.find("integer")!=std::string::npos || t.find("float")!=std::string::npos || t=="number_literal" || t=="decimal_integer_literal" || t=="hex_integer_literal") style=4;
    else if(t=="primitive_type" || t=="type_identifier" || t=="boolean_type" || t=="integral_type" || t=="floating_point_type") style=5;
    else if(ts_node_child_count(node)==0) {
        uint32_t start=ts_node_start_byte(node),end=ts_node_end_byte(node);
        if(end<=text.size() && end-start<32 && keywords.count(text.substr(start,end-start))) style=1;
    }
    if(style) {
        spans.push_back(ts_node_start_byte(node)); spans.push_back(ts_node_end_byte(node)); spans.push_back(style);
        return;
    }
    for(uint32_t i=0;i<ts_node_child_count(node);++i) collect(ts_node_child(node,i),text,spans);
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_mobilecode_ide_editor_NativeSyntax_create(JNIEnv*,jobject,jint language) {
    auto* doc=new Document{ts_parser_new()};
    const TSLanguage* grammar=language==0?tree_sitter_c():language==1?tree_sitter_cpp():language==2?tree_sitter_python():tree_sitter_java();
    if(!ts_parser_set_language(doc->parser,grammar)) { ts_parser_delete(doc->parser); delete doc; return 0; }
    return reinterpret_cast<jlong>(doc);
}
extern "C" JNIEXPORT jintArray JNICALL Java_dev_mobilecode_ide_editor_NativeSyntax_parse(JNIEnv* env,jobject,jlong handle,jstring input) {
    auto* doc=reinterpret_cast<Document*>(handle); auto text=utf8(env,input);
    if(!doc) return env->NewIntArray(0);
    if(doc->tree) {
        size_t prefix=0; while(prefix<std::min(text.size(),doc->text.size()) && text[prefix]==doc->text[prefix]) ++prefix;
        while(prefix>0 && prefix<text.size() && (static_cast<unsigned char>(text[prefix])&0xc0)==0x80) --prefix;
        size_t suffix=0; while(suffix<std::min(text.size()-prefix,doc->text.size()-prefix) && text[text.size()-suffix-1]==doc->text[doc->text.size()-suffix-1]) ++suffix;
        while(suffix>0 && text.size()>suffix && (static_cast<unsigned char>(text[text.size()-suffix])&0xc0)==0x80) --suffix;
        TSInputEdit edit{static_cast<uint32_t>(prefix),static_cast<uint32_t>(doc->text.size()-suffix),static_cast<uint32_t>(text.size()-suffix),point(doc->text,prefix),point(doc->text,doc->text.size()-suffix),point(text,text.size()-suffix)};
        ts_tree_edit(doc->tree,&edit);
    }
    auto* tree=ts_parser_parse_string(doc->parser,doc->tree,text.data(),static_cast<uint32_t>(text.size()));
    if(doc->tree) ts_tree_delete(doc->tree); doc->tree=tree; doc->text=std::move(text);
    std::vector<jint> spans; if(tree) collect(ts_tree_root_node(tree),doc->text,spans);
    auto array=env->NewIntArray(spans.size()); env->SetIntArrayRegion(array,0,spans.size(),spans.data()); return array;
}
extern "C" JNIEXPORT void JNICALL Java_dev_mobilecode_ide_editor_NativeSyntax_destroy(JNIEnv*,jobject,jlong handle) {
    auto* doc=reinterpret_cast<Document*>(handle); if(!doc) return;
    if(doc->tree) ts_tree_delete(doc->tree); ts_parser_delete(doc->parser); delete doc;
}
