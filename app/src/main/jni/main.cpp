#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <unistd.h>
#include <string>
#include <dirent.h>
#include <sys/stat.h>
#include <thread>
#include "dobby.h" // Теперь заголовок Dobby подключен!

#define LOG_TAG "BeariteCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

bool is_game_loaded(const char* lib_name) {
    char line[512];
    FILE* fp = fopen("/proc/self/maps", "rt");
    if (!fp) return false;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, lib_name)) {
            fclose(fp);
            return true;
        }
    }
    fclose(fp);
    return false;
}

void load_external_mods() {
    LOGI("Ядро Bearite успешно запущено.");

    while (!is_game_loaded("libil2cpp.so")) {
        sleep(1);
    }
    LOGI("Движок IL2CPP обнаружен. Готовы использовать DobbyHook.");

    // Здесь внутри модов другие разработчики смогут вызывать функцию DobbyHook
    // Пример: DobbyHook((void*)target_address, (void*)replacement, (void**)&original);
}

__attribute__((constructor))
void init_core() {
    std::thread(load_external_mods).detach();
}
