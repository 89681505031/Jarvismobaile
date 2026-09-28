#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_phone_JarvisNativeLanguageModel_nativeRuntimeAvailable(
        JNIEnv *, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_phone_JarvisNativeLanguageModel_nativeLoadModel(
        JNIEnv *, jobject, jstring) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvis_phone_JarvisNativeLanguageModel_nativeGenerate(
        JNIEnv *, jobject, jstring, jint) {
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_phone_JarvisNativeLanguageModel_nativeUnloadModel(
        JNIEnv *, jobject) {
}
