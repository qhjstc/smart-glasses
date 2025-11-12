import os
import socket
import struct
import threading
import json 
import dashscope
import sounddevice as sd
import numpy as np
from dashscope import Generation
from dashscope.audio.asr import TranslationRecognizerRealtime, TranslationRecognizerCallback


# ====================================================
# 基础配置
# ====================================================
dashscope.api_key = 'sk-e05e5076e72e493998428e2d770e7a11'

MODE = 1  # 0 = TCP服务器模式；1 = 本地麦克风输入模式
LOCAL_MODE_TYPE = "TALKING"  # 当 MODE=1 时使用，可为 "TALKING" 或 "TRANSLATION"

HOST = "0.0.0.0"
PORT = 50005


# ====================================================
# 实时回调类
# ====================================================
class Callback(TranslationRecognizerCallback):
    def __init__(self, conn: socket.socket = None, get_mode_fn=None, print_local=False, on_final_text=None):
        super().__init__()
        self.conn = conn
        self.get_mode = get_mode_fn
        self.partial_text = ""
        self.translation_text = ""
        self.print_local = print_local
        self.on_final_text = on_final_text  # ✅ 当识别结果完成时触发（仅本地TALKING）

    def _send_json(self, obj: dict):
        if not self.conn:
            if self.print_local:
                print("📤 输出消息:", obj)
            return
        try:
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.conn.sendall(struct.pack(">I", len(data)) + data)
        except Exception as e:
            print(f"⚠️ 回调发送包失败: {e}")

    def on_event(self, request_id, transcription_result, translation_result, usage):
        """DashScope 实时事件回调"""
        mode = self.get_mode() if self.get_mode else LOCAL_MODE_TYPE

        # 实时识别
        if transcription_result and transcription_result.text:
            text = transcription_result.text.strip()
            if text:
                self.partial_text = text
                print(f"🎤 实时识别: {text}")

                # 如果已经是完整句子，可以通过 is_sentence_end 来判断结尾
                if transcription_result.is_sentence_end and self.on_final_text and mode == "TALKING":
                    self.on_final_text(text)
                    self.partial_text = ""

        # 翻译模式输出
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
# LLM 调用
# ====================================================
def call_llm_stream(user_text: str):
    """本地TALKING模式：实时生成LLM响应"""
    if not user_text:
        return

    print(f"🧠 [Qwen] 用户说: {user_text}")
    messages = [{"role": "user", "content": user_text}]
    reply_accum = ""

    try:
        responses = Generation.call(
            model="qwen-plus",
            messages=messages,
            result_format="message",
            stream=True,
            incremental_output=True
        )

        print("🤖 助手回复: ", end="", flush=True)
        for response in responses:
            if response.status_code == 200:
                delta = response.output.choices[0].message.content
                if delta:
                    reply_accum += delta
                    print(delta, end="", flush=True)
        print("\n💬 完整回复:", reply_accum)
    except Exception as e:
        print(f"❌ 调用LLM失败: {e}")


# ====================================================
# 客户端处理函数（保持不变）
# ====================================================
def handle_client(conn: socket.socket, addr):
    print(f"📡 客户端连接: {addr}")
    conn.settimeout(120)
    current_mode = "DEFAULT"
    translator = None
    callback = Callback(conn=conn, get_mode_fn=lambda: current_mode)
    messages = []

    def send_json(obj: dict):
        try:
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            conn.sendall(struct.pack(">I", len(data)) + data)
        except Exception as e:
            print(f"⚠️ send_json失败: {e}")

    def call_llm(user_text: str):
        nonlocal messages
        if not user_text:
            return
        print(f"🧠 调用Qwen, 用户说: {user_text}")
        messages.append({"role": "user", "content": user_text})
        reply = ""

        try:
            responses = Generation.call(
                model="qwen-plus",
                messages=messages,
                result_format="message",
                stream=True,
                incremental_output=True
            )

            for response in responses:
                if response.status_code == 200:
                    delta = response.output.choices[0].message.content
                    if delta:
                        reply += delta
                        send_json({"type": "CHAT_STREAM", "delta": delta, "is_final": False})
            messages.append({"role": "assistant", "content": reply})
            send_json({"type": "CHAT_STREAM", "delta": "", "is_final": True, "full_reply": reply})
            print(f"💬 Qwen完整回复: {reply}")

        except Exception as e:
            print(f"❌ LLM调用失败: {e}")
            send_json({"type": "ERROR", "msg": str(e)})

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

            try:
                obj = json.loads(payload.decode("utf-8"))
                if "type" in obj:
                    t = obj["type"]
                    if t == "MODE":
                        current_mode = obj.get("mode", "DEFAULT")
                        print(f"🎮 模式切换 -> {current_mode}")
                        if translator:
                            translator.stop()
                            translator = None
                        continue
                    elif t == "ASR_END" and current_mode == "TALKING":
                        final_text = callback.partial_text.strip()
                        if final_text:
                            send_json({"type": "RESULT", "transcription": final_text})
                            call_llm(final_text)
                        callback.partial_text = ""
                        continue
            except Exception:
                pass

            if current_mode in ("TRANSLATION", "TALKING"):
                if translator is None:
                    print(f"✅ 启动语音识别通道 mode={current_mode}")
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
                translator.send_audio_frame(payload)
    except Exception as e:
        import traceback
        print(f"❌ 客户端异常: {e}")
        traceback.print_exc()
    finally:
        if translator:
            translator.stop()
        conn.close()
        print(f"👋 连接关闭: {addr}")


# ====================================================
# 本地麦克风模式 (MODE=1)
# ====================================================
def mic_mode(local_mode_type="TRANSLATION"):
    print(f"🎧 启动本地麦克风模式 ({local_mode_type})")

    callback = Callback(
        conn=None,
        print_local=True,
        get_mode_fn=lambda: local_mode_type,
        on_final_text=call_llm_stream if local_mode_type == "TALKING" else None
    )
    translator = TranslationRecognizerRealtime(
        model="gummy-realtime-v1",
        format="pcm",
        sample_rate=16000,
        transcription_enabled=True,
        translation_enabled=(local_mode_type == "TRANSLATION"),
        translation_target_languages=["en"] if local_mode_type == "TRANSLATION" else [],
        callback=callback
    )
    translator.start()

    def audio_callback(indata, frames, time, status):
        pcm = (indata * 32767).astype(np.int16).tobytes()
        translator.send_audio_frame(pcm)

    try:
        with sd.InputStream(channels=1, samplerate=16000, callback=audio_callback):
            print("🎙 开始录音 (Ctrl+C 退出)")
            while True:
                sd.sleep(1000)
    except KeyboardInterrupt:
        print("🛑 手动停止")
    finally:
        translator.stop()
        print("✅ 识别器关闭")


# ====================================================
# 启动服务器 (MODE=0)
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


# ====================================================
# 程序入口
# ====================================================
if __name__ == "__main__":
    if MODE == 0:
        start_server()
    else:
        mic_mode(local_mode_type=LOCAL_MODE_TYPE)