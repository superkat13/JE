#pragma once
// Host-only string adapter. Production inference is real; Android/JVM marshalling is not tested.
#include <string>
using jlong = long long;
using jint = int;
using jsize = int;
using jfloat = float;
using jboolean = unsigned char;
using jbyte = signed char;
using jobject = std::string*;
using jstring = jobject;
using jclass = jobject;
using jbyteArray = jobject;
using jmethodID = void*;
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
struct JNIEnv {
 const char* GetStringUTFChars(jstring s, void*) { return s->c_str(); }
 void ReleaseStringUTFChars(jstring, const char*) {}
 jstring NewStringUTF(const char* s) { return new std::string(s); }
 jbyteArray NewByteArray(jsize n) { return new std::string(n, '\0'); }
 void SetByteArrayRegion(jbyteArray s, jsize start, jsize n, const jbyte* data) { s->replace(start,n,reinterpret_cast<const char*>(data),n); }
 jclass FindClass(const char* s) { return NewStringUTF(s); }
 jmethodID GetMethodID(jclass, const char*, const char*) { return nullptr; }
 jobject NewObject(jclass, jmethodID, jbyteArray bytes, jstring) { return new std::string(*bytes); }
 void DeleteLocalRef(jobject s) { delete s; }
};
