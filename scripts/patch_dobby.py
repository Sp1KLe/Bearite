#!/usr/bin/env python3
"""Чинит сборку Dobby (master 2024-03) под Android.

Заголовок common/os_arch_features.h использует OSMemory/kReadExecute, но они
объявлены в PlatformUnifiedInterface/platform.h, который сам через dobby/common.h
включает os_arch_features.h. Если первым включён platform.h (так в
platform-posix.cc), OSMemory к этому моменту ещё не объявлен, и на Android
сборка падает. Лечим: в os_arch_features.h оставляем только объявление функции,
а определение переносим в конец platform.h.

Использование: patch_dobby.py <каталог-с-исходниками-Dobby>
Если Dobby уже исправлен или изменился, скрипт завершается с ошибкой, а не молча.
"""
import re
import sys

root = sys.argv[1]
feat = root + "/common/os_arch_features.h"
plat = root + "/source/PlatformUnifiedInterface/platform.h"

src = open(feat, encoding="utf-8").read()
if "// bearite-patch" in src:
    print("Dobby уже пропатчен")
    sys.exit(0)

pat = re.compile(
    r"namespace android \{\s*inline void make_memory_readable\(void \*address, size_t size\) \{.*?\n\}\s*\} // namespace android",
    re.S,
)
m = pat.search(src)
if not m:
    sys.exit("patch_dobby: не нашёл android::make_memory_readable в os_arch_features.h")
body = m.group(0)
if "OSMemory::SetPermission" not in body:
    sys.exit("patch_dobby: неожиданное тело make_memory_readable")

decl = (
    "namespace android {\n"
    "// bearite-patch: определение в PlatformUnifiedInterface/platform.h (там объявлен OSMemory)\n"
    "inline void make_memory_readable(void *address, size_t size);\n"
    "} // namespace android"
)
open(feat, "w", encoding="utf-8").write(src[: m.start()] + decl + src[m.end():])

definition = '''
// bearite-patch
namespace features {
namespace android {
inline void make_memory_readable(void *address, size_t size) {
#if defined(ANDROID)
  auto page = (void *)ALIGN_FLOOR(address, OSMemory::PageSize());
  if (!OSMemory::SetPermission(page, OSMemory::PageSize(), kReadExecute)) {
    return;
  }
#endif
}
} // namespace android
} // namespace features
'''
p = open(plat, encoding="utf-8").read()
if not p.endswith("\n"):
    p += "\n"
open(plat, "w", encoding="utf-8").write(p + definition)
print("Dobby пропатчен")
