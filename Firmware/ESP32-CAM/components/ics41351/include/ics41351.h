#pragma once
#include <stdint.h>
#include "esp_err.h"

#ifndef USE_ICS41351
#define USE_ICS41351 1
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    // GPIO
    int bclk_gpio;     // SCK / BCLK
    int ws_gpio;       // WS / LRCLK（PDM 模式下也用于时钟/选择）
    int din_gpio;      // SD / DOUT from mic

    // format
    int sample_rate;   // e.g. 16000, 32000, 48000
    int bits;          // 16 or 32
    int channels;      // 1 or 2 (很多板子实际只接单麦=1)

    // internal
    void *i2s_chan;    // i2s_chan_handle_t (avoid including driver headers here)
} ics41351_t;

esp_err_t ics41351_init(ics41351_t *m);
esp_err_t ics41351_start(ics41351_t *m);
esp_err_t ics41351_stop(ics41351_t *m);
esp_err_t ics41351_deinit(ics41351_t *m);

// 读取 PCM（返回读取到的字节数）
esp_err_t ics41351_read(ics41351_t *m, void *dst, size_t dst_bytes, size_t *out_bytes, uint32_t timeout_ms);

#ifdef __cplusplus
}
#endif