// Minimal Android/libretro host for one user's own SNES ROM.
// Project source: MIT. Snes9x itself is non-commercial freeware (see README).
#include <jni.h>
#include <android/bitmap.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include <algorithm>

namespace {
using retro_environment_t = bool (*)(unsigned, void*);
using retro_video_refresh_t = void (*)(const void*, unsigned, unsigned, size_t);
using retro_audio_sample_t = void (*)(int16_t, int16_t);
using retro_audio_sample_batch_t = size_t (*)(const int16_t*, size_t);
using retro_input_poll_t = void (*)();
using retro_input_state_t = int16_t (*)(unsigned, unsigned, unsigned, unsigned);
struct retro_game_info { const char* path; const void* data; size_t size; const char* meta; };
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };
enum : unsigned {
    ENV_GET_CAN_DUPE=3, ENV_GET_SYSTEM_DIRECTORY=9, ENV_SET_PIXEL_FORMAT=10,
    ENV_GET_VARIABLE=15, ENV_GET_VARIABLE_UPDATE=17,
    ENV_GET_CORE_ASSETS_DIRECTORY=30, ENV_GET_SAVE_DIRECTORY=31,
    ENV_SET_GEOMETRY=37, ENV_GET_AUDIO_VIDEO_ENABLE=47,
};
enum : unsigned { DEVICE_JOYPAD=1, MEMORY_SAVE_RAM=0 };
struct retro_variable { const char* key; const char* value; };

void* lib = nullptr;
void (*core_set_environment)(retro_environment_t) = nullptr;
void (*core_set_video_refresh)(retro_video_refresh_t) = nullptr;
void (*core_set_audio_sample)(retro_audio_sample_t) = nullptr;
void (*core_set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
void (*core_set_input_poll)(retro_input_poll_t) = nullptr;
void (*core_set_input_state)(retro_input_state_t) = nullptr;
void (*core_init)() = nullptr;
void (*core_deinit)() = nullptr;
bool (*core_load_game)(const retro_game_info*) = nullptr;
void (*core_unload_game)() = nullptr;
void (*core_get_system_av_info)(retro_system_av_info*) = nullptr;
void (*core_run)() = nullptr;
void (*core_reset)() = nullptr;
void* (*core_get_memory_data)(unsigned) = nullptr;
size_t (*core_get_memory_size)(unsigned) = nullptr;
size_t (*core_serialize_size)() = nullptr;
bool (*core_serialize)(void*, size_t) = nullptr;
bool (*core_unserialize)(const void*, size_t) = nullptr;

std::vector<uint8_t> rom_data;
std::vector<uint32_t> frame;
std::vector<int16_t> audio;
std::string rom_path, save_path;
unsigned frame_width=0, frame_height=0;
unsigned current_input=0;
unsigned pixel_format=0; // libretro 0RGB1555 (default), 1 XRGB8888, 2 RGB565
int sample_rate=32040;
bool initialized=false, game_loaded=false;

bool read_file(const std::string& path, std::vector<uint8_t>& bytes) {
    FILE* file = fopen(path.c_str(), "rb");
    if (!file) return false;
    if (fseek(file, 0, SEEK_END) != 0) { fclose(file); return false; }
    long n = ftell(file);
    if (n <= 0 || n > 16*1024*1024) { fclose(file); return false; }
    rewind(file);
    bytes.resize(static_cast<size_t>(n));
    bool ok = fread(bytes.data(), 1, bytes.size(), file) == bytes.size();
    fclose(file);
    if (!ok) bytes.clear();
    return ok;
}
bool write_file(const std::string& path, const void* bytes, size_t count) {
    FILE* file = fopen(path.c_str(), "wb");
    if (!file) return false;
    bool ok = fwrite(bytes, 1, count, file) == count;
    ok = fclose(file) == 0 && ok;
    return ok;
}

bool environment(unsigned command, void* data) {
    switch (command) {
        case ENV_GET_CAN_DUPE:
            if (data) *static_cast<bool*>(data) = true;
            return true;
        case ENV_SET_PIXEL_FORMAT:
            if (!data) return false;
            pixel_format = *static_cast<const unsigned*>(data);
            return pixel_format <= 2;
        case ENV_GET_SYSTEM_DIRECTORY:
        case ENV_GET_CORE_ASSETS_DIRECTORY:
        case ENV_GET_SAVE_DIRECTORY:
            if (data) *static_cast<const char**>(data) = save_path.c_str();
            return true;
        case ENV_GET_VARIABLE:
            if (data) static_cast<retro_variable*>(data)->value = nullptr;
            return false;
        case ENV_GET_VARIABLE_UPDATE:
            if (data) *static_cast<bool*>(data) = false;
            return true;
        case ENV_SET_GEOMETRY:
            return true;
        case ENV_GET_AUDIO_VIDEO_ENABLE:
            if (data) *static_cast<int*>(data) = 3; // video + audio
            return true;
        default:
            return false;
    }
}

inline uint32_t rgb565_to_argb(uint16_t c) {
    unsigned r=(c>>11)&31, g=(c>>5)&63, b=c&31;
    return 0xFF000000u | ((b*255/31)<<16) | ((g*255/63)<<8) | (r*255/31);
}
inline uint32_t rgb1555_to_argb(uint16_t c) {
    unsigned r=(c>>10)&31, g=(c>>5)&31, b=c&31;
    return 0xFF000000u | ((b*255/31)<<16) | ((g*255/31)<<8) | (r*255/31);
}
void video_callback(const void* data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !w || !h || w>512 || h>480) return;
    frame_width=w; frame_height=h;
    frame.resize(static_cast<size_t>(w)*h);
    for (unsigned y=0;y<h;y++) {
        const uint8_t* line=static_cast<const uint8_t*>(data)+y*pitch;
        for (unsigned x=0;x<w;x++) {
            uint32_t color;
            if (pixel_format==1) {
                uint32_t raw;
                memcpy(&raw,line+x*4,4);
                color=0xFF000000u | ((raw & 0x000000FFu)<<16) | (raw & 0x0000FF00u) | ((raw & 0x00FF0000u)>>16);
            } else {
                uint16_t raw;
                memcpy(&raw,line+x*2,2);
                color=(pixel_format==2)?rgb565_to_argb(raw):rgb1555_to_argb(raw);
            }
            frame[static_cast<size_t>(y)*w+x]=color;
        }
    }
}
void audio_callback(int16_t left, int16_t right) { audio.push_back(left); audio.push_back(right); }
size_t audio_batch_callback(const int16_t* samples, size_t frames) {
    if (samples && frames <= 8192) audio.insert(audio.end(),samples,samples+frames*2);
    return frames;
}
void input_poll_callback() { }
int16_t input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port || device!=DEVICE_JOYPAD || index || id>=16) return 0;
    return (current_input & (1u<<id)) ? 1 : 0;
}

template<typename T> bool load_symbol(T& function, const char* name) {
    function = reinterpret_cast<T>(dlsym(lib,name));
    return function != nullptr;
}
void stop_core() {
    if (game_loaded && core_unload_game) core_unload_game();
    game_loaded=false;
    if (initialized && core_deinit) core_deinit();
    initialized=false;
    if (lib) dlclose(lib);
    lib=nullptr;
    rom_data.clear(); frame.clear(); audio.clear();
    frame_width=0; frame_height=0; current_input=0; pixel_format=0;
}
void read_sram() {
    if (!core_get_memory_size || !core_get_memory_data) return;
    size_t n=core_get_memory_size(MEMORY_SAVE_RAM);
    void* ptr=core_get_memory_data(MEMORY_SAVE_RAM);
    if (!ptr || n==0 || n>0x20000) return;
    std::vector<uint8_t> contents;
    if (read_file(save_path+"/choplifter.srm",contents) && contents.size()==n)
        memcpy(ptr, contents.data(), n);
}
void write_sram() {
    if (!game_loaded || !core_get_memory_size || !core_get_memory_data) return;
    size_t n=core_get_memory_size(MEMORY_SAVE_RAM);
    void* ptr=core_get_memory_data(MEMORY_SAVE_RAM);
    if (ptr && n && n<=0x20000) write_file(save_path+"/choplifter.srm",ptr,n);
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_hr_astrid_minigun_NativeBridge_open(JNIEnv* env, jclass, jstring jcore, jstring jrom, jstring jsave) {
    stop_core();
    const char* core = env->GetStringUTFChars(jcore,nullptr);
    const char* path = env->GetStringUTFChars(jrom,nullptr);
    const char* save = env->GetStringUTFChars(jsave,nullptr);
    std::string core_file(core), rom_file(path);
    save_path=save; rom_path=rom_file;
    env->ReleaseStringUTFChars(jcore,core);
    env->ReleaseStringUTFChars(jrom,path);
    env->ReleaseStringUTFChars(jsave,save);
    if (!read_file(rom_file,rom_data)) return JNI_FALSE;
    lib=dlopen(core_file.c_str(),RTLD_NOW|RTLD_LOCAL);
    if (!lib) return JNI_FALSE;
    bool ok=
        load_symbol(core_set_environment,"retro_set_environment") &&
        load_symbol(core_set_video_refresh,"retro_set_video_refresh") &&
        load_symbol(core_set_audio_sample,"retro_set_audio_sample") &&
        load_symbol(core_set_audio_sample_batch,"retro_set_audio_sample_batch") &&
        load_symbol(core_set_input_poll,"retro_set_input_poll") &&
        load_symbol(core_set_input_state,"retro_set_input_state") &&
        load_symbol(core_init,"retro_init") &&
        load_symbol(core_deinit,"retro_deinit") &&
        load_symbol(core_load_game,"retro_load_game") &&
        load_symbol(core_unload_game,"retro_unload_game") &&
        load_symbol(core_get_system_av_info,"retro_get_system_av_info") &&
        load_symbol(core_run,"retro_run") &&
        load_symbol(core_reset,"retro_reset") &&
        load_symbol(core_get_memory_data,"retro_get_memory_data") &&
        load_symbol(core_get_memory_size,"retro_get_memory_size") &&
        load_symbol(core_serialize_size,"retro_serialize_size") &&
        load_symbol(core_serialize,"retro_serialize") &&
        load_symbol(core_unserialize,"retro_unserialize");
    if (!ok) { stop_core(); return JNI_FALSE; }
    core_set_environment(environment);
    core_set_video_refresh(video_callback);
    core_set_audio_sample(audio_callback);
    core_set_audio_sample_batch(audio_batch_callback);
    core_set_input_poll(input_poll_callback);
    core_set_input_state(input_state_callback);
    core_init(); initialized=true;
    retro_game_info game { rom_path.c_str(), rom_data.data(), rom_data.size(), nullptr };
    if (!core_load_game(&game)) { stop_core(); return JNI_FALSE; }
    game_loaded=true;
    retro_system_av_info av {};
    core_get_system_av_info(&av);
    if (av.timing.sample_rate >=8000 && av.timing.sample_rate<=192000)
        sample_rate=static_cast<int>(av.timing.sample_rate+0.5);
    read_sram();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_hr_astrid_minigun_NativeBridge_step(JNIEnv* env, jclass, jint button_mask, jobject bitmap) {
    if (!game_loaded || !bitmap) return 0;
    current_input=static_cast<unsigned>(button_mask);
    audio.clear();
    core_run();
    if (frame.empty()) return 0;
    AndroidBitmapInfo info {};
    if (AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        frame_width>info.width || frame_height>info.height) return 0;
    void* addr=nullptr;
    if (AndroidBitmap_lockPixels(env,bitmap,&addr)!=ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    for (unsigned y=0;y<frame_height;y++) {
        auto* row=reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(addr)+y*info.stride);
        memcpy(row,frame.data()+static_cast<size_t>(y)*frame_width,frame_width*sizeof(uint32_t));
    }
    AndroidBitmap_unlockPixels(env,bitmap);
    return static_cast<jint>((frame_width<<16)|frame_height);
}
extern "C" JNIEXPORT jshortArray JNICALL
Java_hr_astrid_minigun_NativeBridge_drainAudio(JNIEnv* env, jclass) {
    auto* arr=env->NewShortArray(static_cast<jsize>(audio.size()));
    if (arr && !audio.empty()) env->SetShortArrayRegion(arr,0,static_cast<jsize>(audio.size()),audio.data());
    audio.clear();
    return arr;
}
extern "C" JNIEXPORT jint JNICALL
Java_hr_astrid_minigun_NativeBridge_sampleRate(JNIEnv*,jclass) { return sample_rate; }
extern "C" JNIEXPORT void JNICALL
Java_hr_astrid_minigun_NativeBridge_reset(JNIEnv*,jclass) { if (game_loaded) core_reset(); }
extern "C" JNIEXPORT void JNICALL
Java_hr_astrid_minigun_NativeBridge_saveSram(JNIEnv*,jclass) { write_sram(); }
extern "C" JNIEXPORT jboolean JNICALL
Java_hr_astrid_minigun_NativeBridge_saveState(JNIEnv*,jclass) {
    if (!game_loaded) return JNI_FALSE;
    size_t n=core_serialize_size();
    if (!n || n>0x1000000) return JNI_FALSE;
    std::vector<uint8_t> bytes(n);
    if (!core_serialize(bytes.data(),bytes.size())) return JNI_FALSE;
    write_sram();
    return write_file(save_path+"/choplifter.state",bytes.data(),bytes.size()) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_hr_astrid_minigun_NativeBridge_loadState(JNIEnv*,jclass) {
    if (!game_loaded) return JNI_FALSE;
    std::vector<uint8_t> bytes;
    if (!read_file(save_path+"/choplifter.state",bytes)) return JNI_FALSE;
    if (bytes.size()!=core_serialize_size()) return JNI_FALSE;
    return core_unserialize(bytes.data(),bytes.size()) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_hr_astrid_minigun_NativeBridge_close(JNIEnv*,jclass) { write_sram(); stop_core(); }
