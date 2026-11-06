import json
import os
import glob
import shutil
import urllib.request
from urllib.parse import urlparse, parse_qs
import yt_dlp
from mutagen.mp4 import MP4, MP4Cover

class DownloadInterruptedException(Exception):
    pass

def embed_metadata_to_m4a(file_path, title, artist, thumbnail_url):
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
    except Exception as e:
        print(f"[Mutagen] Bo qua nhung metadata: {e}")

def get_pure_playlist_url(url):
    parsed = urlparse(url)
    qs = parse_qs(parsed.query)
    if 'list' in qs:
        return f"https://www.youtube.com/playlist?list={qs['list'][0]}"
    return url

def clean_video_url(url):
    parsed = urlparse(url)
    qs = parse_qs(parsed.query)
    if 'v' in qs:
        return f"https://www.youtube.com/watch?v={qs['v'][0]}"
    if 'youtu.be' in parsed.netloc:
        video_id = parsed.path.lstrip('/')
        return f"https://www.youtube.com/watch?v={video_id}"
    if 'tiktok.com' in parsed.netloc:
        return f"{parsed.scheme}://{parsed.netloc}{parsed.path}"
    return url

BROWSER_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7',
    'Sec-Fetch-Mode': 'navigate',
}

def extract_media_info(url, cookie_path=None):
    is_pure_playlist = "playlist?list=" in url
    has_list_param = "list=" in url and ("youtube.com" in url or "youtu.be" in url)
    target_url = get_pure_playlist_url(url) if is_pure_playlist else clean_video_url(url)

    ydl_opts = {
        'skip_download': True,
        'nocheckcertificate': True,
        'noplaylist': not is_pure_playlist,
        'playlist_items': '1' if is_pure_playlist else None,
        'socket_timeout': 15,
        'retries': 2,
        'quiet': True,
        'no_warnings': True,
        'http_headers': BROWSER_HEADERS
    }

    if cookie_path and isinstance(cookie_path, str) and os.path.exists(cookie_path):
        ydl_opts['cookiefile'] = cookie_path

    if "youtube.com" in target_url or "youtu.be" in target_url:
        ydl_opts['extractor_args'] = {'youtube': {'player_client': ['mweb', 'android', 'web']}}

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(target_url, download=False)
            if not info:
                return json.dumps({"error": "Khong the lay thong tin URL"})

            if 'entries' in info and info['entries']:
                first_entry = info['entries'][0]
                formats = first_entry.get('formats', [])
                thumbnail = info.get('thumbnail') or first_entry.get('thumbnail') or ""
                channel = info.get('uploader') or first_entry.get('uploader') or "YouTube Playlist"
                p_count = info.get('playlist_count') or len(info['entries'])
                dur_str = f"Playlist ({p_count} video)"
                display_title = f"[Playlist] {info.get('title', 'Danh sach phat')}"
            else:
                formats = info.get('formats', [])
                thumbnail = info.get('thumbnail') or ""
                if not thumbnail and info.get('thumbnails'):
                    thumbnail = info['thumbnails'][-1].get('url', '')
                channel = info.get('uploader') or info.get('channel') or info.get('creator') or 'Mang xa hoi'
                duration_sec = info.get('duration') or 0
                mins, secs = divmod(duration_sec, 60)
                hours, mins = divmod(mins, 60)
                dur_str = f"{hours:02d}:{mins:02d}:{secs:02d}" if hours > 0 else f"{mins:02d}:{secs:02d}"
                display_title = info.get('title', 'Media khong tieu de')

            video_map = {}
            audio_format = {
                "id": "best_audio_m4a",
                "label": "Audio M4A (Chat luong cao)",
                "type": "audio",
                "selector": "bestaudio[ext=m4a]/bestaudio/best"
            }

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
                            "label": f"Video {height}p ({ext.upper()}){filesize_mb}",
                            "type": "video",
                            "height": height
                        }

            if not video_map:
                video_map[720] = {
                    "id": "video_best",
                    "label": "Video Goc (Chat luong cao nhat)",
                    "type": "video",
                    "height": 720
                }

            sorted_videos = [video_map[h] for h in sorted(video_map.keys(), reverse=True)]

            subtitles_list = []
            all_subs = {}
            if info.get('subtitles'):
                all_subs.update(info['subtitles'])
            if info.get('automatic_captions'):
                all_subs.update(info['automatic_captions'])

            priority_langs = {'vi': 'Tieng Viet', 'en': 'Tieng Anh', 'ja': 'Tieng Nhat', 'ko': 'Tieng Han', 'zh': 'Tieng Trung'}
            for code, name in priority_langs.items():
                if any(code in k for k in all_subs.keys()):
                    subtitles_list.append({"code": code, "name": name})

            result = {
                "title": display_title,
                "thumbnail": thumbnail,
                "channel": channel,
                "duration": dur_str,
                "formats": [audio_format] + sorted_videos,
                "subtitles": subtitles_list,
                "is_playlist": is_pure_playlist or has_list_param
            }
            return json.dumps(result, ensure_ascii=False)

    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)

def download_media_task(url, output_dir, format_type, height=720, download_playlist=False, sub_lang=None, cookie_path=None, cancel_token=None, callback=None):
    if cookie_path is not None and not isinstance(cookie_path, str):
        if hasattr(cookie_path, "isCancelled"):
            cancel_token = cookie_path
        cookie_path = None

    target_url = get_pure_playlist_url(url) if download_playlist else clean_video_url(url)

    def progress_hook(d):
        if cancel_token and cancel_token.isCancelled():
            raise DownloadInterruptedException("Da huy tac vu.")

        if callback and d.get('status') == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            downloaded = d.get('downloaded_bytes', 0)
            percent = (downloaded / total * 100) if total > 0 else 0
            speed = d.get('speed') or 0
            speed_str = f"{speed / 1024 / 1024:.2f} MB/s" if speed > 0 else "Dang tai..."

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
        'no_warnings': True,
        'http_headers': BROWSER_HEADERS
    }

    if cookie_path and isinstance(cookie_path, str) and os.path.exists(cookie_path):
        base_opts['cookiefile'] = cookie_path

    if sub_lang:
        base_opts['writesubtitles'] = True
        base_opts['writeautomaticsub'] = True
        base_opts['subtitleslangs'] = [sub_lang]

    # Ở đây sử dụng đúng biến base_opts
    if "youtube.com" in target_url or "youtu.be" in target_url:
        base_opts['extractor_args'] = {'youtube': {'player_client': ['mweb', 'android', 'web']}}

    # Xu ly Playlist
    if download_playlist:
        playlist_dir = os.path.join(output_dir, "playlist_temp")
        if os.path.exists(playlist_dir):
            shutil.rmtree(playlist_dir, ignore_errors=True)
        os.makedirs(playlist_dir, exist_ok=True)

        opts = dict(base_opts)
        opts['outtmpl'] = os.path.join(playlist_dir, '%(autonumber)02d - %(title).100s.%(ext)s')
        opts['ignoreerrors'] = True

        if format_type == "audio":
            opts['format'] = 'bestaudio[ext=m4a]/bestaudio/best'
        else:
            opts['format'] = f'best[height<={height}][ext=mp4]/best[height<={height}]/best[ext=mp4]/best/bestaudio[ext=m4a]/bestaudio'

        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download([target_url])

        valid_media_extensions = {'m4a', 'mp3', 'opus', 'aac', 'ogg', 'wav', 'flac', 'mp4', 'webm', 'mkv', '3gp'}
        downloaded_media = []
        for root, _, filenames in os.walk(playlist_dir):
            for f in filenames:
                ext = os.path.splitext(f)[1].lower().lstrip('.')
                if ext in valid_media_extensions and not f.endswith('.part') and not f.endswith('.ytdl'):
                    downloaded_media.append(f)

        if not downloaded_media:
            return json.dumps({
                "status": "error",
                "error": "Khong the tai bat ky bai hat nao tu Playlist. Co the danh sach bi chan hoac can Cookie."
            })

        if format_type == "audio":
            is_audio_result = True
        else:
            audio_extensions = {'m4a', 'mp3', 'opus', 'aac', 'ogg', 'wav', 'flac', 'webm'}
            is_audio_result = all(os.path.splitext(f)[1].lower().lstrip('.') in audio_extensions for f in downloaded_media)

        return json.dumps({
            "status": "playlist_completed",
            "is_audio": is_audio_result,
            "playlist_dir": playlist_dir
        })

    # Xu ly Audio don le
    if format_type == "audio":
        audio_path = os.path.join(output_dir, "audio_stream.m4a")
        if os.path.exists(audio_path):
            os.remove(audio_path)

        opts = dict(base_opts)
        opts['format'] = 'bestaudio[ext=m4a]/bestaudio/best'
        opts['outtmpl'] = audio_path

        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(target_url, download=True)
            thumb_url = info.get('thumbnail') or ""
            embed_metadata_to_m4a(audio_path, info.get('title'), info.get('uploader'), thumb_url)

        return json.dumps({"status": "ready", "is_audio": True, "file_path": audio_path, "sub_path": ""})

    # Xu ly Video don le
    video_path = os.path.join(output_dir, "video_stream.mp4")
    audio_path = os.path.join(output_dir, "audio_stream.m4a")
    for p in (video_path, audio_path):
        if os.path.exists(p):
            os.remove(p)

    for old_sub in glob.glob(os.path.join(output_dir, "*.vtt")):
        try: os.remove(old_sub)
        except Exception: pass

    opts_prog = dict(base_opts)
    opts_prog['format'] = f'best[height<={height}][ext=mp4]/best[height<={height}]/best'
    opts_prog['outtmpl'] = video_path

    is_progressive = False
    try:
        with yt_dlp.YoutubeDL(opts_prog) as ydl:
            info = ydl.extract_info(target_url, download=True)
            if info.get('vcodec') != 'none' and info.get('acodec') != 'none':
                is_progressive = True
    except Exception:
        is_progressive = False

    sub_files = glob.glob(os.path.join(output_dir, "*.vtt"))
    found_sub_path = sub_files[0] if sub_files else ""

    if is_progressive:
        return json.dumps({"status": "ready", "is_audio": False, "file_path": video_path, "sub_path": found_sub_path})

    opts_v = dict(base_opts)
    opts_v['format'] = f'bestvideo[height<={height}][ext=mp4]/bestvideo[height<={height}]/bestvideo/best'
    opts_v['outtmpl'] = video_path
    with yt_dlp.YoutubeDL(opts_v) as ydl:
        ydl.download([target_url])

    opts_a = dict(base_opts)
    opts_a['format'] = 'bestaudio[ext=m4a]/bestaudio/best'
    opts_a['outtmpl'] = audio_path
    with yt_dlp.YoutubeDL(opts_a) as ydl:
        ydl.download([target_url])

    sub_files = glob.glob(os.path.join(output_dir, "*.vtt"))
    found_sub_path = sub_files[0] if sub_files else ""

    return json.dumps({
        "status": "mux_required",
        "is_audio": False,
        "video_path": video_path,
        "audio_path": audio_path,
        "sub_path": found_sub_path
    })