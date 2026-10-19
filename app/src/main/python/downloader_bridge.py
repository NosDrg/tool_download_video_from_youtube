import os
import yt_dlp

class DownloadInterruptedException(Exception):
    pass

def download_media(url, output_dir, is_audio=False, res_limit="best", callback=None, cancel_token=None):
    """
    Tải media trực tiếp qua yt-dlp.
    Hỗ trợ báo tiến độ và hủy tải ngay lập tức qua cancel_token.
    """
    def progress_hook(d):
        if cancel_token and cancel_token.isCancelled():
            raise DownloadInterruptedException("Tác vụ đã bị người dùng hủy.")

        if callback and d.get('status') == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            downloaded = d.get('downloaded_bytes', 0)
            percent = (downloaded / total * 100) if total > 0 else 0
            speed = d.get('speed') or 0
            speed_str = f"{speed / 1024 / 1024:.2f} MB/s" if speed > 0 else "Đang kết nối..."
            callback.onProgress(float(percent), speed_str)
        elif callback and d.get('status') == 'finished':
            callback.onProgress(100.0, "Đang hoàn thiện file...")

    if is_audio:
        format_selector = 'bestaudio[ext=m4a]/bestaudio'
        outtmpl = os.path.join(output_dir, '%(title)s.%(ext)s')
    else:
        if res_limit and res_limit != "best":
            format_selector = f'best[height<={res_limit}][ext=mp4]/best[ext=mp4]/best'
        else:
            format_selector = 'best[ext=mp4]/best'
        outtmpl = os.path.join(output_dir, '%(title)s.%(ext)s')

    ydl_opts = {
        'format': format_selector,
        'outtmpl': outtmpl,
        'progress_hooks': [progress_hook],
        'quiet': True,
        'no_warnings': True,
        'nocheckcertificate': True
    }

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        filename = ydl.prepare_filename(info)
        return filename