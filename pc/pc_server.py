#!/usr/bin/env python3
import socket
import struct
import threading
import os
from zeroconf import ServiceInfo, Zeroconf
import tkinter as tk
from tkinter import scrolledtext, filedialog, messagebox
from datetime import datetime

PORT = 8888
SERVICE_TYPE = "_mydata._tcp.local."
SERVICE_NAME = "MyAppData._mydata._tcp.local."

class PCServer:
    def __init__(self, gui):
        self.gui = gui
        self.zeroconf = Zeroconf()
        self.server_socket = None
        self.client_sock = None  # 当前连接的客户端
        self.running = False
        self.server_thread = None

    def register_mdns(self):
        # 获取本机局域网 IP
        local_ip = socket.gethostbyname(socket.gethostname())
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
        except:
            pass
        finally:
            s.close()

        info = ServiceInfo(
            SERVICE_TYPE,
            SERVICE_NAME,
            addresses=[socket.inet_aton(local_ip)],
            port=PORT,
            properties={},
        )
        self.zeroconf.register_service(info)
        self.gui.log(f"mDNS 服务已注册: {SERVICE_NAME} -> {local_ip}:{PORT}")

    def start_server(self):
        if self.running:
            return
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind(('0.0.0.0', PORT))
        self.server_socket.listen(5)
        self.running = True
        self.register_mdns()
        self.gui.log(f"TCP 服务器监听中，端口 {PORT} ...")
        self.server_thread = threading.Thread(target=self._accept_loop, daemon=True)
        self.server_thread.start()

    def _accept_loop(self):
        while self.running:
            try:
                client_sock, addr = self.server_socket.accept()
                self.gui.log(f"客户端连接: {addr}")
                # 如果有旧连接，关闭
                if self.client_sock:
                    try:
                        self.client_sock.close()
                    except:
                        pass
                self.client_sock = client_sock
                # 启动接收线程
                threading.Thread(target=self.handle_client, args=(client_sock,), daemon=True).start()
            except Exception as e:
                if self.running:
                    self.gui.log(f"接受连接错误: {e}")
                break

    def recv_exact(self, sock, n):
        data = bytearray()
        while len(data) < n:
            packet = sock.recv(n - len(data))
            if not packet:
                raise ConnectionError("连接断开")
            data.extend(packet)
        return bytes(data)

    def handle_client(self, client_sock):
        try:
            while True:
                header = self.recv_exact(client_sock, 5)
                msg_type = header[0]
                payload_len = struct.unpack('!I', header[1:5])[0]

                if msg_type == 0x01:  # 文本
                    text_bytes = self.recv_exact(client_sock, payload_len)
                    text = text_bytes.decode('utf-8')
                    self.gui.log(f"[收到文本] {text}")
                    self.gui.display_text(f"[移动端] {text}")

                elif msg_type == 0x02:  # 文件
                    # 接收文件名
                    fn_len_bytes = self.recv_exact(client_sock, 4)
                    fn_len = struct.unpack('!I', fn_len_bytes)[0]
                    fn_bytes = self.recv_exact(client_sock, fn_len)
                    file_name = fn_bytes.decode('utf-8')
                    # 接收文件数据
                    img_len_bytes = self.recv_exact(client_sock, 4)
                    img_len = struct.unpack('!I', img_len_bytes)[0]
                    img_data = self.recv_exact(client_sock, img_len)

                    save_dir = "received_files"
                    os.makedirs(save_dir, exist_ok=True)
                    # 避免重名覆盖，加上时间戳
                    base, ext = os.path.splitext(file_name)
                    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
                    new_name = f"{base}_{timestamp}{ext}"
                    save_path = os.path.join(save_dir, new_name)
                    with open(save_path, 'wb') as f:
                        f.write(img_data)
                    self.gui.log(f"[收到文件] 已保存: {save_path}")
                    self.gui.display_text(f"[移动端] 文件: {file_name} (保存为 {new_name})")

        except (ConnectionError, OSError) as e:
            self.gui.log(f"客户端断开: {e}")
        finally:
            if self.client_sock == client_sock:
                self.client_sock = None
            try:
                client_sock.close()
            except:
                pass

    # ----- 发送方法 -----
    def send_text(self, text):
        if not self.client_sock:
            self.gui.log("没有连接的客户端")
            return False
        try:
            data = text.encode('utf-8')
            header = bytearray(5)
            header[0] = 0x01
            struct.pack_into('!I', header, 1, len(data))
            self.client_sock.sendall(header + data)
            self.gui.log(f"[发送文本] {text}")
            # self.gui.display_text(f"[我] {text}")
            return True
        except Exception as e:
            self.gui.log(f"发送文本失败: {e}")
            return False

    def send_file(self, filepath):
        if not self.client_sock:
            self.gui.log("没有连接的客户端")
            return False
        try:
            with open(filepath, 'rb') as f:
                file_data = f.read()
            file_name = os.path.basename(filepath)
            name_bytes = file_name.encode('utf-8')
            # 组装负载：4字节文件名长度 + 文件名 + 4字节文件数据长度 + 文件数据
            payload = bytearray()
            payload.extend(struct.pack('!I', len(name_bytes)))
            payload.extend(name_bytes)
            payload.extend(struct.pack('!I', len(file_data)))
            payload.extend(file_data)

            header = bytearray(5)
            header[0] = 0x02
            struct.pack_into('!I', header, 1, len(payload))
            self.client_sock.sendall(header + payload)
            self.gui.log(f"[发送文件] {file_name} ({len(file_data)} bytes)")
            # self.gui.display_text(f"[我] 文件: {file_name}")
            return True
        except Exception as e:
            self.gui.log(f"发送文件失败: {e}")
            return False

    def stop(self):
        self.running = False
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass
        if self.client_sock:
            try:
                self.client_sock.close()
            except:
                pass
        # 取消注册 mDNS 服务（忽略事件循环已关闭错误）
        try:
            self.zeroconf.unregister_all_services()
        except RuntimeError as e:
            if "Event loop is closed" in str(e):
                pass  # 忽略，程序即将退出
            else:
                raise
        try:
            self.zeroconf.close()
        except RuntimeError as e:
            if "Event loop is closed" in str(e):
                pass
            else:
                raise
        self.gui.log("服务器已停止")


class PCGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("PC 服务器 - 手机互联")
        self.root.geometry("700x550")

        self.server = PCServer(self)

        # 界面布局
        top_frame = tk.Frame(root)
        top_frame.pack(pady=5)

        self.start_btn = tk.Button(top_frame, text="启动服务器", command=self.start_server, width=12)
        self.start_btn.pack(side=tk.LEFT, padx=5)
        self.stop_btn = tk.Button(top_frame, text="停止服务器", command=self.stop_server, width=12, state=tk.DISABLED)
        self.stop_btn.pack(side=tk.LEFT, padx=5)

        # 日志显示区域
        log_label = tk.Label(root, text="日志/消息记录", anchor='w')
        log_label.pack(fill='x', padx=5)

        self.log_area = scrolledtext.ScrolledText(root, height=12, state='normal', wrap=tk.WORD)
        self.log_area.pack(fill='both', expand=True, padx=5, pady=5)

        # 发送区域
        send_frame = tk.Frame(root)
        send_frame.pack(fill='x', padx=5, pady=5)

        self.msg_entry = tk.Entry(send_frame)
        self.msg_entry.pack(side=tk.LEFT, fill='x', expand=True, padx=(0,5))
        self.msg_entry.bind('<Return>', lambda e: self.send_text())

        self.send_btn = tk.Button(send_frame, text="发送文本", command=self.send_text, width=10)
        self.send_btn.pack(side=tk.LEFT, padx=2)

        self.file_btn = tk.Button(send_frame, text="选择文件发送", command=self.send_file, width=12)
        self.file_btn.pack(side=tk.LEFT, padx=2)

        self.clear_btn = tk.Button(send_frame, text="清空记录", command=self.clear_log, width=8)
        self.clear_btn.pack(side=tk.LEFT, padx=2)

        # 状态栏
        self.status_var = tk.StringVar(value="就绪")
        status_bar = tk.Label(root, textvariable=self.status_var, relief=tk.SUNKEN, anchor='w')
        status_bar.pack(fill='x', side=tk.BOTTOM, ipady=2)

    def log(self, msg):
        """添加日志（自动加时间）"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        self.log_area.insert(tk.END, f"[{timestamp}] {msg}\n")
        self.log_area.see(tk.END)

    def display_text(self, msg):
        """在日志区显示消息（用于对话记录）"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        self.log_area.insert(tk.END, f"[{timestamp}] {msg}\n")
        self.log_area.see(tk.END)

    def clear_log(self):
        self.log_area.delete(1.0, tk.END)

    def start_server(self):
        self.start_btn.config(state=tk.DISABLED)
        self.stop_btn.config(state=tk.NORMAL)
        self.status_var.set("服务器运行中...")
        threading.Thread(target=self.server.start_server, daemon=True).start()

    def stop_server(self):
        self.server.stop()
        self.start_btn.config(state=tk.NORMAL)
        self.stop_btn.config(state=tk.DISABLED)
        self.status_var.set("已停止")

    def send_text(self):
        text = self.msg_entry.get().strip()
        if text:
            self.server.send_text(text)
            self.msg_entry.delete(0, tk.END)
        else:
            messagebox.showinfo("提示", "请输入要发送的文本")

    def send_file(self):
        filepath = filedialog.askopenfilename(title="选择要发送的文件")
        if filepath:
            self.server.send_file(filepath)

    def on_closing(self):
        self.server.stop()
        self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    app = PCGUI(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    root.mainloop()