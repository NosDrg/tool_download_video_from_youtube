#include "Downloader.h"
#include <fcntl.h>
#include <cstring>
#include <thread>
#include <sstream>
#include <regex>
#include <algorithm>
#include <iomanip>
#include <unistd.h>
#include <signal.h>
#include <sys/wait.h>

std::vector<DownloadTask> g_queue;
std::mutex g_queueMutex;
int g_nextTaskId = 1;

std::atomic<bool> g_isWorkerRunning(false);
std::atomic<bool> g_stopRequested(false);
std::atomic<int> g_currentDownloadingId(-1);

static pid_t g_currentPid = -1; 
static std::mutex g_procMutex;

std::atomic<bool> g_isUpdatingYtDlp(false);
std::mutex g_updateMutex;
std::string g_updateStatusMsg = "";

const char* g_resNames[] = {
    "Tốt nhất (Gốc)",
    "4K (2160p)",
    "2K (1440p)",
    "Full HD (1080p)",
    "HD (720p)",
    "SD (480p)"
};

const char* g_formatNames[] = {
    "MP4 (Video)",
    "MKV (Video)",
    "WEBM (Video)",
    "MP3 (Audio)",
    "WAV (Audio Lossless)",
    "FLAC (Audio Lossless)",
    "M4A (Audio)"
};
const int g_formatCount = 7;

void BrowseDestinationFolder(char* outPath, size_t maxLen) {
    FILE* fp = popen("zenity --file-selection --directory --title=\"Chọn thư mục lưu trữ\" 2>/dev/null", "r");
    if (fp) {
        char buffer[MAX_PATH];
        if (fgets(buffer, sizeof(buffer), fp) != NULL) {
            std::string path = buffer;
            path.erase(path.find_last_not_of(" \t\r\n") + 1);
            if (!path.empty()) {
                strncpy(outPath, path.c_str(), maxLen);
            }
        }
        pclose(fp);
    }
}

void AbortCurrentDownload() {
    g_stopRequested = true;
    std::lock_guard<std::mutex> lock(g_procMutex);
    if (g_currentPid > 0) {
        kill(g_currentPid, SIGTERM);
        g_currentPid = -1;
    }
}

static std::string FormatSecondsToTime(int totalSeconds) {
    if (totalSeconds <= 0) return "--:--";
    int hours = totalSeconds / 3600;
    int minutes = (totalSeconds % 3600) / 60;
    int seconds = totalSeconds % 60;
    std::ostringstream oss;
    if (hours > 0) {
        oss << hours << ":" << std::setfill('0') << std::setw(2) << minutes << ":" << std::setw(2) << seconds;
    } else {
        oss << std::setfill('0') << std::setw(2) << minutes << ":" << std::setw(2) << seconds;
    }
    return oss.str();
}

static std::string DecodeUnicodeEscape(const std::string& input) {
    std::string output = "";
    size_t i = 0;
    while (i < input.length()) {
        if (input[i] == '\\' && i + 5 < input.length() && input[i + 1] == 'u') {
            std::string hexStr = input.substr(i + 2, 4);
            char* endPtr = nullptr;
            unsigned long code = strtoul(hexStr.c_str(), &endPtr, 16);
            if (endPtr != hexStr.c_str()) {
                if (code <= 0x7F) {
                    output += static_cast<char>(code);
                } else if (code <= 0x7FF) {
                    output += static_cast<char>(0xC0 | ((code >> 6) & 0x1F));
                    output += static_cast<char>(0x80 | (code & 0x3F));
                } else {
                    output += static_cast<char>(0xE0 | ((code >> 12) & 0x0F));
                    output += static_cast<char>(0x80 | ((code >> 6) & 0x3F));
                    output += static_cast<char>(0x80 | (code & 0x3F));
                }
                i += 6;
                continue;
            }
        }
        output += input[i++];
    }
    return output;
}

static void FetchMetadataWorker(int taskId, std::string url, bool allowPlaylist) {
    std::string cmd = "yt-dlp --encoding utf-8 --skip-download ";
    if (allowPlaylist) {
        cmd += "--flat-playlist --dump-single-json \"" + url + "\" 2>/dev/null";
    } else {
        cmd += "--no-playlist --dump-json \"" + url + "\" 2>/dev/null";
    }

    FILE* fp = popen(cmd.c_str(), "r");
    if (!fp) return;

    std::string jsonOutput = "";
    char buffer[2048];
    while (fgets(buffer, sizeof(buffer), fp)) {
        jsonOutput += buffer;
        if (allowPlaylist && jsonOutput.find('\n') != std::string::npos) break;
    }
    pclose(fp);

    if (jsonOutput.empty()) return;

    std::string extractedTitle = "";
    std::string extractedDuration = allowPlaylist ? "Playlist" : "--:--";

    std::regex titleRegex(R"raw("title"\s*:\s*"((?:\\.|[^"\\])*)")raw");
    std::smatch titleMatch;
    if (std::regex_search(jsonOutput, titleMatch, titleRegex)) {
        extractedTitle = titleMatch[1].str();
        size_t p;
        while ((p = extractedTitle.find("\\\"")) != std::string::npos) extractedTitle.replace(p, 2, "\"");
        while ((p = extractedTitle.find("\\\\")) != std::string::npos) extractedTitle.replace(p, 2, "\\");
        while ((p = extractedTitle.find("\\/")) != std::string::npos) extractedTitle.replace(p, 2, "/");
        extractedTitle = DecodeUnicodeEscape(extractedTitle);
    }

    if (allowPlaylist) {
        std::regex countRegex(R"raw("playlist_count"\s*:\s*(\d+))raw");
        std::smatch countMatch;
        if (std::regex_search(jsonOutput, countMatch, countRegex)) {
            extractedDuration = countMatch[1].str() + " videos";
        }
    } else {
        std::regex durationRegex(R"raw("duration"\s*:\s*(\d+(?:\.\d+)?))raw");
        std::smatch durMatch;
        if (std::regex_search(jsonOutput, durMatch, durationRegex)) {
            try {
                int secs = static_cast<int>(std::stof(durMatch[1].str()));
                extractedDuration = FormatSecondsToTime(secs);
            } catch (...) {}
        }
    }

    if (!extractedTitle.empty()) {
        std::lock_guard<std::mutex> lock(g_queueMutex);
        for (auto& item : g_queue) {
            if (item.id == taskId) {
                item.title = extractedTitle;
                item.duration = extractedDuration;
                break;
            }
        }
    }
}

static std::string GetFormatString(int type, int resIndex) {
    // 0: MP4, 1: MKV, 2: WEBM
    // 3: MP3, 4: WAV, 5: FLAC, 6: M4A
    
    // Xử lý Audio
    if (type >= 3) {
        std::string audioExt = "mp3";
        switch (type) {
            case 3: audioExt = "mp3"; break;
            case 4: audioExt = "wav"; break;
            case 5: audioExt = "flac"; break;
            case 6: audioExt = "m4a"; break;
        }
        return "-x --audio-format " + audioExt + " ";
    }

    // Xử lý Video (MP4, MKV, WEBM)
    std::string container = (type == 1) ? "mkv" : ((type == 2) ? "webm" : "mp4");
    std::string heightLimit = "";
    switch (resIndex) {
        case 1: heightLimit = "2160"; break;
        case 2: heightLimit = "1440"; break;
        case 3: heightLimit = "1080"; break;
        case 4: heightLimit = "720";  break;
        case 5: heightLimit = "480";  break;
        default: break;
    }

    if (!heightLimit.empty()) {
        return "-f \"bestvideo[height<=" + heightLimit + "]+bestaudio/best[height<=" + heightLimit + "]\" --merge-output-format " + container + " ";
    }
    return "-f bestvideo*+bestaudio/best --merge-output-format " + container + " ";
}

static bool ExecuteSingleDownload(DownloadTask& task, const std::string& savePath, bool useArchive) {
    int pipefd[2];
    if (pipe(pipefd) == -1) return false;

    // Đổi tên binary thành yt-dlp (không có .exe) và gạch chéo chuẩn /
    std::string cmd = "./yt-dlp --newline -P \"" + savePath + "\" ";
    if (!task.allowPlaylist) cmd += "--no-playlist ";
    if (useArchive) cmd += "--download-archive \"" + savePath + "/archive.txt\" ";
    cmd += GetFormatString(task.type, task.resIndex);
    cmd += "-o \"%(title)s.%(ext)s\" \"" + task.url + "\"";

    pid_t pid = fork();
    if (pid == 0) { // Tiến trình con
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);

        execl("/bin/sh", "sh", "-c", cmd.c_str(), (char*)NULL);
        _exit(1);
    }

    // Tiến trình cha
    close(pipefd[1]);
    {
        std::lock_guard<std::mutex> lock(g_procMutex);
        g_currentPid = pid;
    }

    FILE* stream = fdopen(pipefd[0], "r");
    char buffer[512];
    std::regex progressRegex(R"(\[download\]\s+(\d+\.\d+)%\s+of\s+[~]?([^\s]+)\s+at\s+([^\s]+)\s+ETA\s+([^\s]+))");
    std::regex simplePercentRegex(R"(\[download\]\s+(\d+\.\d+)%)");
    std::regex archiveRegex(R"(\[download\]\s+.*has already been recorded in the archive)");

    if (stream) {
        while (fgets(buffer, sizeof(buffer), stream)) {
            std::string line(buffer);
            std::smatch match;

            if (std::regex_search(line, archiveRegex)) {
                std::lock_guard<std::mutex> lock(g_queueMutex);
                task.progress = 1.0f;
                task.speed = "Đã có";
                task.eta = "Archive";
            } else if (std::regex_search(line, match, progressRegex)) {
                try {
                    float p = std::stof(match[1].str()) / 100.0f;
                    std::lock_guard<std::mutex> lock(g_queueMutex);
                    task.progress = p;
                    task.speed = match[3].str();
                    task.eta = match[4].str();
                } catch (...) {}
            } else if (std::regex_search(line, match, simplePercentRegex)) {
                try {
                    float p = std::stof(match[1].str()) / 100.0f;
                    std::lock_guard<std::mutex> lock(g_queueMutex);
                    task.progress = p;
                } catch (...) {}
            }
        }
        fclose(stream);
    }

    int status = 0;
    waitpid(pid, &status, 0);

    {
        std::lock_guard<std::mutex> lock(g_procMutex);
        g_currentPid = -1;
    }

    return (WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

static void QueueWorkerLoop(std::string savePath, bool useArchive) {
    while (!g_stopRequested.load()) {
        int targetIndex = -1;
        {
            std::lock_guard<std::mutex> lock(g_queueMutex);
            for (size_t i = 0; i < g_queue.size(); i++) {
                if (g_queue[i].status == TaskStatus::Queued) {
                    targetIndex = static_cast<int>(i);
                    g_queue[i].status = TaskStatus::Downloading;
                    g_currentDownloadingId = g_queue[i].id;
                    break;
                }
            }
        }

        if (targetIndex == -1) break;

        DownloadTask currentTaskCopy;
        {
            std::lock_guard<std::mutex> lock(g_queueMutex);
            currentTaskCopy = g_queue[targetIndex];
        }

        bool success = ExecuteSingleDownload(currentTaskCopy, savePath, useArchive);

        {
            std::lock_guard<std::mutex> lock(g_queueMutex);
            if (targetIndex < (int)g_queue.size() && g_queue[targetIndex].id == currentTaskCopy.id) {
                if (g_stopRequested.load()) {
                    g_queue[targetIndex].status = TaskStatus::Stopped;
                    g_queue[targetIndex].speed = "--";
                    g_queue[targetIndex].eta = "--";
                } else if (success) {
                    g_queue[targetIndex].status = TaskStatus::Completed;
                    g_queue[targetIndex].progress = 1.0f;
                    if (g_queue[targetIndex].eta != "Archive") {
                        g_queue[targetIndex].speed = "Xong";
                        g_queue[targetIndex].eta = "00:00";
                    }
                } else {
                    g_queue[targetIndex].status = TaskStatus::Failed;
                    g_queue[targetIndex].speed = "--";
                    g_queue[targetIndex].eta = "--";
                }
            }
        }

        if (g_stopRequested.load()) break;
    }

    g_currentDownloadingId = -1;
    g_isWorkerRunning = false;
    g_stopRequested = false;
}

void StartQueueWorker(const std::string& savePath, bool useArchive) {
    g_isWorkerRunning = true;
    g_stopRequested = false;
    std::thread(QueueWorkerLoop, savePath, useArchive).detach();
}

void EnqueueUrls(const std::string& multiUrlText, int type, int resIndex, bool allowPlaylist) {
    std::stringstream ss(multiUrlText);
    std::string line;
    std::vector<std::pair<int, std::string>> tasksToFetch;

    {
        std::lock_guard<std::mutex> lock(g_queueMutex);
        while (std::getline(ss, line)) {
            line.erase(0, line.find_first_not_of(" \t\r\n"));
            line.erase(line.find_last_not_of(" \t\r\n") + 1);
            if (!line.empty() && line.rfind("http", 0) == 0) {
                DownloadTask task;
                task.id = g_nextTaskId++;
                task.url = line;
                task.title = "Đang lấy thông tin...";
                task.duration = allowPlaylist ? "Playlist" : "--:--";
                task.type = type;
                task.resIndex = resIndex;
                task.allowPlaylist = allowPlaylist;
                task.status = TaskStatus::Queued;
                g_queue.push_back(task);
                tasksToFetch.push_back({task.id, task.url});
            }
        }
    }

    for (const auto& item : tasksToFetch) {
        std::thread(FetchMetadataWorker, item.first, item.second, allowPlaylist).detach();
    }
}

void ClearCompletedTasks() {
    std::lock_guard<std::mutex> lock(g_queueMutex);
    g_queue.erase(
        std::remove_if(g_queue.begin(), g_queue.end(), [](const DownloadTask& t) {
            return t.status == TaskStatus::Completed;
        }),
        g_queue.end()
    );
}

void ClearAllTasks() {
    std::lock_guard<std::mutex> lock(g_queueMutex);
    g_queue.clear();
}

void RetryTask(int taskId) {
    std::string urlToRetry = "";
    bool allowPl = false;
    {
        std::lock_guard<std::mutex> lock(g_queueMutex);
        for (auto& item : g_queue) {
            if (item.id == taskId) {
                item.status = TaskStatus::Queued;
                item.progress = 0.0f;
                item.speed = "--";
                item.eta = "--";
                allowPl = item.allowPlaylist;
                if (item.title == "Đang lấy thông tin..." || item.title.empty()) {
                    urlToRetry = item.url;
                }
                break;
            }
        }
    }
    if (!urlToRetry.empty()) {
        std::thread(FetchMetadataWorker, taskId, urlToRetry, allowPl).detach();
    }
}

void DeleteTask(int taskId) {
    std::lock_guard<std::mutex> lock(g_queueMutex);
    g_queue.erase(
        std::remove_if(g_queue.begin(), g_queue.end(), [taskId](const DownloadTask& t) {
            return t.id == taskId && t.status != TaskStatus::Downloading;
        }),
        g_queue.end()
    );
}

static void UpdateWorkerThread() {
    std::string cmd = "yt-dlp -U 2>&1";
    FILE* fp = popen(cmd.c_str(), "r");
    if (!fp) {
        std::lock_guard<std::mutex> lock(g_updateMutex);
        g_updateStatusMsg = "Không tìm thấy yt-dlp hoặc lỗi thực thi.";
        g_isUpdatingYtDlp = false;
        return;
    }

    char buffer[512];
    std::string output = "";
    while (fgets(buffer, sizeof(buffer), fp)) {
        output += buffer;
    }

    int exitCode = pclose(fp);

    std::lock_guard<std::mutex> lock(g_updateMutex);
    if (exitCode == 0) {
        if (output.find("is up to date") != std::string::npos) {
            g_updateStatusMsg = "yt-dlp đang ở bản mới nhất.";
        } else {
            g_updateStatusMsg = "Đã cập nhật yt-dlp thành công!";
        }
    } else {
        g_updateStatusMsg = "Cập nhật thất bại. Kiểm tra kết nối mạng.";
    }
    g_isUpdatingYtDlp = false;
}

void UpdateYtDlpAsync() {
    if (g_isUpdatingYtDlp.load()) return;
    g_isUpdatingYtDlp = true;
    {
        std::lock_guard<std::mutex> lock(g_updateMutex);
        g_updateStatusMsg = "Đang kiểm tra và cập nhật...";
    }
    std::thread(UpdateWorkerThread).detach();
}