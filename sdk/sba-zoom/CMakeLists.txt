cmake_minimum_required(VERSION 3.18)
project(sbazoom CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(sbazoom SHARED sba_zoom.cpp)

# Папка sdk (там лежат bearite_api.h и bearite_il2cpp.h), если код их подключает
target_include_directories(sbazoom PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)

target_link_libraries(sbazoom log)
