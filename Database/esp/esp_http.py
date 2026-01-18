import cv2
cap = cv2.VideoCapture("http://192.168.8.93/stream")
while True:
    ok, frame = cap.read()
    if not ok: break
    cv2.imshow("mjpeg", frame)
    if cv2.waitKey(1)==27: break