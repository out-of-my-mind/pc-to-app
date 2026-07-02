package com.example.tophone

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.tophone.network.PCConnector
import com.example.tophone.ui.theme.TophoneTheme
import java.io.File
import androidx.compose.foundation.text.selection.SelectionContainer

class MainActivity : ComponentActivity() {
    // 状态变量
    private var connectionStatus by mutableStateOf("未连接")
    private var receivedMessage by mutableStateOf("")
    private lateinit var connector: PCConnector

    // 文件选择器 Launcher
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            handleSelectedFile(uri)
        }
    }

    private fun openFilePicker() {
        filePickerLauncher.launch(arrayOf("*/*"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化连接器
        connector = PCConnector(this)

        // 设置回调
        connector.onConnected = { host, port ->
            runOnUiThread {
                connectionStatus = "已连接到 $host:$port"
            }
        }
        connector.onError = { e ->
            runOnUiThread {
                connectionStatus = "连接失败: ${e.message}"
            }
        }
        // 接收文本回调
        connector.onTextReceived = { text ->
            runOnUiThread {
                receivedMessage = "收到文本: $text"
                Toast.makeText(this, "收到文本: $text", Toast.LENGTH_SHORT).show()
            }
        }
        // 接收文件回调
        connector.onFileReceived = { fileName, data ->
            runOnUiThread {
                // 保存到应用私有目录
                val savedFile = File(getExternalFilesDir(null), fileName)
                savedFile.writeBytes(data)
                receivedMessage = "收到文件: ${savedFile.absolutePath}"
                Toast.makeText(this, "文件已保存: $fileName", Toast.LENGTH_LONG).show()
            }
        }

        enableEdgeToEdge()
        setContent {
            TophoneTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        connector = connector,
                        status = connectionStatus,
                        receivedMsg = receivedMessage,
                        onStatusChange = { newStatus -> connectionStatus = newStatus },
                        onSendFileClick = { openFilePicker() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    // 处理选中的文件
    private fun handleSelectedFile(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                runOnUiThread {
                    Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show()
                }
                return
            }
            val data = inputStream.readBytes()
            inputStream.close()
            connector.sendFile(fileName, data)
            Toast.makeText(this, "正在发送: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "发送文件失败", Toast.LENGTH_SHORT).show()
        }
    }

    // 从 Uri 获取文件名
    private fun getFileName(uri: Uri): String {
        var fileName = "unknown"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = it.getString(nameIndex)
                }
            }
        }
        return fileName
    }

    override fun onDestroy() {
        super.onDestroy()
        connector.close()
    }
}

// ---------- Composable UI ----------
@Composable
fun MainScreen(
    connector: PCConnector,
    status: String,
    receivedMsg: String,
    onStatusChange: (String) -> Unit,
    onSendFileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }

    Column(modifier = modifier.padding(16.dp)) {
        Text(text = "状态：$status")
        Spacer(modifier = Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = "消息：$receivedMsg",
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            onStatusChange("正在扫描...")
            connector.discover()
        }) {
            Text("发现并连接PC")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("输入文本") }
        )

        Button(onClick = {
            if (inputText.isNotEmpty()) {
                connector.sendText(inputText)
                onStatusChange("已发送文本")
            }
        }) {
            Text("发送文本")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = onSendFileClick) {
            Text("选择文件并发送")
        }
    }
}

// ---------- 预览 ----------
@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    TophoneTheme {
        MainScreen(
            connector = PCConnector(androidx.test.core.app.ApplicationProvider.getApplicationContext()),
            status = "未连接",
            receivedMsg = "",
            onStatusChange = {},
            onSendFileClick = {}
        )
    }
}