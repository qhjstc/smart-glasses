import socket
import threading
import json
import dashscope
from dashscope.audio.asr import TranslationRecognizerRealtime, TranslationRecognizerCallback


# ====== 基础配置 ======
dashscope.api_key = 'sk-e05e5076e72e493998428e2d770e7a11'
AUDIO_PORT = 50005   # 客户端 -> 服务器
RESULT_PORT = 50006  # 服务器 -> 客户端
should_exit = False


# ====== DashScope 回调类 ======
class Callback(TranslationRecognizerCallback):
    def __init__(self, result_conn):
        super().__init__()
        self.result_conn = result_conn  # 用于发送结果到客户端

    def on_open(self):
        print("✅ DashScope 连接已建立，等待音频输入...")

    def on_close(self):
        print("🛑 DashScope 连接关闭")

    def on_event(self, request_id, transcription_result, translation_result, usage):
        """处理识别+翻译事件，并回传客户端"""
        global should_exit
        zh_text, en_text = "", ""

        if transcription_result:
            zh_text = transcription_result.text.strip()
            if zh_text:
                print(f"🎤 识别结果: {zh_text}")

        if translation_result:
            tr = translation_result.get_translation("en")
            if tr and tr.text:
                en_text = tr.text.strip()
                print(f"🌍 翻译结果: {en_text}")

        # 检测退出命令
        if zh_text and any(kw in zh_text for kw in ["退出", "停止", "结束", "quit", "exit"]):
            print("⚠️ 检测到退出请求，准备停止识别...")
            should_exit = True

        # ====== 回发到客户端 ======
        if zh_text or en_text:
            try:
                msg = {
                    "transcription": zh_text,
                    "translation": en_text
                }
                data = json.dumps(msg, ensure_ascii=False) + "\n"
                self.result_conn.sendall(data.encode("utf-8"))
                print(f"📤 已发送结果: {data.strip()}")
            except Exception as e:
                print(f"⚠️ 向客户端发送结果失败: {e}")


# ====== 音频处理逻辑 ======
def handle_client(audio_conn, result_conn, addr):
    global should_exit
    print(f"📡 客户端 {addr} 已连接音频通道")

    callback = Callback(result_conn)
    translator = None

    try:
        while not should_exit:
            data = audio_conn.recv(3200)
            if not data:
                print("🚪 客户端断开连接")
                break

            if translator is None:
                translator = TranslationRecognizerRealtime(
                    model="gummy-realtime-v1",
                    format="pcm",
                    sample_rate=16000,
                    transcription_enabled=True,
                    translation_enabled=True,
                    translation_target_languages=["en"],
                    callback=callback,
                )
                translator.start()
                print("✅ 实时识别会话启动")

            translator.send_audio_frame(data)

    except Exception as e:
        print(f"⚠️ 音频通道异常: {e}")
    finally:
        if translator:
            translator.stop()
        audio_conn.close()
        result_conn.close()
        print("👋 客户端连接关闭")


# ====== 主服务器 ======
def start_server():
    audio_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    result_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

    audio_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    result_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    host = "0.0.0.0"
    audio_server.bind((host, AUDIO_PORT))
    result_server.bind((host, RESULT_PORT))

    audio_server.listen(5)
    result_server.listen(5)

    print(f"🌍 DashScope 双通道服务器启动")
    print(f"🎧 等待音频通道连接：{host}:{AUDIO_PORT}")
    print(f"🗣️ 等待结果通道连接：{host}:{RESULT_PORT}")

    while True:
        audio_conn, addr = audio_server.accept()
        result_conn, _ = result_server.accept()
        print(f"✅ 获取到一对连接 {addr}")
        threading.Thread(target=handle_client, args=(audio_conn, result_conn, addr), daemon=True).start()


if __name__ == "__main__":
    try:
        start_server()
    except KeyboardInterrupt:
        print("\n🛑 手动停止服务器")
        should_exit = True