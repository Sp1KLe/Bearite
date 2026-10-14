cmake_minimum_required(VERSION 3.22.1)
project(bearite_loader LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a" AND NOT ANDROID_ABI STREQUAL "armeabi-v7a")
  message(FATAL_ERROR "Неподдерживаемый ABI: ${ANDROID_ABI}")
endif()

# Путь к скомпилированной Dobby
set(DOBBY_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}")
set(DOBBY_LIB "${DOBBY_LIB_DIR}/libdobby.so")

add_library(dobby SHARED IMPORTED)
set_target_properties(dobby PROPERTIES
  IMPORTED_LOCATION "${DOBBY_LIB}"
)

# Подключаем заголовки Dobby
set(DOBBY_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/dobby/include")

# Создаем нашу библиотеку
add_library(bearite_loader SHARED main.cpp)

target_include_directories(bearite_loader PRIVATE "${DOBBY_INCLUDE}")
target_link_libraries(bearite_loader
  dobby
  log
)
