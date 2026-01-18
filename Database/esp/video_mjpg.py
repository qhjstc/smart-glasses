import os
import struct
import subprocess
import sys
from pathlib import Path
from shutil import which

# 协议常量
FRAME_FMT = "<IHHIIQIII"
FRAME_LEN = struct.calcsize(FRAME_FMT)
MAGIC_F = 0x46505345  # 'ESPF'
VER = 1
TYPE_FRAME = 2

def parse_mjpeg(bin_path, out_dir):
    out_dir = Path(out_dir)
    bin_path = Path(bin_path)

    out_dir.mkdir(parents=True, exist_ok=True)
    frames_dir = out_dir / "frames"
    frames_dir.mkdir(parents=True, exist_ok=True)

    # concat 文件是实现 VFR 的关键
    concat_path = out_dir / "ffmpeg_concat.txt"
    frame_info = []

    with bin_path.open("rb") as f:
        i = 0
        while True:
            hdr = f.read(FRAME_LEN)
            if not hdr: break
            if len(hdr) != FRAME_LEN: break

            magic, ver, typ, dev, seq, ts_us, plen, crc32, flags = struct.unpack(FRAME_FMT, hdr)
            
            if magic != MAGIC_F:
                print(f"Warning: skip bad magic at frame {i}")
                continue

            payload = f.read(plen)
            if len(payload) != plen: break

            # 保存图片
            fn = f"frame_{i:06d}.jpg"
            frame_path = frames_dir / fn
            frame_path.write_bytes(payload)

            # 记录路径和硬件时间戳
            frame_info.append({
                'path': frame_path.as_posix(), # FFmpeg 在 Windows 下也建议用正斜杠
                'ts': ts_us
            })
            i += 1

    if not frame_info:
        raise RuntimeError("未检测到有效帧数据")

    # 生成 FFmpeg concat 脚本
    # 每一行的 duration = (下一帧时间戳 - 当前帧时间戳)
    with concat_path.open("w", encoding="utf-8") as cf:
        for idx in range(len(frame_info) - 1):
            curr_ts = frame_info[idx]['ts']
            next_ts = frame_info[idx+1]['ts']
            
            # 处理硬件时间戳溢出或重启的情况
            if next_ts < curr_ts:
                duration_sec = 1.0 / 15.0 # 异常时回退到默认（如15fps）
            else:
                duration_sec = (next_ts - curr_ts) / 1_000_000.0

            cf.write(f"file '{frame_info[idx]['path']}'\n")
            cf.write(f"duration {duration_sec:.6f}\n")
        
        # 最后一帧没有下一帧对比，手动补一个时长
        cf.write(f"file '{frame_info[-1]['path']}'\n")
        cf.write(f"duration {1.0/15.0:.6f}\n")

    print(f"OK: 提取完成，共 {len(frame_info)} 帧，已生成 concat 脚本。")
    return concat_path


def find_ffmpeg():
    p = which("ffmpeg")
    if p: return p
    env_dir = Path(sys.executable).resolve().parent
    cand = env_dir / "Library" / "bin" / "ffmpeg.exe"
    if cand.exists(): return str(cand)
    raise RuntimeError("找不到 ffmpeg，请先安装。")


def frames_to_vfr_mp4(concat_path, out_mp4):
    out_mp4 = Path(out_mp4)
    out_mp4.parent.mkdir(parents=True, exist_ok=True)

    ffmpeg = find_ffmpeg()
    
    # 使用 concat 分离器合成视频
    # -safe 0 是为了允许读取任意路径
    # -vsync vfr 强制使用变帧率模式
    cmd = [
        ffmpeg, "-y",
        "-f", "concat",
        "-safe", "0",
        "-i", str(concat_path),
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-vsync", "vfr", 
        str(out_mp4),
    ]
    
    print("正在调用 FFmpeg 合成变帧率视频...")
    subprocess.run(cmd, check=True)
    print("OK: 视频已生成 ->", out_mp4)


def main():
    # 配置
    base = Path(__file__).resolve().parent
    bin_file = "dev_199651824.bin" # 你的原始文件
    bin_path = base / bin_file
    out_dir = base / f"out_{Path(bin_file).stem}"
    
    if not bin_path.exists():
        print(f"Error: 找不到文件 {bin_path}")
        return

    # 1. 提取帧并计算时间间隔
    concat_script = parse_mjpeg(bin_path, out_dir)
    
    # 2. 合成变帧率视频
    frames_to_vfr_mp4(concat_script, out_dir / "video_vfr.mp4")


if __name__ == "__main__":
    main()