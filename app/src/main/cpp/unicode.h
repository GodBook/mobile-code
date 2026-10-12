#pragma once
#include <jni.h>
#include <string>
#include <vector>

inline std::string utf8(JNIEnv* env, jstring input) {
    if (!input) return {};
    const jchar* text = env->GetStringChars(input, nullptr);
    const jsize length = env->GetStringLength(input);
    std::string out;
    for (jsize i = 0; i < length; ++i) {
        unsigned cp = text[i];
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < length && text[i + 1] >= 0xdc00 && text[i + 1] <= 0xdfff)
            cp = 0x10000 + ((cp - 0xd800) << 10) + (text[++i] - 0xdc00);
        if (cp < 0x80) out += static_cast<char>(cp);
        else if (cp < 0x800) { out += static_cast<char>(0xc0 | (cp >> 6)); out += static_cast<char>(0x80 | (cp & 63)); }
        else if (cp < 0x10000) { out += static_cast<char>(0xe0 | (cp >> 12)); out += static_cast<char>(0x80 | ((cp >> 6) & 63)); out += static_cast<char>(0x80 | (cp & 63)); }
        else { out += static_cast<char>(0xf0 | (cp >> 18)); out += static_cast<char>(0x80 | ((cp >> 12) & 63)); out += static_cast<char>(0x80 | ((cp >> 6) & 63)); out += static_cast<char>(0x80 | (cp & 63)); }
    }
    env->ReleaseStringChars(input, text);
    return out;
}

inline std::vector<unsigned> codepoints(const std::string& text) {
    std::vector<unsigned> out;
    for (size_t i = 0; i < text.size();) {
        unsigned char b = text[i++];
        unsigned cp = b; int n = 0;
        if ((b & 0xe0) == 0xc0) { cp = b & 31; n = 1; }
        else if ((b & 0xf0) == 0xe0) { cp = b & 15; n = 2; }
        else if ((b & 0xf8) == 0xf0) { cp = b & 7; n = 3; }
        while (n-- && i < text.size()) cp = (cp << 6) | (static_cast<unsigned char>(text[i++]) & 63);
        out.push_back(cp);
    }
    return out;
}

inline jstring java_string(JNIEnv* env, const std::string& text) {
    std::vector<jchar> chars;
    for (auto cp : codepoints(text)) {
        if (cp <= 0xffff) chars.push_back(static_cast<jchar>(cp));
        else { cp -= 0x10000; chars.push_back(static_cast<jchar>(0xd800 | (cp >> 10))); chars.push_back(static_cast<jchar>(0xdc00 | (cp & 1023))); }
    }
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}
