cmake_minimum_required(VERSION 3.18)
project(sbazoom CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(sbazoom SHARED sba_zoom.cpp)

target_link_libraries(sbazoom log)
