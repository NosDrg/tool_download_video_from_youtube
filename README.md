# C++ Media Batch Downloader (DirectX 12 + Dear ImGui)

Ứng dụng tải video/audio hàng loạt đa nền tảng (YouTube, TikTok, Facebook, Soundcloud,...) chạy trên nền DirectX 12 với giao diện Dear ImGui, sử dụng `yt-dlp` làm engine tải ngầm.

---

## 1. Yêu cầu môi trường

* **Hệ điều hành:** Windows 10 / Windows 11 (64-bit).
* **Môi trường biên dịch:** [MSYS2](https://www.msys2.org/) (sử dụng môi trường **UCRT64**).
* **Card đồ họa:** Hỗ trợ DirectX 12 (Feature Level 11.0 trở lên).

---

## 2. Cài đặt công cụ biên dịch (MSYS2)

Mở terminal **MSYS2 UCRT64** từ Start Menu và chạy lệnh sau để cài đặt trình biên dịch GCC, CMake và Ninja:

```bash
pacman -S --needed base-devel mingw-w64-ucrt-x86_64-toolchain mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-ninja