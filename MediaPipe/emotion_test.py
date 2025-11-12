import cv2
import time
import mediapipe as mp
from fer import FER
import os

# ---------- 参数设置 ----------
emotion_interval = 2.0       # 每隔多少秒检测一次情绪
padding = 50                 # 人脸框四周扩展的像素
save_debug_faces = True      # 是否保存人脸截图（调试模式）

# ---------- 初始化 ----------
mp_face_detection = mp.solutions.face_detection
face_detector = mp_face_detection.FaceDetection(model_selection=0, min_detection_confidence=0.5)
emotion_detector = FER()

# 打印 FER 后端（兼容所有版本）
if hasattr(emotion_detector, "_backend"):
    print(f"FER backend: {emotion_detector._backend}")
else:
    print("FER backend 属性不存在（你的 FER 版本可能较旧，但可以正常运行）")

# 摄像头
cap = cv2.VideoCapture(0)
if not cap.isOpened():
    raise RuntimeError("❌ 无法打开摄像头，请检查设备")

prev_time = 0
last_emotion_time = 0
last_detected = "Detecting..."

if save_debug_faces:
    os.makedirs("debug_faces", exist_ok=True)

print("✅ 摄像头已启动，按 ESC 退出程序")

while True:
    ret, frame = cap.read()
    if not ret:
        print("❌ 摄像头读取失败。")
        break

    h, w, _ = frame.shape
    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    result = face_detector.process(rgb)

    # 检测人脸
    if result.detections:
        for det_idx, detection in enumerate(result.detections):
            bbox = detection.location_data.relative_bounding_box
            x1 = max(int(bbox.xmin * w) - padding, 0)
            y1 = max(int(bbox.ymin * h) - padding, 0)
            x2 = min(int((bbox.xmin + bbox.width) * w) + padding, w - 1)
            y2 = min(int((bbox.ymin + bbox.height) * h) + padding, h - 1)

            # 绘制框
            cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 255, 0), 2)

            face = frame[y1:y2, x1:x2]
            if face.size == 0:
                continue

            current_time = time.time()
            if current_time - last_emotion_time >= emotion_interval:
                print(f"🧠 检测第 {det_idx + 1} 张人脸: 尺寸 {face.shape}")

                if save_debug_faces:
                    filename = time.strftime("debug_faces/face_%H%M%S.jpg")
                    cv2.imwrite(filename, face)
                    print(f"💾 保存检测人脸：{filename}")

                emotions = emotion_detector.detect_emotions(face)
                print("FER 输出：", emotions)

                if emotions:
                    dominant = emotions[0]["emotions"]
                    emo = max(dominant, key=dominant.get)
                    confidence = dominant[emo]
                    last_detected = f"{emo} ({confidence*100:.1f}%)"
                else:
                    last_detected = "No emotion detected"

                last_emotion_time = current_time

            cv2.putText(frame, last_detected, (x1, y1 - 10),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 255, 0), 2)
    else:
        last_detected = "No face detected"

    curr_time = time.time()
    fps = 1 / (curr_time - prev_time) if prev_time != 0 else 0
    prev_time = curr_time

    cv2.putText(frame, f"FPS: {int(fps)}", (10, 30),
                cv2.FONT_HERSHEY_SIMPLEX, 1, (255, 255, 0), 2)
    cv2.imshow("🧠 Real-time Emotion Recognition", frame)

    if cv2.waitKey(1) & 0xFF == 27:
        print("✅ 用户退出。")
        break

cap.release()
cv2.destroyAllWindows()