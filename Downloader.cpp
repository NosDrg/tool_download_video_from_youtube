#include "Downloader.h"
#include <shlobj.h>
#include <thread>
#include <sstream>
#include <regex>
#include <algorithm>

std::vector<DownloadTask> g_queue;
std::mutex g_queueMutex;
int g_nextTaskId = 1;

std::atomic<bool> g_isWorkerRunning(false);
std::atomic<bool> g_stopRequested(false);
std::atomic<int> g_currentDownloadingId(-1);

static HANDLE g_hCurrentProcess = NULL;
static std::mutex g_procMutex;

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
    std::lock_guard<std::mutex> lock(g_queueMutex);
    while (std::getline(ss, line)) {
        line.erase(0, line.find_first_not_of(" \t\r\n"));
        line.erase(line.find_last_not_of(" \t\r\n") + 1);
        if (!line.empty() && line.rfind("http", 0) == 0) {
            DownloadTask task;
            task.id = g_nextTaskId++;
            task.url = line;
            task.type = type;
            task.resIndex = resIndex;
            task.status = TaskStatus::Queued;
            g_queue.push_back(task);
        }
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