#include <jni.h>
#include <lame.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <string>
#include <vector>

namespace {

struct WavInfo {
    std::streamoff dataOffset = 0;
    uint32_t dataBytes = 0;
    uint32_t sampleRate = 0;
    uint16_t channels = 0;
    uint16_t format = 0;
    uint16_t bitsPerSample = 0;
};

uint16_t read16le(const unsigned char* p) {
    return static_cast<uint16_t>(p[0]) |
           (static_cast<uint16_t>(p[1]) << 8);
}

uint32_t read32le(const unsigned char* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

bool inspectWav(std::ifstream& input, WavInfo& info) {
    char riff[4] = {};
    char wave[4] = {};
    uint32_t riffSize = 0;
    input.read(riff, 4);
    input.read(reinterpret_cast<char*>(&riffSize), sizeof(riffSize));
    input.read(wave, 4);
    if (!input || std::memcmp(riff, "RIFF", 4) != 0 ||
        std::memcmp(wave, "WAVE", 4) != 0) return false;

    bool haveFormat = false;
    bool haveData = false;
    while (input && !(haveFormat && haveData)) {
        char chunkId[4] = {};
        uint32_t chunkSize = 0;
        input.read(chunkId, 4);
        if (!input) break;
        input.read(reinterpret_cast<char*>(&chunkSize), sizeof(chunkSize));
        if (!input) return false;
        const std::streamoff chunkStart = static_cast<std::streamoff>(input.tellg());

        if (std::memcmp(chunkId, "fmt ", 4) == 0) {
            if (chunkSize < 16) return false;
            unsigned char fmt[16] = {};
            input.read(reinterpret_cast<char*>(fmt), sizeof(fmt));
            if (!input) return false;
            info.format = read16le(fmt);
            info.channels = read16le(fmt + 2);
            info.sampleRate = read32le(fmt + 4);
            info.bitsPerSample = read16le(fmt + 14);
            haveFormat = true;
        } else if (std::memcmp(chunkId, "data", 4) == 0) {
            info.dataOffset = chunkStart;
            info.dataBytes = chunkSize;
            haveData = true;
        }

        const std::streamoff next = chunkStart +
            static_cast<std::streamoff>(chunkSize) +
            static_cast<std::streamoff>(chunkSize & 1U);
        input.clear();
        input.seekg(next, std::ios::beg);
    }

    input.clear();
    input.seekg(0, std::ios::end);
    const std::streamoff fileSize = static_cast<std::streamoff>(input.tellg());
    if (!haveFormat || !haveData || info.dataOffset < 12 ||
        info.dataOffset + static_cast<std::streamoff>(info.dataBytes) > fileSize) return false;

    if (info.format != 1 || info.bitsPerSample != 16 ||
        (info.channels != 1 && info.channels != 2) ||
        info.sampleRate < 32000 || info.sampleRate > 48000 ||
        info.dataBytes == 0 ||
        info.dataBytes % (static_cast<uint32_t>(info.channels) * 2U) != 0) return false;

    input.clear();
    input.seekg(info.dataOffset, std::ios::beg);
    return static_cast<bool>(input);
}

bool encodeFile(const std::string& inputPath, const std::string& outputPath, int bitrate) {
    if (inputPath.empty() || outputPath.empty() || inputPath == outputPath ||
        bitrate < 128 || bitrate > 320) return false;

    std::ifstream input(inputPath, std::ios::binary);
    if (!input) return false;

    WavInfo wav;
    if (!inspectWav(input, wav)) return false;

    lame_global_flags* rawEncoder = lame_init();
    if (rawEncoder == nullptr) return false;

    const auto closeEncoder = [](lame_global_flags* encoder) {
        if (encoder != nullptr) lame_close(encoder);
    };
    struct EncoderGuard {
        lame_global_flags* value;
        decltype(closeEncoder) close;
        ~EncoderGuard() { close(value); }
    } guard{rawEncoder, closeEncoder};

    if (lame_set_num_channels(rawEncoder, wav.channels) < 0 ||
        lame_set_in_samplerate(rawEncoder, static_cast<int>(wav.sampleRate)) < 0 ||
        lame_set_out_samplerate(rawEncoder, static_cast<int>(wav.sampleRate)) < 0 ||
        lame_set_brate(rawEncoder, bitrate) < 0 ||
        lame_set_quality(rawEncoder, 2) < 0 ||
        lame_set_mode(rawEncoder, wav.channels == 1 ? MONO : JOINT_STEREO) < 0 ||
        lame_set_bWriteVbrTag(rawEncoder, 0) < 0 ||
        lame_init_params(rawEncoder) < 0) return false;

    std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
    if (!output) return false;

    constexpr int framesPerChunk = 1152;
    const int samplesPerChunk = framesPerChunk * wav.channels;
    const int mp3BufferSize = static_cast<int>(1.25 * framesPerChunk + 7200);
    std::vector<unsigned char> pcmBytes(static_cast<size_t>(samplesPerChunk) * 2U);
    std::vector<short> pcm(static_cast<size_t>(samplesPerChunk));
    std::vector<unsigned char> mp3(static_cast<size_t>(mp3BufferSize));

    uint32_t remaining = wav.dataBytes;
    while (remaining > 0) {
        const uint32_t frames = std::min<uint32_t>(
            framesPerChunk,
            remaining / (static_cast<uint32_t>(wav.channels) * 2U));
        if (frames == 0) break;
        const size_t sampleCount = static_cast<size_t>(frames) * wav.channels;
        const size_t byteCount = sampleCount * 2U;
        input.read(reinterpret_cast<char*>(pcmBytes.data()), static_cast<std::streamsize>(byteCount));
        if (input.gcount() != static_cast<std::streamsize>(byteCount)) {
            output.close();
            std::remove(outputPath.c_str());
            return false;
        }
        for (size_t i = 0; i < sampleCount; ++i) {
            pcm[i] = static_cast<short>(read16le(pcmBytes.data() + i * 2U));
        }

        const int encoded = wav.channels == 2
            ? lame_encode_buffer_interleaved(rawEncoder, pcm.data(),
                  static_cast<int>(frames), mp3.data(), mp3BufferSize)
            : lame_encode_buffer(rawEncoder, pcm.data(), pcm.data(),
                  static_cast<int>(frames), mp3.data(), mp3BufferSize);
        if (encoded < 0) {
            output.close();
            std::remove(outputPath.c_str());
            return false;
        }
        if (encoded > 0) output.write(reinterpret_cast<const char*>(mp3.data()), encoded);
        if (!output) {
            output.close();
            std::remove(outputPath.c_str());
            return false;
        }
        remaining -= static_cast<uint32_t>(byteCount);
    }

    const int flushed = lame_encode_flush(rawEncoder, mp3.data(), mp3BufferSize);
    if (flushed < 0) {
        output.close();
        std::remove(outputPath.c_str());
        return false;
    }
    if (flushed > 0) output.write(reinterpret_cast<const char*>(mp3.data()), flushed);
    output.flush();
    const bool success = output.good();
    output.close();

    if (!success) {
        std::remove(outputPath.c_str());
        return false;
    }
    std::ifstream verify(outputPath, std::ios::binary | std::ios::ate);
    const bool hasData = verify && verify.tellg() > 128;
    if (!hasData) std::remove(outputPath.c_str());
    return hasData;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_musicaia_modulos_Mp3Exporter_encodeWavToMp3(
    JNIEnv* env, jobject /* instance */, jstring inputPath, jstring outputPath, jint bitrateKbps) {
    if (inputPath == nullptr || outputPath == nullptr) return JNI_FALSE;
    const char* inputChars = env->GetStringUTFChars(inputPath, nullptr);
    const char* outputChars = env->GetStringUTFChars(outputPath, nullptr);
    if (inputChars == nullptr || outputChars == nullptr) {
        if (inputChars != nullptr) env->ReleaseStringUTFChars(inputPath, inputChars);
        if (outputChars != nullptr) env->ReleaseStringUTFChars(outputPath, outputChars);
        return JNI_FALSE;
    }

    const std::string input(inputChars);
    const std::string output(outputChars);
    env->ReleaseStringUTFChars(inputPath, inputChars);
    env->ReleaseStringUTFChars(outputPath, outputChars);

    try {
        return encodeFile(input, output, static_cast<int>(bitrateKbps)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        std::remove(output.c_str());
        return JNI_FALSE;
    }
}
