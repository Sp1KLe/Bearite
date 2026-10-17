#!/usr/bin/env python3
"""Чинит сборку Dobby (master 2024-03) под Android.

Заголовок common/os_arch_features.h использует OSMemory/kReadExecute, но они
объявлены в PlatformUnifiedInterface/platform.h, который сам через dobby/common.h
включает os_arch_features.h. Если первым включён platform.h (так в
platform-posix.cc), OSMemory к этому моменту ещё не объявлен, и на Android
сборка падает. Лечим: в os_arch_features.h оставляем только объявление функции,
а определение переносим в конец platform.h.

Второй патч: closure_bridge_arm64.asm использует синтаксис Mach-O (@PAGE/@PAGEOFF),
который ELF-ассемблер Android не принимает. Для не-Apple платформ подставляем
adrp + :lo12:.

Использование: patch_dobby.py <каталог-с-исходниками-Dobby>
Если Dobby уже исправлен или изменился, скрипт завершается с ошибкой, а не молча.
"""
import re
import sys

root = sys.argv[1]
feat = root + "/common/os_arch_features.h"
plat = root + "/source/PlatformUnifiedInterface/platform.h"

def patch_include_cycle():
    src = open(feat, encoding="utf-8").read()
    if "// bearite-patch" in src:
        print("патч 1 (include-цикл): уже применён")
        return
    _patch_include_cycle(src)

def _patch_include_cycle(src):
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

    definition = "\n".join([
        "",
        "// bearite-patch",
        "namespace features {",
        "namespace android {",
        "inline void make_memory_readable(void *address, size_t size) {",
        "#if defined(ANDROID)",
        "  auto page = (void *)ALIGN_FLOOR(address, OSMemory::PageSize());",
        "  if (!OSMemory::SetPermission(page, OSMemory::PageSize(), kReadExecute)) {",
        "    return;",
        "  }",
        "#endif",
        "}",
        "} // namespace android",
        "} // namespace features",
        "",
    ])
    p = open(plat, encoding="utf-8").read()
    if not p.endswith("\n"):
        p += "\n"
    open(plat, "w", encoding="utf-8").write(p + definition)
    print("патч 1 (include-цикл): применён")



def patch_arm64_asm():
    path = root + "/source/TrampolineBridge/ClosureTrampolineBridge/arm64/closure_bridge_arm64.asm"
    a = open(path, encoding="utf-8").read()
    old = ("adrp TMP_REG_0, cdecl(common_closure_bridge_handler)@PAGE\n"
           "add TMP_REG_0, TMP_REG_0, cdecl(common_closure_bridge_handler)@PAGEOFF\n")
    if "#if defined(__APPLE__)\nadrp" in a:
        print("патч 2 (arm64 asm): уже применён")
        return
    if old not in a:
        sys.exit("patch_dobby: не нашёл @PAGE/@PAGEOFF в closure_bridge_arm64.asm")
    new = ("#if defined(__APPLE__)\n" + old + "#else\n"
           "adrp TMP_REG_0, cdecl(common_closure_bridge_handler)\n"
           "add TMP_REG_0, TMP_REG_0, :lo12:cdecl(common_closure_bridge_handler)\n#endif\n")
    open(path, "w", encoding="utf-8").write(a.replace(old, new))
    print("патч 2 (arm64 asm): применён")


patch_include_cycle()
patch_arm64_asm()
