cmake_minimum_required(VERSION 3.22.1)
project("redmiaudiobridge")

add_library(
    redmiaudiobridge
    SHARED
    RedmiAudioBridge.cpp
    native-lib.cpp
)

find_library(log-lib log)

target_link_libraries(
    redmiaudiobridge
    ${log-lib}
)
