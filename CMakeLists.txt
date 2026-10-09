cmake_minimum_required(VERSION 3.16)
project(VideoDownloader LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

file(GLOB IMGUI_SOURCES "imgui/*.cpp")

include_directories(. imgui)

add_executable(VideoDownloader WIN32 
    main.cpp 
    Downloader.cpp 
    UI.cpp 
    ${IMGUI_SOURCES}
)

target_link_libraries(VideoDownloader PRIVATE 
    d3d12 
    dxgi 
    d3dcompiler 
    dwmapi 
    shell32 
    ole32
)