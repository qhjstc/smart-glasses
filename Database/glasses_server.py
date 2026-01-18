import socket
import threading
import json
import os
import time
import struct
import queue
from datetime import datetime

SERVER_IP = "192.168.8.40"
PORT_AUDIO = 50005
PORT_VIDEO = 50006
PORT_IMU = 50007

BASE_SAVE_DIR = "received_data"
os.makedirs(BASE_SAVE_DIR, exist_ok=True)

# ===================================================
# 🧩 工具函数
# ===================================================

def log(msg):
    print(f"[SERVER] {msg}", flush=True)

def timestamp_str():
    return datetime.now().strftime("%Y-%m-%d_%H-%M-%S")

def day_str():
    return datetime.now().strftime("%Y-%m-%d")

def day_dir():
    d = os.path.join(BASE_SAVE_DIR, day_str())
    os.makedirs(d, exist_ok=True)
    return d

def open_audio_file():
    return open(os.path.join(day_dir(), f"audio_{timestamp_str()}.pcm"), "ab", buffering=1024 * 1024)

def open_video_file():
    # ✅ 每次轮转都新建文件
    return open(os.path.join(day_dir(), f"video_{timestamp_str()}.h264"), "ab", buffering=1024 * 1024)

def open_imu_file():
    return open(os.path.join(day_dir(), f"imu_{timestamp_str()}.jsonl"), "a", encoding="utf-8")


# ===================================================
# 🎥 H264 AnnexB NAL/IDR 检测（用于“按IDR切片”）
# ===================================================

START_CODE_3 = b"\x00\x00\x01"
START_CODE_4 = b"\x00\x00\x00\x01"

def iter_annexb_nals(payload: bytes):
    """从 AnnexB payload 中迭代 NAL（不含 start code）"""
    n = len(payload)

    def find_start(pos):
        while pos + 3 <= n:
            if payload[pos:pos+4] == START_CODE_4:
                return pos, 4
            if payload[pos:pos+3] == START_CODE_3:
                return pos, 3
            pos += 1
        return -1, 0

    start, sc_len = find_start(0)
    if start < 0:
        return

    i = start + sc_len
    while True:
        nxt, nxt_len = find_start(i)
        if nxt < 0:
            nal = payload[i:]
            if nal:
                yield nal
            break
        nal = payload[i:nxt]
        if nal:
            yield nal
        i = nxt + nxt_len

def has_idr(payload: bytes) -> bool:
    """判断一个 AnnexB payload 是否包含 IDR（nal_type=5）"""
    for nal in iter_annexb_nals(payload):
        nal_type = nal[0] & 0x1F
        if nal_type == 5:
            return True
    return False


# ===================================================
# 🔌 通用：网络接收与写盘解耦（适用于 audio 原始流）
# ===================================================

def handle_stream_to_files(
    conn, addr, *,
    name: str,
    recv_size: int,
    open_file_fn,
    rotate_seconds: int = 60,
    q_max: int = 1024,
    drop_oldest: bool = True,
    sock_timeout: float = 10.0,
):
    log(f"{name} 连接来自 {addr}")
    conn.settimeout(sock_timeout)

    q = queue.Queue(maxsize=q_max)
    stop = threading.Event()

    def writer():
        f = open_file_fn()
        last_rotate = time.time()
        try:
            while not stop.is_set():
                try:
                    chunk = q.get(timeout=1)
                except queue.Empty:
                    continue

                now = time.time()
                if now - last_rotate >= rotate_seconds:
                    try: f.flush()
                    except: pass
                    try: f.close()
                    except: pass
                    f = open_file_fn()
                    last_rotate = now
                    log(f"{name} ✅ 已轮转保存")

                f.write(chunk)
        finally:
            try: f.flush()
            except: pass
            try: f.close()
            except: pass

    wt = threading.Thread(target=writer, name=f"{name}-writer-{addr}", daemon=True)
    wt.start()

    try:
        while True:
            try:
                data = conn.recv(recv_size)
            except socket.timeout:
                continue

            if not data:
                break

            if q.full():
                if drop_oldest:
                    try: q.get_nowait()
                    except queue.Empty: pass
                    q.put_nowait(data)
                else:
                    q.put(data)
            else:
                q.put_nowait(data)

    except Exception as e:
        log(f"{name} ⚠️ 连接异常: {e}")
    finally:
        stop.set()
        try: wt.join(timeout=2)
        except: pass
        try: conn.close()
        except: pass
        log(f"{name} ❌ 连接关闭")


# ===================================================
# 🎧 音频接收（解耦写盘，原样写）
# ===================================================

def handle_audio(conn, addr):
    handle_stream_to_files(
        conn, addr,
        name="🎧 音频",
        recv_size=4096,
        open_file_fn=open_audio_file,
        rotate_seconds=60,
        q_max=512,
        drop_oldest=True,
        sock_timeout=10.0,
    )


# ===================================================
# 🎥 视频接收（长度前缀解包 + 解耦写盘；≥60s 后等待 IDR 才切）
# ===================================================

def handle_video(conn, addr):
    name = "🎥 视频"
    log(f"{name} 连接来自 {addr}")
    conn.settimeout(10.0)

    q = queue.Queue(maxsize=2048)
    stop = threading.Event()

    def writer():
        f = open_video_file()
        last_rotate = time.time()
        pending_rotate = False  # 到点后等待 IDR 再切

        try:
            while not stop.is_set():
                try:
                    payload = q.get(timeout=1)  # payload = AnnexB H264 bytes
                except queue.Empty:
                    continue

                now = time.time()
                if now - last_rotate >= 60:
                    pending_rotate = True

                # ≥60 秒后：等到“包含 IDR 的 payload”才切文件
                if pending_rotate and has_idr(payload):
                    try: f.flush()
                    except: pass
                    try: f.close()
                    except: pass

                    f = open_video_file()
                    last_rotate = time.time()
                    pending_rotate = False
                    log(f"{name} ✅ 已按 IDR 轮转保存")

                f.write(payload)
        finally:
            try: f.flush()
            except: pass
            try: f.close()
            except: pass

    wt = threading.Thread(target=writer, name=f"video-writer-{addr}", daemon=True)
    wt.start()

    buf = b""

    try:
        while True:
            try:
                chunk = conn.recv(65536)
            except socket.timeout:
                continue

            if not chunk:
                break

            buf += chunk

            # 解包：4字节大端长度 + payload
            while True:
                if len(buf) < 4:
                    break

                (msg_len,) = struct.unpack(">I", buf[:4])

                # 长度异常：丢 1 字节尝试重新同步（静默处理，不打日志）
                if msg_len <= 0 or msg_len > 10 * 1024 * 1024:
                    buf = buf[1:]
                    continue

                if len(buf) < 4 + msg_len:
                    break

                payload = buf[4:4 + msg_len]
                buf = buf[4 + msg_len:]

                # 入队（视频实时优先：丢最旧保最新）
                if q.full():
                    try: q.get_nowait()
                    except queue.Empty: pass
                q.put_nowait(payload)

    except Exception as e:
        log(f"{name} ⚠️ 连接异常: {e}")
    finally:
        stop.set()
        try: wt.join(timeout=2)
        except: pass
        try: conn.close()
        except: pass
        log(f"{name} ❌ 连接关闭")


# ===================================================
# 🧭 IMU（JSON 长度前缀，边解析边写 jsonl，每分钟新文件）
# ===================================================

def handle_imu(conn, addr):
    log(f"🧭 IMU连接来自 {addr}")
    conn.settimeout(10.0)

    last_rotate = time.time()
    f = open_imu_file()
    buf = b""
    last_flush = time.time()

    try:
        while True:
            try:
                chunk = conn.recv(4096)
            except socket.timeout:
                continue

            if not chunk:
                break
            buf += chunk

            now = time.time()
            if now - last_rotate >= 60:
                try: f.flush()
                except: pass
                try: f.close()
                except: pass
                f = open_imu_file()
                last_rotate = now
                log("🧭 ✅ 已轮转保存 IMU JSONL")

            while True:
                if len(buf) < 4:
                    break

                (msg_len,) = struct.unpack(">I", buf[:4])

                # 长度异常：丢 1 字节尝试重新同步（静默处理）
                if msg_len <= 0 or msg_len > 1024 * 1024:
                    buf = buf[1:]
                    continue

                if len(buf) < 4 + msg_len:
                    break

                payload = buf[4:4 + msg_len]
                buf = buf[4 + msg_len:]

                try:
                    obj = json.loads(payload.decode("utf-8"))
                except Exception:
                    continue

                f.write(json.dumps(obj, ensure_ascii=False) + "\n")

                if time.time() - last_flush >= 1.0:
                    f.flush()
                    last_flush = time.time()

    except Exception as e:
        log(f"🧭 ⚠️ IMU连接异常: {e}")
    finally:
        try: f.flush()
        except: pass
        try: f.close()
        except: pass
        try: conn.close()
        except: pass
        log("🧭 ❌ IMU连接关闭")


# ===================================================
# 🚀 通用监听线程
# ===================================================

def start_server(port, handler):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((SERVER_IP, port))
    s.listen(5)
    log(f"✅ 监听端口 {port}")
    while True:
        conn, addr = s.accept()
        threading.Thread(target=handler, args=(conn, addr), daemon=True).start()


# ===================================================
# 🏁 主入口
# ===================================================

if __name__ == "__main__":
    log("📡 多路流接收服务器启动（audio/video/imu 保存；video: ≥60s 等 IDR 轮转）")

    threading.Thread(target=start_server, args=(PORT_AUDIO, handle_audio), daemon=True).start()
    threading.Thread(target=start_server, args=(PORT_VIDEO, handle_video), daemon=True).start()
    threading.Thread(target=start_server, args=(PORT_IMU, handle_imu), daemon=True).start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        log("🛑 服务器手动终止")