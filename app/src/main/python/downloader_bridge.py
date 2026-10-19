import json
import os
import glob
import yt_dlp

class DownloadInterruptedException(Exception):
    pass

def clean_url(url):
    if "watch?v=" in url and "&list=" in url:
        return url.split("&list=")[0]
    return url

def extract_media_info(url):
    has_list_param = "list=" in url
    target_url = clean_url(url)

    ydl_opts = {
        'skip_download': True,
        'nocheckcertificate': True,
        'noplaylist': True,
        'socket_timeout': 15,
        'retries': 2,
        # Dùng client web làm chính để YouTube trả về đầy đủ luồng 1080p và 720p
        'extractor_args': {'youtube': {'player_client': ['web', 'android']}},
        'quiet': True,
        'no_warnings': True
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(target_url, download=False)
            if not info:
                return json.dumps({"error": "Không thể lấy thông tin URL"})

            duration_sec = info.get('duration') or 0
            mins, secs = divmod(duration_sec, 60)
            hours, mins = divmod(mins, 60)
            dur_str = f"{hours:02d}:{mins:02d}:{secs:02d}" if hours > 0 else f"{mins:02d}:{secs:02d}"

            formats = info.get('formats', [])
            video_map = {}

            # 1. Luồng Audio chất lượng cao
            audio_format = {
                "id": "best_audio_m4a",
                "label": "Audio M4A",
                "type": "audio",
                "selector": "bestaudio[ext=m4a]/bestaudio"
            }

            # 2. Lọc tất cả luồng Video (chấp nhận cả DASH stream không tiếng vì đã có MediaMuxer)
            for f in formats:
                height = f.get('height')
                vcodec = f.get('vcodec') or 'none'
                ext = f.get('ext', '')
                filesize = f.get('filesize') or f.get('filesize_approx') or 0
                filesize_mb = f" ({round(filesize / (1024 * 1024), 1)} MB)" if filesize else ""

                if height and vcodec != 'none' and height in (1080, 720, 480, 360):
                    if height not in video_map or ext == 'mp4':
                        video_map[height] = {
                            "id": f"video_{height}",
                            "label": f"Video {height}p {ext.upper()}{filesize_mb}",
                            "type": "video",
                            "height": height
                        }

            # Sắp xếp các độ phân giải giảm dần (1080p -> 720p -> 480p -> 360p)
            sorted_videos = [video_map[h] for h in sorted(video_map.keys(), reverse=True)]

            result = {
                "title": info.get('title', 'Video không tiêu đề'),
                "thumbnail": info.get('thumbnail', ''),
                "channel": info.get('uploader') or info.get('channel') or 'YouTube',
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
            
            # Lấy thông tin bài hát hiện tại từ info_dict
            info = d.get('info_dict') or {}
            raw_title = info.get('title') or ""
            p_index = info.get('playlist_index')
            p_total = info.get('n_entries') or info.get('playlist_count')

            if download_playlist and p_index and p_total:
                display_title = f"[{p_index}/{p_total}] {raw_title}"
            elif download_playlist and p_index:
                display_title = f"[{p_index}] {raw_title}"
            else:
                display_title = raw_title

            # Gửi 3 tham số: phần trăm, tốc độ, tiêu đề cụ thể
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


    if download_playlist:
        out_pattern = os.path.join(output_dir, '%(title)s.%(ext)s')
        opts = dict(base_opts)
        opts['outtmpl'] = out_pattern

        if format_type == "audio":
            opts['format'] = 'bestaudio[ext=m4a]/bestaudio'
        else:
            # Đối với playlist video, ưu tiên stream progressive có sẵn tiếng để tránh phải mux từng bài
            opts['format'] = f'best[height<={height}][ext=mp4]/best[ext=mp4]/best'

        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download([url])

        return json.dumps({
            "status": "playlist_completed",
            "is_audio": (format_type == "audio")
        })

    if format_type == "audio":
        audio_path = os.path.join(output_dir, "audio_stream.m4a")
        if os.path.exists(audio_path):
            os.remove(audio_path)

        opts = dict(base_opts)
        opts['format'] = 'bestaudio[ext=m4a]/bestaudio'
        opts['outtmpl'] = audio_path

        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download([url])

        return json.dumps({"status": "ready", "is_audio": True, "file_path": audio_path})

    else:
        video_path = os.path.join(output_dir, "video_stream.mp4")
        audio_path = os.path.join(output_dir, "audio_stream.m4a")
        for p in (video_path, audio_path):
            if os.path.exists(p):
                os.remove(p)

        opts_v = dict(base_opts)
        opts_v['format'] = f'bestvideo[height<={height}][ext=mp4]/bestvideo[ext=mp4]/bestvideo'
        opts_v['outtmpl'] = video_path
        with yt_dlp.YoutubeDL(opts_v) as ydl:
            ydl.download([url])

        opts_a = dict(base_opts)
        opts_a['format'] = 'bestaudio[ext=m4a]/bestaudio'
        opts_a['outtmpl'] = audio_path
        with yt_dlp.YoutubeDL(opts_a) as ydl:
            ydl.download([url])

        return json.dumps({
            "status": "mux_required",
            "is_audio": False,
            "video_path": video_path,
            "audio_path": audio_path
        })