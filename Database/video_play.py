import os
import glob
import time
import cv2

DIR = r"./received_data/2025-12-23"          # 改成你的目录
PATTERN = "video_2025-12-23_22-09-47.h264"   # 文件名或通配符，如 "video_*.h264"

files = sorted(glob.glob(os.path.join(DIR, PATTERN)))
if not files:
    raise SystemExit(f"没找到文件：{os.path.join(DIR, PATTERN)}")

print("将播放以下文件：")
for f in files:
    print(" -", os.path.basename(f))

print("\n说明：")
print(" - ESC：立即退出")
print(" - q  ：跳到下一个文件\n")

for f in files:
    cap = cv2.VideoCapture(f)  # 让 OpenCV/FFmpeg 解码裸 h264
    if not cap.isOpened():
        print(f"[跳过] 无法打开：{f}")
        continue

    # 尝试读取元数据（裸 h264 可能拿不到可靠值）
    fps = cap.get(cv2.CAP_PROP_FPS)
    frame_count = cap.get(cv2.CAP_PROP_FRAME_COUNT)

    est = None
    if fps and frame_count and fps > 0 and frame_count > 0:
        est = frame_count / fps

    print(f"\n=== {os.path.basename(f)} ===")
    if est is not None:
        print(f"OpenCV 元数据：FPS={fps:.3f}, 帧数={int(frame_count)}, 估算时长={est:.2f}s")
    else:
        # 很常见：裸流拿不到
        print(f"OpenCV 元数据：FPS={fps}, 帧数={frame_count}（可能不可靠/为 0），将用播放读取耗时统计")

    win = f"Playing: {os.path.basename(f)} (q=next, esc=quit)"
    t0 = time.time()
    frames_read = 0

    while True:
        ok, frame = cap.read()
        if not ok:
            break

        frames_read += 1
        cv2.imshow(win, frame)

        key = cv2.waitKey(1) & 0xFF
        if key == 27:  # ESC
            cap.release()
            cv2.destroyAllWindows()
            raise SystemExit
        if key == ord('q'):  # next file
            break

    elapsed = time.time() - t0

    # 如果能拿到 fps，用读到的帧数再估算一次“内容时长”
    content_est = None
    if fps and fps > 0 and frames_read > 0:
        content_est = frames_read / fps

    print(f"读取到帧数：{frames_read}")
    print(f"播放/读取耗时(墙钟)：{elapsed:.2f}s")
    if content_est is not None:
        print(f"按 FPS 估算内容时长：{content_est:.2f}s")

    cap.release()
    cv2.destroyWindow(win)

cv2.destroyAllWindows()