# Достижения Bearite

Вся папка достижений. В APK попадает как есть (кроме этого файла).

- `index.json` — список достижений.
- `icons/` — аватарки (png, квадрат, лучше 256×256).
- `animation/` — анимация получения (скинешь позже).

Добавить достижение: запись `{"id":"my_id","name":"Название","desc":"Как получить","icon":"icons/my_id.png"}` в `index.json` и png в `icons/`. Нет картинки — покажется ★.

Открывает достижение код: лаунчер `AchievementStore.unlockKey("bearite:my_id")`, игра — строка `bearite<TAB>my_id` в `filesDir/_ach`.
