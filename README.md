# C++ Media Batch Downloader (DirectX 12 + Dear ImGui)

Ứng dụng tải video/audio hàng loạt đa nền tảng (YouTube, TikTok, Facebook, Soundcloud,...) chạy trên nền DirectX 12 với giao diện Dear ImGui, sử dụng `yt-dlp` làm engine tải ngầm.

---

## 1. Yêu cầu môi trường

* **Hệ điều hành:** Windows 10 / Windows 11 (64-bit).
* **Môi trường biên dịch:** [MSYS2](https://www.msys2.org/) (sử dụng môi trường **UCRT64** để biên dịch chương trình).
* **Card đồ họa:** Hỗ trợ DirectX 12 (Feature Level 11.0 trở lên).

---

## 2. Cài đặt công cụ biên dịch (MSYS2) - Có thể dùng trình biên dịch khác

Mở terminal **MSYS2 UCRT64** từ Start Menu và chạy lệnh sau để cài đặt trình biên dịch GCC, CMake và Ninja:

```bash
pacman -S --needed base-devel mingw-w64-ucrt-x86_64-toolchain mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-ninja

```

---

## 3. Chuẩn bị file thực thi bổ trợ

Để ứng dụng tải và ghép video/audio, bạn cần chuẩn bị 2 file sau và đặt vào thư mục dự án (đã có sẵn):

* `yt-dlp.exe`: Tải bản Windows từ yt-dlp GitHub Releases.

* `ffmpeg.exe`: Tải bản nén cho Windows từ trang chủ FFmpeg và giải nén lấy file `ffmpeg.exe`.

---

## 4. Hướng dẫn biên dịch
Mở terminal **MSYS2 UCRT64**, di chuyển đến thư mục dự án và thực hiện các bước sau:

```bash

# 1. Tạo và chuyển vào thư mục build
mkdir -p build && cd build

# 2. Sinh file cấu hình dự án với CMake và Ninja
cmake -G "Ninja" ..

# 3. Tiến hành biên dịch
ninja

```
Sau khi hoàn tất, file thực thi `VideoDownloader.exe` sẽ được tạo ra bên trong thư mục `build/`.

---

## 5. Khởi chạy ứng dụng
Đảm bảo `yt-dlp.exe` và `ffmpeg.exe` đã nằm cùng thư mục với `VideoDownloader.exe`:

```Bash
cp ../yt-dlp.exe .
cp ../ffmpeg.exe .
```
