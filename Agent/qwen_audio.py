import pyaudio
import dashscope
from dashscope.audio.asr import *
import threading
import time

dashscope.api_key = 'sk-e05e5076e72e493998428e2d770e7a11'

# 全局控制变量
should_exit = False
mic = None
stream = None

class Callback(TranslationRecognizerCallback):
    def on_open(self) -> None:
        global mic, stream
        print("✅ 识别器已启动，请开始说话（说“退出”可结束程序）")
        mic = pyaudio.PyAudio()
        stream = mic.open(format=pyaudio.paInt16, channels=1, rate=16000, input=True)

    def on_close(self) -> None:
        global mic, stream
        print("🛑 识别器已关闭")
        if stream:
            stream.stop_stream()
            stream.close()
        if mic:
            mic.terminate()
        stream = None
        mic = None

    def on_event(self, request_id, transcription_result, translation_result, usage):
        global should_exit
        text = ""

        # 优先使用中文识别结果
        if transcription_result is not None:
            text = transcription_result.text.strip()
            print(f"🎤 识别结果: {text}")

        # 检查是否包含退出关键词（支持中文“退出”、“结束”、“停止”等）
        if text and any(keyword in text for keyword in ["退出", "结束", "停止", "quit", "exit"]):
            print("⚠️ 检测到退出指令，即将关闭...")
            should_exit = True

        # 打印英文翻译（如果有）
        if translation_result is not None:
            en_text = translation_result.get_translation("en")
            if en_text and en_text.text:
                print(f"🌍 英文翻译: {en_text.text}")


def main():
    global should_exit, stream

    callback = Callback()
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

    try:
        while not should_exit:
            if stream:
                try:
                    data = stream.read(3200, exception_on_overflow=False)
                    translator.send_audio_frame(data)
                except Exception as e:
                    print(f"⚠️ 音频读取错误: {e}")
                    break
            else:
                time.sleep(0.1)

    except KeyboardInterrupt:
        print("\n⌨️ 用户中断（Ctrl+C）")
    finally:
        should_exit = True
        translator.stop()
        # 等待 on_close 完成
        time.sleep(1)
        print("👋 程序已退出")


if __name__ == "__main__":
    main()