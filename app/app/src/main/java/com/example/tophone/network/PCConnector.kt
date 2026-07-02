package com.example.tophone.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.File
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer

const val SERVICE_TYPE = "_mydata._tcp."

class PCConnector(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    // ------ 回调接口 ------
    var onConnected: ((String, Int) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null
    var onTextReceived: ((String) -> Unit)? = null
    var onFileReceived: ((String, ByteArray) -> Unit)? = null  // 文件名，文件数据

    // ------ mDNS 发现 ------
    fun discover() {
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == SERVICE_TYPE) {
                    nsdManager.resolveService(service, resolveListener)
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host.hostAddress ?: return
            val port = serviceInfo.port
            Thread { connect(host, port) }.start()
        }
    }

    // ------ 连接 + 接收线程 ------
    private var receiveThread: Thread? = null

    private fun connect(host: String, port: Int) {
        try {
            socket = Socket(host, port)
            outputStream = socket?.getOutputStream()
            // 启动接收线程
            startReceiving()
            println("已连接到 $host:$port")
            onConnected?.invoke(host, port)
        } catch (e: Exception) {
            e.printStackTrace()
            onError?.invoke(e)
        }
    }

    private fun startReceiving() {
        receiveThread = Thread {
            try {
                val inputStream = socket?.getInputStream() ?: return@Thread
                while (true) {
                    // 读取 5 字节头部
                    val header = ByteArray(5)
                    var read = 0
                    while (read < 5) {
                        val n = inputStream.read(header, read, 5 - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < 5) break
                    val msgType = header[0].toInt() and 0xFF
                    val payloadLen = ByteBuffer.wrap(header, 1, 4).int

                    // 读取负载
                    val payload = ByteArray(payloadLen)
                    var readPayload = 0
                    while (readPayload < payloadLen) {
                        val n = inputStream.read(payload, readPayload, payloadLen - readPayload)
                        if (n < 0) break
                        readPayload += n
                    }
                    if (readPayload < payloadLen) break

                    // 解析消息
                    when (msgType) {
                        0x01 -> { // 文本
                            val text = String(payload, Charsets.UTF_8)
                            onTextReceived?.invoke(text)
                        }
                        0x02 -> { // 文件
                            val buffer = ByteBuffer.wrap(payload)
                            val nameLen = buffer.int
                            val nameBytes = ByteArray(nameLen)
                            buffer.get(nameBytes)
                            val fileName = String(nameBytes, Charsets.UTF_8)
                            val dataLen = buffer.int
                            val data = ByteArray(dataLen)
                            buffer.get(data)
                            onFileReceived?.invoke(fileName, data)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        receiveThread?.start()
    }

    // ------ 发送文本 ------
    fun sendText(text: String) {
        Thread {
            try {
                val data = text.toByteArray(Charsets.UTF_8)
                val header = ByteBuffer.allocate(5)
                header.put(0x01)
                header.putInt(data.size)
                outputStream?.write(header.array())
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // ------ 发送文件（通用）------
    fun sendFile(fileName: String, data: ByteArray) {
        Thread {
            try {
                val nameBytes = fileName.toByteArray(Charsets.UTF_8)
                val payloadLen = 4 + nameBytes.size + 4 + data.size
                val header = ByteBuffer.allocate(5)
                header.put(0x02)
                header.putInt(payloadLen)

                outputStream?.apply {
                    write(header.array())
                    write(ByteBuffer.allocate(4).putInt(nameBytes.size).array())
                    write(nameBytes)
                    write(ByteBuffer.allocate(4).putInt(data.size).array())
                    write(data)
                    flush()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // ------ 发送图片（保留兼容）------
    fun sendImage(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return
        val data = file.readBytes()
        sendFile(file.name, data)
    }

    // ------ 释放资源 ------
    fun close() {
        socket?.close()
        receiveThread?.interrupt()
    }
}