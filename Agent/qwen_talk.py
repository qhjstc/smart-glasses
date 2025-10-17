import os
import socket
import struct
import threading
import json
import dashscope
from dashscope import Generation
from dashscope.audio.asr import TranslationRecognizerRealtime, TranslationRecognizerCallback


# ====================================================
# 基础配置
# ====================================================
dashscope.api_key = 'sk-e05e5076e72e493998428e2d770e7a11'

HOST = "0.0.0.0"
PORT = 50005


# ====================================================
# 实时回调类：语音识别 & 翻译监听
# ====================================================
class Callback(TranslationRecognizerCallback):
    def __init__(self, conn: socket.socket, get_mode_fn):
        """
        conn: 与客户端通信的 socket
        get_mode_fn: 实时获取当前工作模式的函数引用
        """
        super().__init__()
        self.conn = conn
        self.get_mode = get_mode_fn
        self.partial_text = ""
        self.translation_text = ""

    def _send_json(self, obj: dict):
        """统一安全发送 JSON 帧"""
        try:
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.conn.sendall(struct.pack(">I", len(data)) + data)
        except Exception as e:
            print(f"⚠️ 回调发送包失败: {e}")

    def on_event(self, request_id, transcription_result, translation_result, usage):
        """DashScope 实时事件回调"""
        mode = self.get_mode()

        # ✅ 实时语音识别（两种模式都要）
        if transcription_result and transcription_result.text:
            text = transcription_result.text.strip()
            if text:
                self.partial_text = text
                print(f"🎤 实时识别: {text}")

        # ✅ 若是翻译模式，则自动回传翻译结果
        if mode == "TRANSLATION" and translation_result:
            en_res = translation_result.get_translation("en")
            if en_res and en_res.text:
                self.translation_text = en_res.text.strip()
                print(f"🌍 实时翻译: {self.translation_text}")
                self._send_json({
                    "type": "TRANSLATION_PARTIAL",
                    "zh": self.partial_text,
                    "en": self.translation_text
                })


# ====================================================
# 核心处理线程：处理每个客户端连接
# ====================================================
def handle_client(conn: socket.socket, addr):
    print(f"📡 客户端连接 {addr}")
    conn.settimeout(120)

    current_mode = "DEFAULT"     # 可为: TALKING, TRANSLATION, DEFAULT
    translator = None            # DashScope 实时识别对象
    callback = Callback(conn=conn, get_mode_fn=lambda: current_mode)

    # ———— TALKING 模式下用于多轮 LLM 对话的上下文 ————
    messages = []

    # --------------------------
    def send_json(obj: dict):
        """统一发送 JSON 帧到客户端"""
        try:
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            conn.sendall(struct.pack(">I", len(data)) + data)
        except Exception as e:
            print(f"⚠️ send_json 失败: {e}")

    def call_llm(user_text: str):
        """用于 TALKING 模式的 LLM 调用"""
        nonlocal messages
        if not user_text:
            return
        print(f"🧠 调用 Qwen 模型, 用户说: {user_text}")
        messages.append({"role": "user", "content": user_text})

        try:
            response = Generation.call(
                model="qwen-plus",
                messages=messages,
                result_format="message"
            )
            reply = response.output.choices[0].message.content.strip()
            messages.append({"role": "assistant", "content": reply})
            print(f"💬 Qwen 回复: {reply}")

            send_json({
                "type": "CHAT",
                "user_text": user_text,
                "reply": reply
            })
        except Exception as e:
            print(f"❌ 调用LLM失败: {e}")
            send_json({"type": "ERROR", "msg": str(e)})

    # ====================================================
    # 主循环：接收客户端指令与音频
    # ====================================================
    try:
        while True:
            header = conn.recv(4)
            if not header:
                print("🚪 客户端断开连接")
                break

            frame_len = struct.unpack(">I", header)[0]
            payload = b""
            while len(payload) < frame_len:
                chunk = conn.recv(frame_len - len(payload))
                if not chunk:
                    raise ConnectionError("socket closed mid-frame")
                payload += chunk

            # ——— 尝试解析为 JSON 控制命令 ———
            try:
                obj = json.loads(payload.decode("utf-8"))
                if isinstance(obj, dict) and "type" in obj:
                    t = obj["type"]

                    # 🔁 模式切换
                    if t == "MODE":
                        current_mode = obj.get("mode", "DEFAULT")
                        print(f"🎮 模式切换 -> {current_mode}")

                        # 切换时停止之前识别
                        if translator:
                            try:
                                translator.stop()
                            except Exception:
                                pass
                            translator = None
                        continue

                    # 🟢 TALKING 模式的结尾触发（用于调用 LLM）
                    elif t == "ASR_END" and current_mode == "TALKING":
                        final_text = callback.partial_text.strip()
                        if final_text:
                            send_json({"type": "RESULT", "transcription": final_text})
                            call_llm(final_text)
                        callback.partial_text = ""
                        continue

                    # 其他控制包，如心跳
                    else:
                        print(f"⚙️ 收到控制包: {obj}")
                        continue

            except Exception:
                pass  # 如果不是 JSON，则是音频数据帧

            # ——— 音频数据处理逻辑 ———
            if current_mode in ("TRANSLATION", "TALKING"):
                if translator is None:
                    print(f"✅ 启动语音识别通道 (mode={current_mode})")
                    translator = TranslationRecognizerRealtime(
                        model="gummy-realtime-v1",
                        format="pcm",
                        sample_rate=16000,
                        transcription_enabled=True,
                        translation_enabled=(current_mode == "TRANSLATION"),
                        translation_target_languages=["en"] if current_mode == "TRANSLATION" else [],
                        callback=callback
                    )
                    translator.start()

            try:
                translator.send_audio_frame(payload)
            except Exception as e:
                print(f"⚠️ 音频帧损坏，丢弃此帧: {e}")
                # 如果 translator 内部异常严重，可尝试重启
                try:
                    translator.stop()
                    translator = None
                except Exception:
                    pass
                continue  # 不影响主循环

    except Exception as e:
        import traceback
        print(f"❌ 客户端异常 {addr}: {e}")
        traceback.print_exc()

    finally:
        if translator:
            try:
                translator.stop()
            except Exception as e:
                print(f"⚠️ 关闭翻译器异常: {e}")
        conn.close()
        print(f"👋 连接关闭 {addr}")


# ====================================================
# 启动 TCP 服务器
# ====================================================
def start_server():
    print(f"🚀 启动AI语音服务器 {HOST}:{PORT}")
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT))
    s.listen(5)
    print("✅ 等待客户端连接...\n")
    while True:
        conn, addr = s.accept()
        threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    try:
        start_server()
    except KeyboardInterrupt:
        print("🛑 服务器已手动终止")