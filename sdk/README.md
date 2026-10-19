# Набор для создания модов Bearite

Всё, что нужно для нативного мода, лежит в этой папке. Брать что-то ещё из репозитория не нужно.

| Файл | Зачем |
|---|---|
| `bearite_api.h` | API мода: лог, настройки, адреса IL2CPP, хуки |
| `bearite_il2cpp.h` | Помощники: найти класс или метод по имени и перехватить его |
| `build-mod.sh` | Собирает папку мода в готовый zip для Bearite |
| `mods/template/` | Пустой мод: копируй и меняй |
| `sba-zoom/` | Готовый мод «Зум» с плавным приближением и настройками (собирается через CMake) |
| `github-workflow/build-mods.yml` | Сборка в облаке для твоего репозитория, без NDK у тебя |

## Мод за 4 шага (без компьютера)

1. В этом репозитории (или в своём форке) создай папку `sdk/mods/мой-мод/` с файлами:
   - `mod.json`: скопируй из `sdk/mods/template/mod.json`, поменяй `id`, название и имя библиотеки в `libs`;
   - `src/mod.c`: скопируй из `sdk/mods/template/src/mod.c` и пиши свой код.
   На телефоне это делается в браузере: **Add file → Create new file**, в имени файла можно писать `/`,
   чтобы создавать папки.
2. Сохрани (**Commit changes**). GitHub Actions сам соберёт все моды из `sdk/mods/`.
3. Вкладка **Actions** → последний запуск → **Artifacts** → `Bearite-mods`: там `<id>.zip` для каждого мода.
4. В Bearite: вкладка **Моды** → **Установить мод** → выбери zip.

Если сборка упала, ошибка компилятора показана красной строкой в запуске (раздел Annotations).

## Что должно быть в моде

`mod.json`:

```json
{
  "id": "my-mod",
  "name": "Мой мод",
  "version": "1.0",
  "author": "я",
  "description": "Что делает мод",
  "libs": ["libs/arm64-v8a/libmymod.so"],
  "settings": [
    { "id": "speed", "type": "float", "name": "Скорость", "min": 0.5, "max": 3, "default": 1 }
  ]
}
```

`src/mod.c`:

```c
#include "bearite_api.h"
#include "bearite_il2cpp.h"

__attribute__((visibility("default")))
void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
    api->log(mod_id, "Привет из мода!");
    double speed = api->get_float(mod_id, "speed", 1.0);   /* значение из настроек */
}
```

Аватарка: положи `icon.png` рядом с `mod.json` (квадрат 128–256 пикселей).

Кнопки на экране игры: добавь в `mod.json` массив `hud`, например
`{ "label": "Зум", "setting": "zoom_on", "action": "toggle", "pos": "left" }`. Нажатие меняет настройку,
а мод читает её через `get_bool` и другие функции. Полное описание: главный `README.md`, раздел «Кнопки на экране игры».

## Перехват метода игры по имени

```c
static void (*orig)(void* self, float value, const void* method);

static void my_hook(void* self, float value, const void* method) {
    orig(self, value / 2.0f, method);          /* свой код, потом оригинал */
}

void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
    if (!bearite_il2cpp_init(api)) return;
    bearite_hook_method(api, mod_id, "UnityEngine", "Camera", "set_fieldOfView", 1,
                        (void*)my_hook, (void**)&orig);
}
```

- Для метода экземпляра первым аргументом идёт `self`, последним всегда `const void* method`.
  Для статического метода `self` нет.
- Результат перехвата (найден ли метод, получилось ли) пишется в лог Bearite.
- Нужны имена классов и методов игры. Их можно получить дампером IL2CPP (например, Il2CppDumper) по
  `libil2cpp.so` и `global-metadata.dat` игры.
- Другие помощники: `bearite_find_class`, `bearite_find_method`, `bearite_invoke`.

## Сборка на компьютере

```
export ANDROID_NDK_HOME=/путь/к/ndk
bash build-mod.sh mods/my-mod dist
```

Нужны NDK и python3. Результат: `dist/<id>.zip`.

## Сборка в своём репозитории

Положи файлы этого набора в корень своего репозитория, а `github-workflow/build-mods.yml` в
`.github/workflows/`. После пуша zip модов появятся в Artifacts запуска.
