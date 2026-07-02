# PC ↔ App 数据互通工具

PC 端与 Android 移动端通过局域网 TCP 协议实现文本与文件互传的工具。

## 项目结构

```
pc-to-app/
├── pc/                              # PC 端（Python）
│   └── pc_server.py                 # PC 服务器，含 Tkinter GUI
├── app/                             # Android 端（Kotlin + Jetpack Compose）
│   ├── app/
│   │   └── src/main/java/com/example/tophone/
│   │       ├── MainActivity.kt      # 主界面（Jetpack Compose UI）
│   │       └── network/
│   │           └── PCConnector.kt   # 网络连接器（mDNS 发现 + TCP 通信）
│   ├── build.gradle.kts             # 模块构建配置
│   ├── settings.gradle.kts          # 项目设置
│   └── gradle/libs.versions.toml    # 版本目录
├── .gitignore
└── README.md
```

## 功能

- **文本互传** — PC 与手机之间双向发送文本消息
- **文件互传** — PC 与手机之间发送任意文件（图片、文档等）
- **自动发现** — 手机通过 mDNS 自动发现局域网中的 PC 服务器，无需手动输入 IP

## 通信协议

采用自定义 TCP 协议，所有消息以 **5 字节头部**开头：

| 偏移 | 长度 | 说明 |
|------|------|------|
| 0    | 1    | 消息类型 |
| 1    | 4    | 负载长度（大端序，uint32） |

### 消息类型

| 类型值 | 含义 | 负载格式 |
|--------|------|---------|
| `0x01` | 文本 | UTF-8 编码的文本内容 |
| `0x02` | 文件 | `4 字节文件名长度` + `文件名` + `4 字节文件数据长度` + `文件数据` |

## 快速开始

### 1. 启动 PC 服务器

```bash
cd pc
pip install zeroconf
python pc_server.py
```

点击 **"启动服务器"** 按钮，PC 端将通过 mDNS 注册服务。

### 2. 编译并安装 Android App

使用 Android Studio 打开 `app/` 目录，构建并安装到 Android 设备（需 API 29+）。

### 3. 连接

- 确保 PC 与手机处于同一局域网
- 打开手机 App，点击 **"发现并连接PC"**
- App 通过 mDNS 自动发现 PC 服务器并建立 TCP 连接

### 4. 开始传输

- **PC → 手机：** 在 PC 界面输入文本或选择文件发送
- **手机 → PC：** 在 App 中输入文本或选择文件发送

## 技术栈

### PC 端
- **语言：** Python 3
- **GUI：** Tkinter
- **服务发现：** Zeroconf (mDNS)
- **通信：** TCP Socket

### Android 端
- **语言：** Kotlin
- **UI：** Jetpack Compose + Material 3
- **服务发现：** Android NSD API (mDNS)
- **通信：** TCP Socket
- **最低 SDK：** 29 (Android 10)

## 权限说明

Android 端需要以下权限：
- `INTERNET` — TCP 网络通信
- `ACCESS_NETWORK_STATE` — 检查网络状态
- `CHANGE_WIFI_MULTICAST_STATE` — mDNS 多播发现