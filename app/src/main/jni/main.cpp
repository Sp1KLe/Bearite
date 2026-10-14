#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <unistd.h>
#include <string>
#include <dirent.h>
#include <sys/stat.h>
#include <thread>

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
    LOGI("Ядро Bearite успешно запущено во внутреннем процессе игры.");

    // Ожидаем полной инициализации игрового движка
    while (!is_game_loaded("libil2cpp.so")) {
        sleep(1);
    }
    LOGI("Движок IL2CPP обнаружен. Загружаем сторонние плагины модов...");

    std::string path = "/sdcard/Android/data/com.EarthkwakGames.SuperBearAdventure/files/mods/";
    mkdir(path.c_str(), 0777);

    DIR* dir = opendir(path.c_str());
    if (dir) {
        struct dirent* file;
        while ((file = readdir(dir)) != NULL) {
            std::string name = file->d_name;
            if (name.find(".so") != std::string::npos) {
                std::string full_path = path + name;
                void* handle = dlopen(full_path.c_str(), RTLD_NOW);
                if (handle) {
                    LOGI("Мод %s успешно активирован в памяти!", name.c_str());
                }
            }
        }
        closedir(dir);
    }
}

__attribute__((constructor))
void init_core() {
    std::thread(load_external_mods).detach();
}
