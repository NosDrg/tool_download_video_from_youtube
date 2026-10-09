#include "Downloader.h"
#include <shlobj.h>
#include <thread>
#include <sstream>
#include <regex>
#include <algorithm>
#include <iomanip>

std::vector<DownloadTask> g_queue;
std::mutex g_queueMutex;
int g_nextTaskId = 1;

std::atomic<bool> g_isWorkerRunning(false);
std::atomic<bool> g_stopRequested(false);
std::atomic<int> g_currentDownloadingId(-1);

static HANDLE g_hCurrentProcess = NULL;
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

void BrowseDestinationFolder(char* outPath, size_t maxLen) {
    BROWSEINFOA bi = { 0 };
    bi.lpszTitle = "Chọn thư mục lưu trữ danh sách tải";
    bi.ulFlags = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
    LPITEMIDLIST pidl = SHBrowseForFolderA(&bi);
    if (pidl != 0) {
        char path[MAX_PATH];
        if (SHGetPathFromIDListA(pidl, path)) {
            strncpy(outPath, path, maxLen);
        }
        IMalloc* imalloc = 0;
        if (SUCCEEDED(SHGetMalloc(&imalloc))) {
            imalloc->Free(pidl);
            imalloc->Release();
        }
    }
}

void AbortCurrentDownload() {
    g_stopRequested = true;
    std::lock_guard<std::mutex> lock(g_procMutex);
    if (g_hCurrentProcess != NULL) {
        TerminateProcess(g_hCurrentProcess, 1);
        CloseHandle(g_hCurrentProcess);
        g_hCurrentProcess = NULL;
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

// Luồng ngầm fetch tiêu đề và thời lượng bằng --dump-json
static void FetchMetadataWorker(int taskId, std::string url) {
    HANDLE hReadPipe, hWritePipe;
    SECURITY_ATTRIBUTES sa = { sizeof(SECURITY_ATTRIBUTES), NULL, TRUE };
    if (!CreatePipe(&hReadPipe, &hWritePipe, &sa, 0)) return;

    STARTUPINFOA si = { sizeof(STARTUPINFOA) };
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.hStdOutput = hWritePipe;
    si.hStdError = hWritePipe;
    si.wShowWindow = SW_HIDE;

    PROCESS_INFORMATION pi = { 0 };
    std::string cmd = "yt-dlp.exe --dump-json --flat-playlist --skip-download --no-playlist \"" + url + "\"";

    if (!CreateProcessA(NULL, &cmd[0], NULL, NULL, TRUE, 0, NULL, NULL, &si, &pi)) {
        CloseHandle(hReadPipe);
        CloseHandle(hWritePipe);
        return;
    }
    CloseHandle(hWritePipe);

    std::string jsonOutput = "";
    char buffer[1024];
    DWORD bytesRead;
    while (ReadFile(hReadPipe, buffer, sizeof(buffer) - 1, &bytesRead, NULL) && bytesRead > 0) {
        buffer[bytesRead] = '\0';
        jsonOutput += buffer;
    }

    WaitForSingleObject(pi.hProcess, INFINITE);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    CloseHandle(hReadPipe);

    if (jsonOutput.empty()) return;

    std::string extractedTitle = "";
    std::string extractedDuration = "--:--";

    // Trích xuất "title": "..."
    std::regex titleRegex(R"raw("title"\s*:\s*"((?:\\.|[^"\\])*)")raw");
    std::smatch titleMatch;
    if (std::regex_search(jsonOutput, titleMatch, titleRegex)) {
        extractedTitle = titleMatch[1].str();
        // Xử lý escape escape cơ bản
        size_t p;
        while ((p = extractedTitle.find("\\\"")) != std::string::npos) extractedTitle.replace(p, 2, "\"");
        while ((p = extractedTitle.find("\\\\")) != std::string::npos) extractedTitle.replace(p, 2, "\\");
    }

    // Trích xuất "duration": 123 hoặc "duration": 123.0
    std::regex durationRegex(R"raw("duration"\s*:\s*(\d+(?:\.\d+)?))raw");
    std::smatch durMatch;
    if (std::regex_search(jsonOutput, durMatch, durationRegex)) {
        try {
            int secs = static_cast<int>(std::stof(durMatch[1].str()));
            extractedDuration = FormatSecondsToTime(secs);
        } catch (...) {}
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
    if (type == 1) {
        return "-x --audio-format mp3 ";
    }

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
        return "-f \"bestvideo[height<=" + heightLimit + "]+bestaudio/best[height<=" + heightLimit + "]\" --merge-output-format mp4 ";
    }
    return "-f bestvideo*+bestaudio/best --merge-output-format mp4 ";
}

static bool ExecuteSingleDownload(DownloadTask& task, const std::string& savePath, bool useArchive) {
    HANDLE hReadPipe, hWritePipe;
    SECURITY_ATTRIBUTES sa = { sizeof(SECURITY_ATTRIBUTES), NULL, TRUE };
    if (!CreatePipe(&hReadPipe, &hWritePipe, &sa, 0)) return false;

    STARTUPINFOA si = { sizeof(STARTUPINFOA) };
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.hStdOutput = hWritePipe;
    si.hStdError = hWritePipe;
    si.wShowWindow = SW_HIDE;

    PROCESS_INFORMATION pi = { 0 };

    std::string cmd = "yt-dlp.exe --newline --no-playlist -P \"" + savePath + "\" ";
    if (useArchive) {
        cmd += "--download-archive \"" + savePath + "\\archive.txt\" ";
    }
    cmd += GetFormatString(task.type, task.resIndex);
    cmd += "-o \"%(title)s.%(ext)s\" \"" + task.url + "\"";

    if (!CreateProcessA(NULL, &cmd[0], NULL, NULL, TRUE, 0, NULL, NULL, &si, &pi)) {
        CloseHandle(hReadPipe);
        CloseHandle(hWritePipe);
        return false;
    }
    CloseHandle(hWritePipe);

    {
        std::lock_guard<std::mutex> lock(g_procMutex);
        g_hCurrentProcess = pi.hProcess;
    }

    char buffer[512];
    DWORD bytesRead;
    std::string logLine = "";
    std::regex progressRegex(R"(\[download\]\s+(\d+\.\d+)%\s+of\s+[~]?([^\s]+)\s+at\s+([^\s]+)\s+ETA\s+([^\s]+))");
    std::regex simplePercentRegex(R"(\[download\]\s+(\d+\.\d+)%)");
    std::regex archiveRegex(R"(\[download\]\s+.*has already been recorded in the archive)");

    while (ReadFile(hReadPipe, buffer, sizeof(buffer) - 1, &bytesRead, NULL) && bytesRead > 0) {
        buffer[bytesRead] = '\0';
        logLine += buffer;
        size_t pos;
        while ((pos = logLine.find('\n')) != std::string::npos) {
            std::string line = logLine.substr(0, pos);
            logLine.erase(0, pos + 1);

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
    }

    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD exitCode;
    GetExitCodeProcess(pi.hProcess, &exitCode);

    {
        std::lock_guard<std::mutex> lock(g_procMutex);
        if (g_hCurrentProcess != NULL) {
            CloseHandle(g_hCurrentProcess);
            g_hCurrentProcess = NULL;
        }
    }
    CloseHandle(pi.hThread);
    CloseHandle(hReadPipe);

    return (exitCode == 0);
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

void EnqueueUrls(const std::string& multiUrlText, int type, int resIndex) {
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
                task.duration = "--:--";
                task.type = type;
                task.resIndex = resIndex;
                task.status = TaskStatus::Queued;
                g_queue.push_back(task);
                tasksToFetch.push_back({task.id, task.url});
            }
        }
    }

    // Khởi chạy các luồng tách biệt để fetch metadata ngầm mà không giật lag UI
    for (const auto& item : tasksToFetch) {
        std::thread(FetchMetadataWorker, item.first, item.second).detach();
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
    {
        std::lock_guard<std::mutex> lock(g_queueMutex);
        for (auto& item : g_queue) {
            if (item.id == taskId) {
                item.status = TaskStatus::Queued;
                item.progress = 0.0f;
                item.speed = "--";
                item.eta = "--";
                if (item.title == "Đang lấy thông tin..." || item.title.empty()) {
                    urlToRetry = item.url;
                }
                break;
            }
        }
    }
    if (!urlToRetry.empty()) {
        std::thread(FetchMetadataWorker, taskId, urlToRetry).detach();
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
    HANDLE hReadPipe, hWritePipe;
    SECURITY_ATTRIBUTES sa = { sizeof(SECURITY_ATTRIBUTES), NULL, TRUE };
    if (!CreatePipe(&hReadPipe, &hWritePipe, &sa, 0)) {
        std::lock_guard<std::mutex> lock(g_updateMutex);
        g_updateStatusMsg = "Lỗi khởi tạo Pipe cập nhật.";
        g_isUpdatingYtDlp = false;
        return;
    }

    STARTUPINFOA si = { sizeof(STARTUPINFOA) };
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.hStdOutput = hWritePipe;
    si.hStdError = hWritePipe;
    si.wShowWindow = SW_HIDE;

    PROCESS_INFORMATION pi = { 0 };
    std::string cmd = "yt-dlp.exe -U";

    if (!CreateProcessA(NULL, &cmd[0], NULL, NULL, TRUE, 0, NULL, NULL, &si, &pi)) {
        std::lock_guard<std::mutex> lock(g_updateMutex);
        g_updateStatusMsg = "Không tìm thấy file yt-dlp.exe để cập nhật.";
        CloseHandle(hReadPipe);
        CloseHandle(hWritePipe);
        g_isUpdatingYtDlp = false;
        return;
    }
    CloseHandle(hWritePipe);

    char buffer[512];
    DWORD bytesRead;
    std::string output = "";

    while (ReadFile(hReadPipe, buffer, sizeof(buffer) - 1, &bytesRead, NULL) && bytesRead > 0) {
        buffer[bytesRead] = '\0';
        output += buffer;
    }

    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD exitCode;
    GetExitCodeProcess(pi.hProcess, &exitCode);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    CloseHandle(hReadPipe);

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