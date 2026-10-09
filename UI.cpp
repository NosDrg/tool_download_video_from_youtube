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

    ImGui::TextColored(ImVec4(0.2f, 0.8f, 1.0f, 1.0f), u8"BỘ CÔNG CỤ TẢI MEDIA TỰ ĐỘNG (DIRECTX 12)");
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
    ImGui::InputTextMultiline("##MultiUrl", g_multiUrlBuf, IM_ARRAYSIZE(g_multiUrlBuf), ImVec2(-1, 85));

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

    // 5. Nút điều phối
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

    // 6. Bảng danh sách hàng đợi
    ImGuiTableFlags flags = ImGuiTableFlags_Borders | ImGuiTableFlags_RowBg | ImGuiTableFlags_ScrollY;
    if (ImGui::BeginTable("DownloadQueueTable", 6, flags, ImVec2(0, -1))) {
        ImGui::TableSetupColumn("ID", ImGuiTableColumnFlags_WidthFixed, 35.0f);
        ImGui::TableSetupColumn(u8"Định dạng / Độ phân giải", ImGuiTableColumnFlags_WidthFixed, 180.0f);
        ImGui::TableSetupColumn(u8"Địa chỉ URL", ImGuiTableColumnFlags_WidthStretch);
        ImGui::TableSetupColumn(u8"Tiến độ", ImGuiTableColumnFlags_WidthFixed, 140.0f);
        ImGui::TableSetupColumn(u8"Tốc độ / ETA", ImGuiTableColumnFlags_WidthFixed, 140.0f);
        ImGui::TableSetupColumn(u8"Trạng thái", ImGuiTableColumnFlags_WidthFixed, 110.0f);
        ImGui::TableHeadersRow();

        std::lock_guard<std::mutex> lock(g_queueMutex);
        for (const auto& item : g_queue) {
            ImGui::TableNextRow();

            ImGui::TableSetColumnIndex(0);
            ImGui::Text("%d", item.id);

            ImGui::TableSetColumnIndex(1);
            if (item.type == 1) {
                ImGui::Text("Audio MP3");
            } else {
                ImGui::Text("MP4 | %s", g_resNames[item.resIndex]);
            }

            ImGui::TableSetColumnIndex(2);
            ImGui::TextUnformatted(item.url.c_str());

            ImGui::TableSetColumnIndex(3);
            char progOverlay[32];
            snprintf(progOverlay, sizeof(progOverlay), "%.1f%%", item.progress * 100.0f);
            ImGui::ProgressBar(item.progress, ImVec2(-1.0f, 15.0f), progOverlay);

            ImGui::TableSetColumnIndex(4);
            if (item.status == TaskStatus::Downloading || item.eta == "Archive") {
                ImGui::Text("%s | %s", item.speed.c_str(), item.eta.c_str());
            } else {
                ImGui::TextDisabled("--");
            }

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
        }
        ImGui::EndTable();
    }

    ImGui::End();
}