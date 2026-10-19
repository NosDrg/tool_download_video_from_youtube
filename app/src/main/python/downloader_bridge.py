import json
import os
import urllib.request
import yt_dlp
from mutagen.mp4 import MP4, MP4Cover

class DownloadInterruptedException(Exception):
    pass

def embed_metadata_to_m4a(file_path, title, artist, thumbnail_url):
    """
    Nhúng Title, Artist và Ảnh bìa (Cover Art) vào file M4A bằng Mutagen.
    """
    try:
        audio = MP4(file_path)
        if title:
            audio['\xa9nam'] = [title]
        if artist:
            audio['\xa9ART'] = [artist]
            audio['\xa9alb'] = [artist]

        if thumbnail_url:
            req = urllib.request.Request(
                thumbnail_url,
                headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                cover_data = resp.read()

            fmt = MP4Cover.FORMAT_PNG if ".png" in thumbnail_url.lower() else MP4Cover.FORMAT_JPEG
            
            audio['covr'] = [MP4Cover(cover_data, fmt)]

        audio.save()
        print(f"[Mutagen] Đã nhúng metadata và thumbnail thành công: {file_path}")
    except Exception as e:
        print(f"[Mutagen] Lỗi khi nhúng metadata: {e}")

def clean_url(url):
    """Chỉ cắt tham số playlist đối với link YouTube đơn lẻ"""
    if ("youtube.com/watch" in url or "youtu.be/" in url) and "&list=" in url:
        return url.split("&list=")[0]
    return url

def extract_media_info(url):
    has_list_param = "list=" in url and ("youtube.com" in url or "youtu.be" in url)
    target_url = clean_url(url)

    ydl_opts = {
        'skip_download': True,
        'nocheckcertificate': True,
        'noplaylist': True,
        'socket_timeout': 15,
        'retries': 2,
        'quiet': True,
        'no_warnings': True
    }

    # Chỉ bật player_client riêng khi là YouTube để tránh lỗi trên TikTok/Facebook/Instagram
    if "youtube.com" in url or "youtu.be" in url:
        ydl_opts['extractor_args'] = {'youtube': {'player_client': ['web', 'android']}}

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(target_url, download=False)
            if not info:
                return json.dumps({"error": "Không thể lấy thông tin URL"})

            # Lấy thumbnail chất lượng cao nhất có thể
            thumbnail = info.get('thumbnail') or ""
            if not thumbnail and info.get('thumbnails'):
                thumbnail = info['thumbnails'][-1].get('url', '')

            duration_sec = info.get('duration') or 0
            mins, secs = divmod(duration_sec, 60)
            hours, mins = divmod(mins, 60)
            dur_str = f"{hours:02d}:{mins:02d}:{secs:02d}" if hours > 0 else f"{mins:02d}:{secs:02d}"

            formats = info.get('formats', [])
            video_map = {}

            # Tùy chọn Audio
            audio_format = {
                "id": "best_audio_m4a",
                "label": "Audio M4A",
                "type": "audio",
                "selector": "bestaudio[ext=m4a]/bestaudio/best"
            }

            # Lọc Video đa nền tảng
            for f in formats:
                height = f.get('height')
                vcodec = f.get('vcodec') or 'none'
                ext = f.get('ext', 'mp4')
                filesize = f.get('filesize') or f.get('filesize_approx') or 0
                filesize_mb = f" (~{round(filesize / (1024 * 1024), 1)} MB)" if filesize else ""

                if height and vcodec != 'none':
                    if height not in video_map or ext == 'mp4':
                        video_map[height] = {
                            "id": f"video_{height}",
                            "label": f"Video {height}p {ext.upper()}{filesize_mb}",
                            "type": "video",
                            "height": height
                        }

            if not video_map:
                video_map[720] = {
                    "id": "video_best",
                    "label": "Video Gốc",
                    "type": "video",
                    "height": 720
                }

            sorted_videos = [video_map[h] for h in sorted(video_map.keys(), reverse=True)]

            result = {
                "title": info.get('title', 'Media không tiêu đề'),
                "thumbnail": thumbnail,
                "channel": info.get('uploader') or info.get('channel') or info.get('creator') or 'Mạng xã hội',
                "duration": dur_str,
                "formats": [audio_format] + sorted_videos,
                "is_playlist": has_list_param
            }
            return json.dumps(result, ensure_ascii=False)

    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)

def download_media_task(url, output_dir, format_type, height=720, download_playlist=False, cancel_token=None, callback=None):
    if not download_playlist:
        url = clean_url(url)

    def progress_hook(d):
        if cancel_token and cancel_token.isCancelled():
            raise DownloadInterruptedException("Đã hủy tác vụ.")

        if callback and d.get('status') == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            downloaded = d.get('downloaded_bytes', 0)
            percent = (downloaded / total * 100) if total > 0 else 0
            speed = d.get('speed') or 0
            speed_str = f"{speed / 1024 / 1024:.2f} MB/s" if speed > 0 else "Đang tải..."
            
            info = d.get('info_dict') or {}
            raw_title = info.get('title') or ""
            p_index = info.get('playlist_index')
            p_total = info.get('n_entries') or info.get('playlist_count')

            display_title = f"[{p_index}/{p_total}] {raw_title}" if (download_playlist and p_index and p_total) else raw_title
            callback.onProgress(float(percent), speed_str, display_title)

    base_opts = {
        'progress_hooks': [progress_hook],
        'noplaylist': not download_playlist,
        'socket_timeout': 20,
        'retries': 3,
        'nocheckcertificate': True,
        'quiet': True,
        'no_warnings': True
    }

    if "youtube.com" in url or "youtu.be" in url:
        base_opts['extractor_args'] = {'youtube': {'player_client': ['web', 'android']}}

    # Tải Playlist
    if download_playlist:
        out_pattern = os.path.join(output_dir, '%(title)s.%(ext)s')
        opts = dict(base_opts)
        opts['outtmpl'] = out_pattern
        opts['format'] = 'bestaudio[ext=m4a]/bestaudio' if format_type == "audio" else f'best[height<={height}][ext=mp4]/best[ext=mp4]/best'

        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            
            # Ưu tiên lấy thumbnail đuôi JPG từ danh sách thumbnails để chuẩn hóa Cover Art
            thumb_url = ""
            thumbnails = info.get('thumbnails') or []
            for t in reversed(thumbnails):
                u = t.get('url', '')
                if '.jpg' in u.lower() or '.jpeg' in u.lower():
                    thumb_url = u
                    break
            if not thumb_url:
                thumb_url = info.get('thumbnail') or ""

            embed_metadata_to_m4a(
                file_path=audio_path,
                title=info.get('title'),
                artist=info.get('uploader') or info.get('creator') or info.get('channel'),
                thumbnail_url=thumb_url
            )

        return json.dumps({"status": "playlist_completed", "is_audio": (format_type == "audio")})

    # Tải 1 file Audio đơn lẻ kèm Metadata
    if format_type == "audio":
        audio_path = os.path.join(output_dir, "audio_stream.m4a")
        if os.path.exists(audio_path):
            os.remove(audio_path)

        opts = dict(base_opts)
        opts['format'] = 'bestaudio[ext=m4a]/bestaudio/best'
        opts['outtmpl'] = audio_path

        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            # Nhúng thông tin bìa và nghệ sĩ sau khi file tải xong
            embed_metadata_to_m4a(
                file_path=audio_path,
                title=info.get('title'),
                artist=info.get('uploader') or info.get('creator'),
                thumbnail_url=info.get('thumbnail')
            )

        return json.dumps({"status": "ready", "is_audio": True, "file_path": audio_path})

    # Tải 1 file Video (Hỗ trợ cả stream Progressive của TikTok/FB lẫn tách luồng DASH)
    video_path = os.path.join(output_dir, "video_stream.mp4")
    audio_path = os.path.join(output_dir, "audio_stream.m4a")
    for p in (video_path, audio_path):
        if os.path.exists(p):
            os.remove(p)

    # 1. Thử lấy Progressive video (TikTok, Facebook, Instagram thường chỉ cần 1 luồng này)
    opts_prog = dict(base_opts)
    opts_prog['format'] = f'best[height<={height}][ext=mp4]/best[ext=mp4]/best'
    opts_prog['outtmpl'] = video_path

    try:
        with yt_dlp.YoutubeDL(opts_prog) as ydl:
            info = ydl.extract_info(url, download=True)
            # Nếu định dạng tải về đã có cả hình lẫn tiếng thì trả về trực tiếp
            if info.get('vcodec') != 'none' and info.get('acodec') != 'none':
                return json.dumps({"status": "ready", "is_audio": False, "file_path": video_path})
    except Exception:
        pass

    # 2. Nếu là YouTube độ phân giải cao tách luồng -> Tải video stream + audio stream để MediaMuxer ghép
    opts_v = dict(base_opts)
    opts_v['format'] = f'bestvideo[height<={height}][ext=mp4]/bestvideo'
    opts_v['outtmpl'] = video_path
    with yt_dlp.YoutubeDL(opts_v) as ydl:
        info_v = ydl.extract_info(url, download=True)

    opts_a = dict(base_opts)
    opts_a['format'] = 'bestaudio[ext=m4a]/bestaudio/best'
    opts_a['outtmpl'] = audio_path
    with yt_dlp.YoutubeDL(opts_a) as ydl:
        ydl.download([url])

    return json.dumps({
        "status": "mux_required",
        "is_audio": False,
        "video_path": video_path,
        "audio_path": audio_path
    })