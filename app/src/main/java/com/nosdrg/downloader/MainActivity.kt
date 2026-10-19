package com.nosdrg.downloader

import android.content.Intent
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
    var expandedDropdown by remember { mutableStateOf(false) }

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
        Text("Media Inspector & Downloader", fontSize = 22.sp, fontWeight = FontWeight.Bold)

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
                enabled = state.status != TaskStatus.DOWNLOADING && !state.isAnalyzing
            )
            Button(
                onClick = { viewModel.inspectUrl(inputUrl) },
                enabled = inputUrl.isNotBlank() && state.status != TaskStatus.DOWNLOADING && !state.isAnalyzing,
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
                    TextButton(
                        onClick = { folderPicker.launch(null) },
                        enabled = state.status != TaskStatus.DOWNLOADING
                    ) {
                        Text("Thay đổi")
                    }
                    if (state.customFolderUri != null) {
                        TextButton(
                            onClick = { viewModel.resetToDefaultFolder() },
                            enabled = state.status != TaskStatus.DOWNLOADING
                        ) {
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
                    Text("Kênh: ${meta.channel}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                                        onClick = { viewModel.setDownloadEntirePlaylist(false) },
                                        enabled = state.status != TaskStatus.DOWNLOADING
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
                                        onClick = { viewModel.setDownloadEntirePlaylist(true) },
                                        enabled = state.status != TaskStatus.DOWNLOADING
                                    )
                                    Text("Tải toàn bộ Playlist", fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = expandedDropdown,
                        onExpandedChange = {
                            if (state.status != TaskStatus.DOWNLOADING) expandedDropdown = it
                        }
                    ) {
                        OutlinedTextField(
                            value = state.selectedFormat?.label ?: "Chọn định dạng",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Định dạng xuất") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            enabled = state.status != TaskStatus.DOWNLOADING
                        )
                        ExposedDropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false }
                        ) {
                            meta.formats.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.label) },
                                    onClick = {
                                        viewModel.selectFormat(item)
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.startDownload() },
                            modifier = Modifier.weight(1f),
                            enabled = state.status != TaskStatus.DOWNLOADING,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            val btnText = if (state.status == TaskStatus.DOWNLOADING) {
                                "Đang tải xuống..."
                            } else if (state.downloadEntirePlaylist) {
                                "Tải toàn bộ Playlist"
                            } else {
                                "Bắt đầu tải"
                            }
                            Text(btnText)
                        }

                        if (state.status == TaskStatus.DOWNLOADING) {
                            Button(
                                onClick = { viewModel.abortDownload() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Dừng")
                            }
                        }
                    }
                }
            }
        }

        if (state.status == TaskStatus.DOWNLOADING) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (state.currentDownloadingTitle.isNotBlank()) {
                        Text(
                            text = state.currentDownloadingTitle,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "${state.progress.toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(text = state.speed, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        when (state.status) {
            TaskStatus.SUCCESS -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Tải thành công!", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        Text(state.outputUri, color = Color.DarkGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

                        val isAudio = state.selectedFormat?.isAudio ?: false
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    StorageHelper.playMediaFile(context, state.outputUri, isAudio)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Phát ngay")
                            }

                            Button(
                                onClick = {
                                    StorageHelper.openDownloadFolder(context, state.customFolderUri, isAudio)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("📁 Mở thư mục")
                            }
                        }
                    }
                }
            }
            TaskStatus.FAILED -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Có lỗi xảy ra:", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        Text(state.errorMessage, color = Color.DarkGray, fontSize = 12.sp)
                    }
                }
            }
            TaskStatus.CANCELED -> {
                Text(state.errorMessage, color = Color.Gray, fontSize = 13.sp)
            }
            else -> {}
        }
    }
}