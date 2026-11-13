/*
 * JNI adapter for Arm's Stable Audio Open Small ExecuTorch sample.
 * Upstream source: Arm-Examples/ML-examples, Apache-2.0.
 */
#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include <vector>
#include <unistd.h>

#include "audiogen_main.cpp"

#define LOG_TAG "MusicaIA-AudioGen"

extern "C" JNIEXPORT jstring JNICALL
Java_com_musicaia_modulos_AudioGenNative_generateNative(
    JNIEnv* env,
    jobject /* instance */,
    jstring jModelsPath,
    jstring jPrompt,
    jstring jOutputPath,
    jint jSeed
) {
    const char* modelsChars = env->GetStringUTFChars(jModelsPath, nullptr);
    const char* promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    const char* outputChars = env->GetStringUTFChars(jOutputPath, nullptr);
    if (modelsChars == nullptr || promptChars == nullptr || outputChars == nullptr) {
        if (modelsChars) env->ReleaseStringUTFChars(jModelsPath, modelsChars);
        if (promptChars) env->ReleaseStringUTFChars(jPrompt, promptChars);
        if (outputChars) env->ReleaseStringUTFChars(jOutputPath, outputChars);
        return nullptr;
    }

    const std::string modelsPath(modelsChars);
    const std::string prompt(promptChars);
    const std::string outputPath(outputChars);
    env->ReleaseStringUTFChars(jModelsPath, modelsChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);
    env->ReleaseStringUTFChars(jOutputPath, outputChars);

    try {
        std::vector<std::string> args = {
            "audiogen",
            "-m", modelsPath,
            "-p", prompt,
            "-t", "4",
            "-s", std::to_string(static_cast<int>(jSeed)),
            "-n", "8",
            "-l", "11",
            "-o", outputPath
        };
        std::vector<char*> argv;
        argv.reserve(args.size() + 1);
        for (auto& arg : args) argv.push_back(arg.data());
        argv.push_back(nullptr);

        optind = 1;
        opterr = 0;
        const int result = audiogen_cli_main(
            static_cast<int32_t>(args.size()),
            argv.data()
        );
        if (result != EXIT_SUCCESS) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "ExecuTorch generator returned %d", result);
            return nullptr;
        }
        return env->NewStringUTF(outputPath.c_str());
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Generation failed: %s", error.what());
        return nullptr;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Generation failed with an unknown native exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_musicaia_modulos_AudioGenNative_releaseNative(
    JNIEnv* /* env */,
    jobject /* instance */
) {
    try {
        audiogen_release_models();
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to release Stable Audio model memory");
    }
}
