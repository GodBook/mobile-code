#include <jni.h>
#include <tree_sitter/api.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <string_view>
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
static const std::unordered_set<std::string_view> keywords={
    "if","else","for","while","do","switch","case","break","continue","return","class","struct","enum","union","typedef","const","static","extern","volatile","sizeof","namespace","using","template","typename","public","private","protected","virtual","override","new","delete","try","catch","throw","throws","import","package","extends","implements","interface","final","abstract","synchronized","void","int","long","short","char","float","double","bool","boolean","unsigned","signed","auto","def","async","await","from","as","in","is","not","and","or","with","lambda","yield","pass","raise","except","finally","assert","global","nonlocal","del","True","False","None","true","false","null"
    ,"concept","requires","constexpr","consteval","constinit","noexcept","nullptr","decltype","explicit","friend","operator","this","super","record","var","instanceof","native","transient","strictfp","elif","match"
};
static const std::unordered_set<std::string_view> qualifiers={
    "const","static","extern","volatile","virtual","override","final","abstract","synchronized","constexpr","consteval","constinit","noexcept","explicit","inline","mutable","thread_local","async","await","native","transient","strictfp"
};
static bool field(TSNode parent,const char* name,TSNode child) {
    return !ts_node_is_null(parent) && ts_node_eq(ts_node_child_by_field_name(parent,name,std::strlen(name)),child);
}
static int classify(TSNode node,std::string_view text) {
    const std::string_view type=ts_node_type(node);
    if(type.find("comment")!=type.npos) return 3;
    if(type=="string_literal" || type=="concatenated_string" || type=="raw_string_literal" || type=="string" || type=="char_literal" || type=="character_literal" || type=="system_lib_string") return 2;
    if(type=="primitive_type" || type=="boolean_type" || type=="integral_type" || type=="floating_point_type") return 1;
    if(type=="type_identifier" || type=="namespace_identifier") return 5;
    if(ts_node_child_count(node)!=0) return 0;
    const auto start=ts_node_start_byte(node),end=ts_node_end_byte(node);
    if(start>=end || end>text.size()) return 0;
    const auto value=text.substr(start,end-start);
    if(type.find("integer")!=type.npos || type.find("float")!=type.npos || type=="number_literal" || value=="true" || value=="false" || value=="null" || value=="True" || value=="False" || value=="None" || value=="nullptr") return 4;
    if(value.front()=='#' || type=="preproc_directive") return 7;
    if(qualifiers.count(value)) return 8;
    if(keywords.count(value)) return 1;
    if(type=="identifier" || type=="field_identifier") {
        const auto parent=ts_node_parent(node);
        const std::string_view context=ts_node_is_null(parent)?"":ts_node_type(parent);
        if((context=="function_declarator" && field(parent,"declarator",node)) ||
           ((context=="function_definition" || context=="method_declaration" || context=="constructor_declaration") && field(parent,"name",node)) ||
           (context=="method_invocation" && field(parent,"name",node)) ||
           ((context=="call_expression" || context=="call") && field(parent,"function",node))) return 6;
        if((context=="class_definition" || context=="class_declaration" || context=="interface_declaration" || context=="enum_declaration" || context=="record_declaration") && field(parent,"name",node)) return 5;
        if((context=="field_expression" && field(parent,"field",node)) || (context=="attribute" && field(parent,"attribute",node)) || (context=="qualified_identifier" && field(parent,"name",node))) {
            const auto outer=ts_node_parent(parent);
            const std::string_view outerType=ts_node_is_null(outer)?"":ts_node_type(outer);
            if((outerType=="call_expression" || outerType=="call") && field(outer,"function",parent)) return 6;
        }
        if(context=="preproc_function_def" && field(parent,"name",node)) return 6;
        return 9;
    }
    if(!ts_node_is_named(node)) return 10;
    return 0;
}
static void collect(TSNode root,const std::string& text,std::vector<jint>& spans) {
    // Cursor traversal avoids a native stack frame/string allocation per AST node.
    auto cursor=ts_tree_cursor_new(root);
    std::vector<char> brackets;
    while(true) {
        const auto node=ts_tree_cursor_current_node(&cursor);
        int style=classify(node,text);
        const auto start=ts_node_start_byte(node),end=ts_node_end_byte(node);
        if(style==10 && end==start+1) {
            const char c=text[start];
            if(c=='(' || c=='[' || c=='{') { style=12+brackets.size()%3; brackets.push_back(c); }
            else if(c==')' || c==']' || c=='}') {
                const char expected=c==')'?'(':c==']'?'[':'{';
                if(!brackets.empty() && brackets.back()==expected) { brackets.pop_back(); style=12+brackets.size()%3; }
            }
        }
        if(style && start<end) { spans.push_back(start); spans.push_back(end); spans.push_back(style); }
        if(!style && ts_tree_cursor_goto_first_child(&cursor)) continue;
        while(!ts_tree_cursor_goto_next_sibling(&cursor)) {
            if(!ts_tree_cursor_goto_parent(&cursor)) { ts_tree_cursor_delete(&cursor); return; }
        }
    }
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_mobilecode_ide_editor_NativeSyntax_create(JNIEnv*,jobject,jint language) {
    auto* doc=new Document{ts_parser_new(),nullptr,{}};
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
