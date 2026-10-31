#include "core.hh"
#include <jni.h>
#include <stdexcept>
namespace {
struct JavaFailure {};
std::string bytes(JNIEnv* env, jbyteArray arr) {
    if (!arr) throw std::runtime_error("缺少参数");
    std::string s(env->GetArrayLength(arr), '\0');
    env->GetByteArrayRegion(arr, 0, s.size(), reinterpret_cast<jbyte*>(s.data()));
    if (env->ExceptionCheck()) throw JavaFailure();
    if (s.find('\0') != std::string::npos) throw std::runtime_error("参数包含无效字符");
    return s;
}
void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    std::string text(message);
    auto data = env->NewByteArray(text.size());
    if (!data) return;
    env->SetByteArrayRegion(data, 0, text.size(), reinterpret_cast<const jbyte*>(text.data()));
    auto strClass = env->FindClass("java/lang/String");
    auto ctor = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    auto encoding = env->NewStringUTF("UTF-8");
    auto str = env->NewObject(strClass, ctor, data, encoding);
    if (env->ExceptionCheck()) return;
    auto cls = env->FindClass("java/io/IOException");
    auto error = env->NewObject(cls, env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V"), str);
    if (error) env->Throw(static_cast<jthrowable>(error));
}
}
extern "C" JNIEXPORT jint JNICALL
Java_cn_paperslice_app_PdfEngine_countNative(JNIEnv* env, jobject, jbyteArray path, jbyteArray pass, jboolean repair) {
    try { return page_count(bytes(env, path), bytes(env, pass), repair); }
    catch (JavaFailure const&) { return 0; }
    catch (std::exception const& e) { fail(env, e.what()); return 0; }
    catch (...) { fail(env, "原生引擎读取失败"); return 0; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_cn_paperslice_app_PdfEngine_extractNative(JNIEnv* env, jobject, jbyteArray path, jbyteArray pass,
    jbyteArray selection, jbyteArray output, jint expected, jboolean repair, jobject callback) {
    try {
        auto cls = env->GetObjectClass(callback);
        auto method = env->GetMethodID(cls, "update", "(I)V");
        if (env->ExceptionCheck() || !method) throw JavaFailure();
        return extract_pages(bytes(env, path), bytes(env, pass), bytes(env, selection), bytes(env, output),
            expected, repair, [=](int percent) {
                env->CallVoidMethod(callback, method, percent);
                if (env->ExceptionCheck()) throw JavaFailure();
            });
    } catch (JavaFailure const&) { return false; }
    catch (std::exception const& e) { fail(env, e.what()); return false; }
    catch (...) { fail(env, "原生引擎提取失败"); return false; }
}
