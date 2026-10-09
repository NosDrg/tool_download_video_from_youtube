#pragma once
#include <windows.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>

enum class TaskStatus { Queued, Downloading, Completed, Failed, Stopped };

struct DownloadTask {
    int id;
    std::string url;
    std::string title = "Đang lấy thông tin...";
    std::string duration = "--:--";
    int type;       // 0: MP4, 1: MP3
    int resIndex;   // 0: Best, 1: 4K, 2: 2K, 3: 1080p, 4: 720p, 5: 480p
    bool allowPlaylist = false; // Cho phép tải toàn bộ playlist
    float progress = 0.0f;
    std::string speed = "--";
    std::string eta = "--";
    TaskStatus status = TaskStatus::Queued;
};

extern std::vector<DownloadTask> g_queue;
extern std::mutex g_queueMutex;
extern int g_nextTaskId;
extern std::atomic<bool> g_isWorkerRunning;
extern std::atomic<bool> g_stopRequested;
extern const char* g_resNames[];
extern const char* g_formatNames[];
extern const int g_formatCount;

// Quản lý cập nhật yt-dlp
extern std::atomic<bool> g_isUpdatingYtDlp;
extern std::mutex g_updateMutex;
extern std::string g_updateStatusMsg;

void EnqueueUrls(const std::string& multiUrlText, int type, int resIndex, bool allowPlaylist);
void StartQueueWorker(const std::string& savePath, bool useArchive);
void AbortCurrentDownload();
void ClearCompletedTasks();
void ClearAllTasks();
void RetryTask(int taskId);
void DeleteTask(int taskId);
void UpdateYtDlpAsync();
void BrowseDestinationFolder(char* outPath, size_t maxLen);