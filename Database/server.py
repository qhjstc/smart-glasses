import socket
import threading
import json
import os
import time
from datetime import datetime

SERVER_IP = "192.168.8.40"
PORT_AUDIO = 50005
PORT_VIDEO = 50006
PORT_IMU = 50007

SAVE_DIR = "received_data"
os.makedirs(SAVE_DIR, exist_ok=True)


# ===================================================
# 🧩 工具函数
# ===================================================

def log(msg):
    print(f"[SERVER] {msg}")

def timestamp_str():
    return datetime.now().strftime("%Y-%m-%d_%H-%M-%S")


# ===================================================
# 🎧 音频接收线程 （16kHz PCM, 每分钟新文件）
# ===================================================

def handle_audio(conn, addr):
    log(f"🎧 音频连接来自 {addr}")
    last_rotate = time.time()
    last_print = 0  # 控制打印频率
    f = open(os.path.join(SAVE_DIR, f"audio_{timestamp_str()}.pcm"), "ab")

    try:
        while True:
            data = conn.recv(4096)
            if not data:
                break

            # 每当接收到数据，可打印出字节数作为 debug
            now = time.time()
            if now - last_print >= 5:  # 每5秒打印一次数据接收状态
                log(f"✅ 已接收音频数据包 ({len(data)} bytes)")
                last_print = now

            if now - last_rotate >= 60:  # 每分钟换文件
                f.close()
                f = open(os.path.join(SAVE_DIR, f"audio_{timestamp_str()}.pcm"), "ab")
                last_rotate = now

            f.write(data)
    except Exception as e:
        log(f"⚠️ 音频连接异常: {e}")
    finally:
        f.close()
        conn.close()
        log("❌ 音频连接关闭")


# ===================================================
# 🎥 视频接收线程 （H.264裸流, 每分钟新文件）
# ===================================================

def handle_video(conn, addr):
    log(f"🎥 视频连接来自 {addr}")
    last_rotate = time.time()
    last_print = 0
    f = open(os.path.join(SAVE_DIR, f"video_{timestamp_str()}.h264"), "ab")

    try:
        while True:
            data = conn.recv(8192)
            if not data:
                break

            now = time.time()
            if now - last_print >= 5:
                log(f"🎞️ 已接收视频数据包 ({len(data)} bytes)")
                last_print = now

            if now - last_rotate >= 60:
                f.close()
                f = open(os.path.join(SAVE_DIR, f"video_{timestamp_str()}.h264"), "ab")
                last_rotate = now

            f.write(data)
    except Exception as e:
        log(f"⚠️ 视频连接异常: {e}")
    finally:
        f.close()
        conn.close()
        log("❌ 视频连接关闭")


# ===================================================
# 🧭 IMU（JSON数据, 每分钟新文件）
# ===================================================

def handle_imu(conn, addr):
    log(f"🧭 IMU连接来自 {addr}")

    last_rotate = time.time()
    last_print = 0
    f = open(os.path.join(SAVE_DIR, f"imu_{timestamp_str()}.txt"), "a", encoding="utf-8")
    buffer = b""

    try:
        while True:
            chunk = conn.recv(1024)
            if not chunk:
                break
            buffer += chunk

            now = time.time()
            if now - last_print >= 5:
                log(f"📡 已接收到 IMU 原始字节 ({len(chunk)} bytes)")
                last_print = now

            if now - last_rotate >= 60:
                f.close()
                f = open(os.path.join(SAVE_DIR, f"imu_{timestamp_str()}.txt"), "a", encoding="utf-8")
                last_rotate = now

            try:
                text = buffer.decode(errors='ignore')
                if "}" in text:
                    parts = text.split("}")
                    buffer = b""
                    for segment in parts[:-1]:
                        line = segment.strip() + "}"
                        if line.strip():
                            data = json.loads(line)
                            log(f"IMU 🧭 yaw={data['yaw']:.1f}, pitch={data['pitch']:.1f}, roll={data['roll']:.1f}")
                            f.write(line + "\n")
                    buffer = parts[-1].encode()
            except json.JSONDecodeError:
                pass  # 可能包不完整，继续等待下一次
    except Exception as e:
        log(f"⚠️ IMU连接异常: {e}")
    finally:
        f.close()
        conn.close()
        log("❌ IMU连接关闭")


# ===================================================
# 🚀 通用监听线程
# ===================================================

def start_server(port, handler):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((SERVER_IP, port))
    s.listen(1)
    log(f"✅ 监听端口 {port}")
    while True:
        conn, addr = s.accept()
        threading.Thread(target=handler, args=(conn, addr), daemon=True).start()


# ===================================================
# 🏁 主入口
# ===================================================

if __name__ == "__main__":
    log("📡 Python 多路流接收服务器启动（支持每分钟文件切分 + Debug打印）")

    threading.Thread(target=start_server, args=(PORT_AUDIO, handle_audio), daemon=True).start()
    threading.Thread(target=start_server, args=(PORT_VIDEO, handle_video), daemon=True).start()
    threading.Thread(target=start_server, args=(PORT_IMU, handle_imu), daemon=True).start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        log("🛑 服务器手动终止")