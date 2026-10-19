package com.nosdrg.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val state by viewModel.uiState.collectAsState()

    var inputUrl by remember { mutableStateOf("") }
    var isAudioOnly by remember { mutableStateOf(false) }
    var selectedResolution by remember { mutableStateOf("720") }
    var expandedDropdown by remember { mutableStateOf(false) }

    val resolutions = listOf("1080", "720", "480", "360", "best")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Media Downloader", fontSize = 24.sp, style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = inputUrl,
            onValueChange = { inputUrl = it },
            label = { Text("Dán liên kết video vào đây") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = state.status != TaskStatus.DOWNLOADING
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = !isAudioOnly,
                    onClick = { isAudioOnly = false },
                    enabled = state.status != TaskStatus.DOWNLOADING
                )
                Text("Video (MP4)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = isAudioOnly,
                    onClick = { isAudioOnly = true },
                    enabled = state.status != TaskStatus.DOWNLOADING
                )
                Text("Audio (M4A)")
            }
        }

        if (!isAudioOnly) {
            ExposedDropdownMenuBox(
                expanded = expandedDropdown,
                onExpandedChange = {
                    if (state.status != TaskStatus.DOWNLOADING) expandedDropdown = it
                }
            ) {
                OutlinedTextField(
                    value = if (selectedResolution == "best") "Gốc (Best)" else "${selectedResolution}p",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Độ phân giải tối đa") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    enabled = state.status != TaskStatus.DOWNLOADING
                )
                ExposedDropdownMenu(
                    expanded = expandedDropdown,
                    onDismissRequest = { expandedDropdown = false }
                ) {
                    resolutions.forEach { res ->
                        DropdownMenuItem(
                            text = { Text(if (res == "best") "Gốc (Best)" else "${res}p") },
                            onClick = {
                                selectedResolution = res
                                expandedDropdown = false
                            }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.startDownload(inputUrl, isAudioOnly, selectedResolution) },
                modifier = Modifier.weight(1f),
                enabled = state.status != TaskStatus.DOWNLOADING,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (state.status == TaskStatus.DOWNLOADING) "Đang xử lý..." else "Tải xuống")
            }

            if (state.status == TaskStatus.DOWNLOADING) {
                Button(
                    onClick = { viewModel.abortDownload() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Hủy")
                }
            }
        }

        if (state.status == TaskStatus.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Text("Tiến độ: ${state.progress.toInt()}%  •  Tốc độ: ${state.speed}", fontSize = 14.sp)
        }

        when (state.status) {
            TaskStatus.SUCCESS -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Tải thành công!", color = Color(0xFF2E7D32), fontSize = 16.sp)
                        Text(state.outputUri, color = Color.DarkGray, fontSize = 12.sp)
                    }
                }
            }
            TaskStatus.FAILED -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Lỗi:", color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
                        Text(state.errorMessage, color = Color.DarkGray, fontSize = 12.sp)
                    }
                }
            }
            TaskStatus.CANCELED -> {
                Text(state.errorMessage, color = Color.Gray, fontSize = 14.sp)
            }
            else -> {}
        }
    }
}