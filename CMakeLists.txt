cmake_minimum_required(VERSION 3.16)
project(VideoDownloaderLinux LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(PkgConfig REQUIRED)
pkg_check_modules(GLFW REQUIRED glfw3)
find_package(OpenGL REQUIRED)

include_directories(. imgui imgui/backends ${GLFW_INCLUDE_DIRS})

add_executable(VideoDownloader
    main.cpp
    Downloader.cpp
    UI.cpp
    imgui/imgui.cpp
    imgui/imgui_draw.cpp
    imgui/imgui_tables.cpp
    imgui/imgui_widgets.cpp
    imgui/imgui_impl_glfw.cpp
    imgui/imgui_impl_opengl3.cpp
)

target_link_libraries(VideoDownloader PRIVATE
    ${GLFW_LIBRARIES}
    OpenGL::GL
    pthread
)