#include "UI.h"
#include "Downloader.h"
#include "imgui/imgui.h"
#include <shellapi.h>

static char g_savePathBuf[MAX_PATH] = "C:\\Downloads";
static char g_multiUrlBuf[16384] = "";
static int g_selectedType = 0;
static int g_selectedRes = 0;
static bool g_useArchive = true;

void InitUI() {
    CreateDirectoryA(g_savePathBuf, NULL);
}

void RenderDownloaderUI(HWND hWnd, int windowWidth, int windowHeight) {
    ImGui::SetNextWindowPos(ImVec2(0, 0));
    ImGui::SetNextWindowSize(ImVec2((float)windowWidth, (float)windowHeight));
    ImGuiWindowFlags windowFlags = ImGuiWindowFlags_NoTitleBar | 
                                  ImGuiWindowFlags_NoResize | 
                                  ImGuiWindowFlags_NoMove | 
                                  ImGuiWindowFlags_NoCollapse;

    ImGui::Begin("MainPanel", nullptr, windowFlags);

    // Tiêu đề & Nút Cập nhật Core
    ImGui::TextColored(ImVec4(0.2f, 0.8f, 1.0f, 1.0f), u8"BỘ CÔNG CỤ TẢI MEDIA TỰ ĐỘNG (DIRECTX 12)");
    ImGui::SameLine(ImGui::GetWindowWidth() - 320);

    if (g_isUpdatingYtDlp.load()) {
        ImGui::BeginDisabled();
        ImGui::Button(u8"Đang cập nhật...", ImVec2(140, 24));
        ImGui::EndDisabled();
    } else {
        if (ImGui::Button(u8"Cập nhật yt-dlp", ImVec2(140, 24))) {
            UpdateYtDlpAsync();
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_updateMutex);
        if (!g_updateStatusMsg.empty()) {
            ImGui::SameLine();
            ImGui::TextColored(ImVec4(0.9f, 0.8f, 0.2f, 1.0f), "%s", g_updateStatusMsg.c_str());
        }
    }

    ImGui::Separator();

    // 1. Thư mục lưu
    ImGui::Text(u8"Thư mục lưu:");
    ImGui::SameLine();
    ImGui::PushItemWidth(580);
    ImGui::InputText("##PathInput", g_savePathBuf, MAX_PATH);
    ImGui::PopItemWidth();
    ImGui::SameLine();
    if (ImGui::Button(u8"Duyệt...")) {
        BrowseDestinationFolder(g_savePathBuf, MAX_PATH);
    }
    ImGui::SameLine();
    if (ImGui::Button(u8"Mở thư mục")) {
        ShellExecuteA(NULL, "open", g_savePathBuf, NULL, NULL, SW_SHOWDEFAULT);
    }

    // 2. Ô nhập nhiều link
    ImGui::Text(u8"Dán danh sách liên kết tại đây (mỗi link trên 1 dòng):");
    ImGui::InputTextMultiline("##MultiUrl", g_multiUrlBuf, IM_ARRAYSIZE(g_multiUrlBuf), ImVec2(-1, 80));

    // 3. Tùy chọn định dạng & độ phân giải
    ImGui::Text(u8"Định dạng:");
    ImGui::SameLine();
    ImGui::RadioButton(u8"Video MP4", &g_selectedType, 0);
    ImGui::SameLine();
    ImGui::RadioButton(u8"Audio MP3", &g_selectedType, 1);

    if (g_selectedType == 0) {
        ImGui::SameLine(280);
        ImGui::Text(u8"Chất lượng:");
        ImGui::SameLine();
        ImGui::PushItemWidth(170);
        ImGui::Combo("##ResCombo", &g_selectedRes, g_resNames, 6);
        ImGui::PopItemWidth();
    }

    ImGui::SameLine(600);
    if (ImGui::Button(u8"Thêm vào Hàng đợi", ImVec2(160, 26))) {
        EnqueueUrls(g_multiUrlBuf, g_selectedType, g_selectedRes);
        g_multiUrlBuf[0] = '\0';
    }
    ImGui::SameLine();
    if (ImGui::Button(u8"Xóa trắng ô", ImVec2(120, 26))) {
        g_multiUrlBuf[0] = '\0';
    }

    // 4. Checkbox tùy chọn
    ImGui::Spacing();
    ImGui::Checkbox(u8"Chống tải trùng lặp (archive.txt)", &g_useArchive);
    ImGui::Separator();

    // 5. Nút điều phối hàng đợi
    if (g_isWorkerRunning.load()) {
        ImGui::BeginDisabled();
        ImGui::Button(u8"Đang xử lý...", ImVec2(140, 32));
        ImGui::EndDisabled();

        ImGui::SameLine();
        if (ImGui::Button(u8"Dừng hàng đợi", ImVec2(140, 32))) {
            AbortCurrentDownload();
        }
    } else {
        if (ImGui::Button(u8"Bắt đầu tải hết", ImVec2(140, 32))) {
            StartQueueWorker(g_savePathBuf, g_useArchive);
        }
        ImGui::SameLine();
        if (ImGui::Button(u8"Xóa mục hoàn tất", ImVec2(160, 32))) {
            ClearCompletedTasks();
        }
        ImGui::SameLine();
        if (ImGui::Button(u8"Xóa tất cả", ImVec2(110, 32))) {
            ClearAllTasks();
        }
    }

    ImGui::SameLine(ImGui::GetWindowWidth() - 150);
    if (ImGui::Button(u8"Thoát phần mềm", ImVec2(130, 32))) {
        if (g_isWorkerRunning.load()) {
            AbortCurrentDownload();
        }
        PostMessage(hWnd, WM_CLOSE, 0, 0);
    }

    ImGui::Spacing();

    // 6. Bảng danh sách hàng đợi (Cột Tiêu đề & Thời lượng)
    ImGuiTableFlags flags = ImGuiTableFlags_Borders | ImGuiTableFlags_RowBg | ImGuiTableFlags_ScrollY;
    if (ImGui::BeginTable("DownloadQueueTable", 7, flags, ImVec2(0, -1))) {
        ImGui::TableSetupColumn("ID", ImGuiTableColumnFlags_WidthFixed, 35.0f);
        ImGui::TableSetupColumn(u8"Định dạng", ImGuiTableColumnFlags_WidthFixed, 150.0f);
        ImGui::TableSetupColumn(u8"Tiêu đề Video / Thời lượng", ImGuiTableColumnFlags_WidthStretch);
        ImGui::TableSetupColumn(u8"Tiến độ", ImGuiTableColumnFlags_WidthFixed, 130.0f);
        ImGui::TableSetupColumn(u8"Tốc độ / ETA", ImGuiTableColumnFlags_WidthFixed, 130.0f);
        ImGui::TableSetupColumn(u8"Trạng thái", ImGuiTableColumnFlags_WidthFixed, 100.0f);
        ImGui::TableSetupColumn(u8"Thao tác", ImGuiTableColumnFlags_WidthFixed, 120.0f);
        ImGui::TableHeadersRow();

        std::vector<DownloadTask> queueCopy;
        {
            std::lock_guard<std::mutex> lock(g_queueMutex);
            queueCopy = g_queue;
        }

        for (const auto& item : queueCopy) {
            ImGui::TableNextRow();
            ImGui::PushID(item.id);

            // Cột 0: ID
            ImGui::TableSetColumnIndex(0);
            ImGui::Text("%d", item.id);

            // Cột 1: Định dạng / Độ phân giải
            ImGui::TableSetColumnIndex(1);
            if (item.type == 1) {
                ImGui::Text("Audio MP3");
            } else {
                ImGui::Text("MP4 | %s", g_resNames[item.resIndex]);
            }

            // Cột 2: Tiêu đề video + Thời lượng (kèm Tooltip hiển thị URL khi hover)
            ImGui::TableSetColumnIndex(2);
            if (item.title == "Đang lấy thông tin...") {
                ImGui::TextColored(ImVec4(0.6f, 0.6f, 0.6f, 1.0f), "%s", item.title.c_str());
            } else {
                ImGui::Text("%s [%s]", item.title.c_str(), item.duration.c_str());
            }
            if (ImGui::IsItemHovered()) {
                ImGui::SetTooltip("%s", item.url.c_str());
            }

            // Cột 3: Progress Bar mini
            ImGui::TableSetColumnIndex(3);
            char progOverlay[32];
            snprintf(progOverlay, sizeof(progOverlay), "%.1f%%", item.progress * 100.0f);
            ImGui::ProgressBar(item.progress, ImVec2(-1.0f, 15.0f), progOverlay);

            // Cột 4: Tốc độ & ETA
            ImGui::TableSetColumnIndex(4);
            if (item.status == TaskStatus::Downloading || item.eta == "Archive") {
                ImGui::Text("%s | %s", item.speed.c_str(), item.eta.c_str());
            } else {
                ImGui::TextDisabled("--");
            }

            // Cột 5: Trạng thái
            ImGui::TableSetColumnIndex(5);
            switch (item.status) {
                case TaskStatus::Queued:
                    ImGui::TextColored(ImVec4(0.7f, 0.7f, 0.7f, 1.0f), u8"Chờ tải");
                    break;
                case TaskStatus::Downloading:
                    ImGui::TextColored(ImVec4(0.2f, 0.8f, 1.0f, 1.0f), u8"Đang tải...");
                    break;
                case TaskStatus::Completed:
                    if (item.eta == "Archive") {
                        ImGui::TextColored(ImVec4(0.9f, 0.8f, 0.2f, 1.0f), u8"Đã có sẵn");
                    } else {
                        ImGui::TextColored(ImVec4(0.2f, 1.0f, 0.2f, 1.0f), u8"Hoàn thành");
                    }
                    break;
                case TaskStatus::Failed:
                    ImGui::TextColored(ImVec4(1.0f, 0.3f, 0.3f, 1.0f), u8"Lỗi");
                    break;
                case TaskStatus::Stopped:
                    ImGui::TextColored(ImVec4(1.0f, 0.8f, 0.2f, 1.0f), u8"Đã dừng");
                    break;
            }

            // Cột 6: Thao tác riêng theo từng dòng
            ImGui::TableSetColumnIndex(6);
            if (item.status == TaskStatus::Failed || item.status == TaskStatus::Stopped) {
                if (ImGui::SmallButton(u8"Thử lại")) {
                    RetryTask(item.id);
                }
                ImGui::SameLine();
                if (ImGui::SmallButton(u8"Xóa")) {
                    DeleteTask(item.id);
                }
            } else if (item.status == TaskStatus::Queued) {
                if (ImGui::SmallButton(u8"Xóa")) {
                    DeleteTask(item.id);
                }
            } else if (item.status == TaskStatus::Completed) {
                if (ImGui::SmallButton(u8"Mở thư mục")) {
                    ShellExecuteA(NULL, "open", g_savePathBuf, NULL, NULL, SW_SHOWDEFAULT);
                }
            } else {
                ImGui::TextDisabled("...");
            }

            ImGui::PopID();
        }
        ImGui::EndTable();
    }

    ImGui::End();
}