import socket, struct, threading, time, zlib
from collections import defaultdict

HELLO_FMT = "<IHHII"          # magic, ver, type, device_id, reserved
FRAME_FMT = "<IHHIIQIII"      # magic, ver, type, device_id, seq, ts_us, payload_len, crc32, flags
HELLO_LEN = struct.calcsize(HELLO_FMT)
FRAME_LEN = struct.calcsize(FRAME_FMT)

MAGIC_H = 0x48505345  # 'ESPH'
MAGIC_F = 0x46505345  # 'ESPF'
VER = 1
TYPE_HELLO = 1
TYPE_FRAME = 2

def recv_exact(conn, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf

stats_lock = threading.Lock()
stats = defaultdict(lambda: {
    "total": 0,
    "lost": 0,
    "crc_err": 0,
    "expected_seq": None,
    "last_ts": None,
    "last_print": 0.0,
})

def handle_client(conn: socket.socket, addr):
    try:
        hb = recv_exact(conn, HELLO_LEN)
        magic, ver, typ, device_id, _ = struct.unpack(HELLO_FMT, hb)
        if magic != MAGIC_H or ver != VER or typ != TYPE_HELLO:
            raise RuntimeError("bad HELLO")
        print(f"[+] {addr} device_id={device_id} connected")

        # 可选：每台设备单独存文件（原始帧流）
        f = open(f"dev_{device_id}.bin", "ab", buffering=0)

        while True:
            hdr = recv_exact(conn, FRAME_LEN)
            magic, ver, typ, dev, seq, ts_us, plen, crc32, flags = struct.unpack(FRAME_FMT, hdr)
            if magic != MAGIC_F or ver != VER or typ != TYPE_FRAME or dev != device_id:
                raise RuntimeError("bad FRAME header")

            payload = recv_exact(conn, plen)
            c = zlib.crc32(payload) & 0xffffffff

            with stats_lock:
                st = stats[device_id]
                st["total"] += 1

                if st["expected_seq"] is None:
                    st["expected_seq"] = seq
                if seq != st["expected_seq"]:
                    if seq > st["expected_seq"]:
                        st["lost"] += (seq - st["expected_seq"])
                    # seq < expected_seq：可能重连/复位/乱序（TCP一般不乱序）
                st["expected_seq"] = seq + 1

                if c != crc32:
                    st["crc_err"] += 1

                st["last_ts"] = ts_us

                now = time.time()
                if now - st["last_print"] > 2.0:
                    st["last_print"] = now
                    print(f"[dev {device_id}] total={st['total']} lost={st['lost']} crc_err={st['crc_err']} last_seq={seq} ts_us={ts_us} plen={plen} flags={flags}")

            # 可选：把 header+payload 原样写盘（之后离线解析）
            f.write(hdr)
            f.write(payload)

    except (EOFError, ConnectionResetError):
        print(f"[-] {addr} disconnected")
    except Exception as e:
        print(f"[!] {addr} error: {e}")
    finally:
        try:
            conn.close()
        except:
            pass

def main(host="0.0.0.0", port=9000):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((host, port))
    s.listen(20)  # <=10台足够
    print(f"PC server listening on {host}:{port}")

    while True:
        conn, addr = s.accept()
        t = threading.Thread(target=handle_client, args=(conn, addr), daemon=True)
        t.start()

if __name__ == "__main__":
    main()