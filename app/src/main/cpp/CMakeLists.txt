cmake_minimum_required(VERSION 3.22.1)
project(bearite LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Dobby собирается scripts/build_dobby.sh в app/src/main/cpp/dobby/
set(DOBBY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/dobby")
if(NOT EXISTS "${DOBBY_DIR}/lib/${ANDROID_ABI}/libdobby.a")
  message(FATAL_ERROR "Нет Dobby для ${ANDROID_ABI}. Сначала запусти scripts/build_dobby.sh")
endif()

add_library(dobby STATIC IMPORTED)
set_target_properties(dobby PROPERTIES
  IMPORTED_LOCATION "${DOBBY_DIR}/lib/${ANDROID_ABI}/libdobby.a")

add_library(bearite SHARED bearite.cpp)
target_include_directories(bearite PRIVATE "${DOBBY_DIR}/include" "${CMAKE_CURRENT_SOURCE_DIR}/../../../../sdk")
target_link_libraries(bearite dobby log)
