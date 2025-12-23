import os
import socket
import struct
import threading
import json
import time
import base64
import queue
from dataclasses import dataclass

import dashscope
from dashscope import Generation
from dashscope.audio.asr import TranslationRecognizerRealtime, TranslationRecognizerCallback

from openai import OpenAI


# ====================================================
# 基础配置
# ====================================================
dashscope.api_key = os.getenv("DASHSCOPE_API_KEY")
print("🔑 Using DASHSCOPE_API_KEY:", dashscope.api_key)

MODE = 0  # 0 = TCP服务器模式；1 = 本地麦克风输入模式（下面保留但你可删）
LOCAL_MODE_TYPE = "TALKING"

HOST = "0.0.0.0"
PORT = 50005

# 千问VL（OpenAI兼容）
vl_client = OpenAI(
    api_key=os.getenv("DASHSCOPE_API_KEY"),
    base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
)
VL_MODEL = "qwen3-vl-plus"


# ====================================================
# 工具函数：稳定读满 N 字节
# ====================================================
def recv_exact(conn: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("socket closed while reading bytes")
        buf.extend(chunk)
    return bytes(buf)


def send_framed_json(conn: socket.socket, obj: dict):
    data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    conn.sendall(struct.pack(">I", len(data)) + data)


def analyze_image_with_qwen_vl(jpeg_bytes: bytes, prompt: str) -> str:
    b64 = base64.b64encode(jpeg_bytes).decode("ascii")
    data_url = f"data:image/jpeg;base64,{b64}"

    completion = vl_client.chat.completions.create(
        model=VL_MODEL,
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": data_url}},
                    {"type": "text", "text": prompt},
                ],
            }
        ],
    )
    return completion.choices[0].message.content


# ====================================================
# 实时回调类（改造：保存 partial / 句末final / 时间戳）
# ====================================================
class Callback(TranslationRecognizerCallback):
    def __init__(self, conn: socket.socket = None, get_mode_fn=None, print_local=False):
        super().__init__()
        self.conn = conn
        self.get_mode = get_mode_fn
        self.print_local = print_local

        self.last_partial = ""
        self.last_partial_ts = 0.0

        self.last_sentence = ""
        self.last_sentence_ts = 0.0

        self.translation_text = ""
        self.translation_ts = 0.0

        self._lock = threading.Lock()

    def _send_json(self, obj: dict):
        if not self.conn:
            if self.print_local:
                print("📤 输出消息:", obj)
            return
        try:
            send_framed_json(self.conn, obj)
        except Exception as e:
            print(f"⚠️ 回调发送包失败: {e}")

    def on_event(self, request_id, transcription_result, translation_result, usage):
        mode = self.get_mode() if self.get_mode else LOCAL_MODE_TYPE
        now = time.time()

        if transcription_result and transcription_result.text:
            text = transcription_result.text.strip()
            if text:
                with self._lock:
                    self.last_partial = text
                    self.last_partial_ts = now

                    if getattr(transcription_result, "is_sentence_end", False):
                        self.last_sentence = text
                        self.last_sentence_ts = now

                # 你可按需关闭打印
                print(f"🎤 ASR: {text}{' [END]' if getattr(transcription_result, 'is_sentence_end', False) else ''}")

        if mode == "TRANSLATION" and translation_result:
            en_res = translation_result.get_translation("en")
            if en_res and en_res.text:
                tr = en_res.text.strip()
                with self._lock:
                    self.translation_text = tr
                    self.translation_ts = now

                self._send_json({"type": "TRANSLATION_PARTIAL", "zh": self.last_partial, "en": tr})


@dataclass
class LlmTask:
    turn_id: int
    user_text: str


# ====================================================
# 客户端处理函数（改造 TALKING：异步 LLM + turn_id + 触发策略）
# ====================================================
def handle_client(conn: socket.socket, addr):
    print(f"📡 客户端连接: {addr}")
    conn.settimeout(120)

    current_mode = "DEFAULT"
    translator = None

    callback = Callback(conn=conn, get_mode_fn=lambda: current_mode)

    # 对话历史（裁剪）
    messages = []
    MAX_TURNS = 8  # 保留最近 8 轮（user+assistant 计为 2 条）

    # LLM 任务队列与 turn 控制
    llm_q: "queue.Queue[LlmTask]" = queue.Queue()
    turn_id = 0
    active_turn_lock = threading.Lock()
    active_turn_id = 0

    # 用于避免同一句重复触发（句末/ASR_END 兜底会触发两次）
    last_triggered_text = ""
    last_triggered_ts = 0.0

    def send_json(obj: dict):
        try:
            send_framed_json(conn, obj)
        except Exception as e:
            print(f"⚠️ send_json失败: {e}")

    def set_active_turn(tid: int):
        nonlocal active_turn_id
        with active_turn_lock:
            active_turn_id = tid

    def get_active_turn() -> int:
        with active_turn_lock:
            return active_turn_id

    def trim_messages():
        nonlocal messages
        # 粗裁剪：保留最后 MAX_TURNS*2 条（user+assistant）
        max_len = MAX_TURNS * 2
        if len(messages) > max_len:
            messages = messages[-max_len:]

    def llm_worker():
        nonlocal messages
        while True:
            task = llm_q.get()
            if task is None:
                return

            tid = task.turn_id
            user_text = task.user_text

            # 若已经被更新 turn（新一轮开始），旧任务可以选择跳过（软取消）
            if tid != get_active_turn():
                continue

            print(f"🧠 LLM turn={tid}, 用户: {user_text}")

            # 构建消息
            messages.append({"role": "user", "content": user_text})
            trim_messages()

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
                    # 软取消：新 turn 开始则停止旧 turn 推流
                    if tid != get_active_turn():
                        break

                    if response.status_code == 200:
                        delta = response.output.choices[0].message.content
                        if delta:
                            reply += delta
                            send_json({
                                "type": "CHAT_STREAM",
                                "turn_id": tid,
                                "delta": delta,
                                "is_final": False
                            })
                    else:
                        send_json({"type": "ERROR", "msg": f"LLM status={response.status_code}", "turn_id": tid})
                        break

                # 仅在没被取消时写入历史 & 发 final
                if tid == get_active_turn():
                    messages.append({"role": "assistant", "content": reply})
                    trim_messages()
                    send_json({
                        "type": "CHAT_STREAM",
                        "turn_id": tid,
                        "delta": "",
                        "is_final": True,
                        "full_reply": reply
                    })
                    print(f"💬 LLM turn={tid} 完整回复: {reply}")

            except Exception as e:
                print(f"❌ LLM调用失败: {e}")
                send_json({"type": "ERROR", "msg": str(e), "turn_id": tid})

    # 启动 LLM 工作者线程（每个连接一个，简单可靠）
    threading.Thread(target=llm_worker, daemon=True).start()

    def trigger_llm(text: str, reason: str):
        nonlocal turn_id, last_triggered_text, last_triggered_ts

        text = (text or "").strip()
        if not text:
            return

        now = time.time()
        # 去重：1.5s 内同样文本不重复触发（句末 & ASR_END 兜底）
        if text == last_triggered_text and (now - last_triggered_ts) < 1.5:
            return

        last_triggered_text = text
        last_triggered_ts = now

        turn_id += 1
        set_active_turn(turn_id)

        # 先把用户最终文本发回客户端（你 Android 侧会显示“你：xxx”）
        send_json({"type": "RESULT", "transcription": text, "turn_id": turn_id, "reason": reason})

        # 入队做 LLM（异步）
        llm_q.put(LlmTask(turn_id=turn_id, user_text=text))

    def ensure_translator_started():
        nonlocal translator
        if translator is not None:
            return

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

    def stop_translator():
        nonlocal translator
        if translator:
            try:
                translator.stop()
            except Exception:
                pass
            translator = None

    try:
        while True:
            header = recv_exact(conn, 4)
            frame_len = struct.unpack(">I", header)[0]
            if frame_len <= 0 or frame_len > 50_000_000:
                raise ValueError(f"Invalid frame_len={frame_len}")

            payload = recv_exact(conn, frame_len)

            # 尝试当 JSON 解析
            obj = None
            if payload[:1] == b"{" and payload[-1:] in (b"}",):  # 小优化：降低误判概率
                try:
                    obj = json.loads(payload.decode("utf-8"))
                except Exception:
                    obj = None

            if isinstance(obj, dict) and "type" in obj:
                t = obj["type"]

                if t == "MODE":
                    current_mode = obj.get("mode", "DEFAULT")
                    print(f"🎮 模式切换 -> {current_mode}")

                    # 模式切换：停 ASR
                    stop_translator()

                    # TALKING 新一轮：重置 turn（可选）
                    # set_active_turn(turn_id)

                    continue

                # TRACKING：收图 -> VL -> 回传
                if t == "PHOTO" and current_mode == "TRACKING":
                    photo_len = int(obj.get("len", 0))
                    if photo_len <= 0 or photo_len > 20_000_000:
                        raise ValueError(f"Invalid photo len={photo_len}")

                    jpeg_bytes = recv_exact(conn, photo_len)
                    prompt = obj.get("prompt", "请描述这张图片里有什么，并指出关键目标/文字/场景。")

                    ts = int(obj.get("ts", int(time.time() * 1000)))
                    w = obj.get("w")
                    h = obj.get("h")
                    print(f"📷 收到照片 ts={ts} size={photo_len} w={w} h={h}，开始VL分析...")

                    try:
                        result = analyze_image_with_qwen_vl(jpeg_bytes, prompt)
                        send_json({"type": "PHOTO_VL_RESULT", "ts": ts, "w": w, "h": h, "text": result})
                        print("✅ VL分析完成")
                        print("💡 VL结果:", result)
                    except Exception as e:
                        print(f"❌ VL分析失败: {e}")
                        send_json({"type": "ERROR", "msg": f"VL分析失败: {e}"})
                    continue

                # TALKING：收到 VAD 发来的 ASR_END（兜底触发）
                if t == "ASR_END" and current_mode == "TALKING":
                    with callback._lock:
                        sentence = callback.last_sentence.strip()
                        partial = callback.last_partial.strip()
                        sent_ts = callback.last_sentence_ts
                        part_ts = callback.last_partial_ts

                    # 优先用最近的句末；如果很久没句末，则用 partial
                    now = time.time()
                    chosen = ""
                    reason = ""
                    if sentence and (now - sent_ts) < 3.0:
                        chosen = sentence
                        reason = "asr_end_sentence"
                    elif partial and (now - part_ts) < 3.0:
                        chosen = partial
                        reason = "asr_end_partial"

                    if chosen:
                        trigger_llm(chosen, reason=reason)
                    continue

                # 其他控制消息
                continue

            # 非 JSON：当作音频帧（仅 TALKING/TRANSLATION）
            if current_mode in ("TRANSLATION", "TALKING"):
                ensure_translator_started()
                translator.send_audio_frame(payload)

                # TALKING：句末触发（首选）
                if current_mode == "TALKING":
                    with callback._lock:
                        sentence = callback.last_sentence.strip()
                        sent_ts = callback.last_sentence_ts
                    if sentence and (time.time() - sent_ts) < 0.8:
                        # 句末出现后尽快触发一次（去重逻辑在 trigger_llm 内）
                        trigger_llm(sentence, reason="sentence_end")

    except Exception as e:
        import traceback
        print(f"❌ 客户端异常: {e}")
        traceback.print_exc()
    finally:
        stop_translator()
        try:
            conn.close()
        except Exception:
            pass
        # 停 worker
        try:
            llm_q.put(None)
        except Exception:
            pass
        print(f"👋 连接关闭: {addr}")


# ====================================================
# 本地麦克风模式 (MODE=1) - 可选保留
# ====================================================
def mic_mode(local_mode_type="TRANSLATION"):
    import sounddevice as sd
    import numpy as np

    print(f"🎧 启动本地麦克风模式 ({local_mode_type})")

    callback = Callback(
        conn=None,
        print_local=True,
        get_mode_fn=lambda: local_mode_type
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

    # 简单本地：句末时打印（你也可以在这里接 LLM）
    last_sentence = ""

    def audio_callback(indata, frames, time_info, status):
        pcm = (indata * 32767).astype(np.int16).tobytes()
        translator.send_audio_frame(pcm)

    try:
        with sd.InputStream(channels=1, samplerate=16000, callback=audio_callback):
            print("🎙 开始录音 (Ctrl+C 退出)")
            while True:
                time.sleep(0.2)
                with callback._lock:
                    s = callback.last_sentence.strip()
                if s and s != last_sentence:
                    last_sentence = s
                    print("🧾 句末:", s)

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