package com.nosdrg.downloader

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    private val viewModel: DownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DownloaderScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(viewModel: DownloadViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var inputUrl by remember { mutableStateOf("") }
    var expandedFormatDropdown by remember { mutableStateOf(false) }
    var expandedSubDropdown by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {}

            val rawName = uri.lastPathSegment?.substringAfterLast(":")
            val displayName = if (!rawName.isNullOrBlank()) rawName else "Thư mục tùy chọn"
            viewModel.setCustomFolder(uri, displayName)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Trình tải video, audio", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                label = { Text("Dán URL vào đây") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !state.isAnalyzing
            )
            Button(
                onClick = { viewModel.inspectUrl(inputUrl) },
                enabled = inputUrl.isNotBlank() && !state.isAnalyzing,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(56.dp)
            ) {
                if (state.isAnalyzing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Text("Kiểm tra")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Vị trí lưu:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        state.customFolderDisplayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    TextButton(onClick = { folderPicker.launch(null) }) {
                        Text("Thay đổi")
                    }
                    if (state.customFolderUri != null) {
                        TextButton(onClick = { viewModel.resetToDefaultFolder() }) {
                            Text("Mặc định", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        state.metadata?.let { meta ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().height(190.dp)) {
                        AsyncImage(
                            model = meta.thumbnail,
                            contentDescription = "Thumbnail",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(meta.duration, color = Color.White, fontSize = 12.sp)
                        }
                    }

                    Text(
                        text = meta.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("Kenh: ${meta.channel}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (meta.isPlaylist) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    "Phát hiện Playlist liên kết:",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setDownloadEntirePlaylist(false) }
                                ) {
                                    RadioButton(
                                        selected = !state.downloadEntirePlaylist,
                                        onClick = { viewModel.setDownloadEntirePlaylist(false) }
                                    )
                                    Text("Chỉ tải video này", fontSize = 14.sp)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setDownloadEntirePlaylist(true) }
                                ) {
                                    RadioButton(
                                        selected = state.downloadEntirePlaylist,
                                        onClick = { viewModel.setDownloadEntirePlaylist(true) }
                                    )
                                    Text("Tải toàn bộ Playlist", fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = expandedFormatDropdown,
                        onExpandedChange = { expandedFormatDropdown = it }
                    ) {
                        OutlinedTextField(
                            value = state.selectedFormat?.label ?: "Chọn định dạng",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Định dạng xuất") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFormatDropdown) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expandedFormatDropdown,
                            onDismissRequest = { expandedFormatDropdown = false }
                        ) {
                            meta.formats.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.label) },
                                    onClick = {
                                        viewModel.selectFormat(item)
                                        expandedFormatDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    if (state.selectedFormat?.isAudio == false && meta.subtitles.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = expandedSubDropdown,
                            onExpandedChange = { expandedSubDropdown = it }
                        ) {
                            OutlinedTextField(
                                value = state.selectedSubtitle?.name ?: "Không tải phụ đề",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Tải kèm Phụ đề") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSubDropdown) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedSubDropdown,
                                onDismissRequest = { expandedSubDropdown = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Không tải phụ đề") },
                                    onClick = {
                                        viewModel.selectSubtitle(null)
                                        expandedSubDropdown = false
                                    }
                                )
                                meta.subtitles.forEach { sub ->
                                    DropdownMenuItem(
                                        text = { Text(sub.name) },
                                        onClick = {
                                            viewModel.selectSubtitle(sub)
                                            expandedSubDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.addToQueue() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Thêm vào hàng đợi tải")
                    }
                }
            }
        }

        if (state.errorMessage.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Co loi xay ra:", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Text(state.errorMessage, color = Color.DarkGray, fontSize = 12.sp)
                }
            }
        }

        if (state.queueTasks.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hàng đợi tải xuống (${state.queueTasks.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = { viewModel.clearCompletedQueue() }) {
                    Text("Dọn danh sách xong")
                }
            }

            state.queueTasks.forEach { task ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            task.title,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val statusText = when (task.status) {
                                QueueStatus.WAITING -> "[Đang chờ]"
                                QueueStatus.DOWNLOADING -> "[Đang tải] ${task.progress.toInt()}% (${task.speed})"
                                QueueStatus.SUCCESS -> "[Đã tải xong]"
                                QueueStatus.FAILED -> "[Thất bại] ${task.errorMessage}"
                                QueueStatus.CANCELED -> "[Đã dừng]"
                            }
                            val statusColor = when (task.status) {
                                QueueStatus.SUCCESS -> Color(0xFF2E7D32)
                                QueueStatus.FAILED -> MaterialTheme.colorScheme.error
                                QueueStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                                else -> Color.Gray
                            }

                            Text(text = statusText, fontSize = 12.sp, color = statusColor)

                            if (task.status != QueueStatus.DOWNLOADING) {
                                Text(
                                    text = "Xóa",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.clickable { viewModel.removeQueueItem(task.id) }
                                )
                            }
                        }

                        if (task.status == QueueStatus.DOWNLOADING) {
                            LinearProgressIndicator(
                                progress = { task.progress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }

                        if (task.status == QueueStatus.SUCCESS && task.outputUri.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        StorageHelper.playMediaFile(context, task.outputUri, task.formatItem.isAudio)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Phát ngay", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        StorageHelper.openDownloadFolder(context, task.customFolderUri, task.formatItem.isAudio)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Mở thư mục", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}