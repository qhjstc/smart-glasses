#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"
#include "esp_camera.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    framesize_t framesize;     // e.g. FRAMESIZE_QVGA / FRAMESIZE_VGA
    int jpeg_quality;          // 0..63 (lower is better quality). Typical: 10-15
    int fb_count;              // 1..2 (2 recommended with PSRAM)
    bool grab_latest;          // when true: prefer latest frame (reduce latency)
} cam_ov2640_cfg_t;

/**
 * Initialize OV2640 camera (AI-Thinker ESP32-CAM pinout by default).
 */
esp_err_t cam_ov2640_init(const cam_ov2640_cfg_t *cfg);

/**
 * Deinitialize camera driver.
 */
esp_err_t cam_ov2640_deinit(void);

/**
 * Get a frame buffer from camera. You must return it with cam_ov2640_fb_return().
 */
camera_fb_t *cam_ov2640_fb_get(void);

/**
 * Return a previously acquired frame buffer.
 */
void cam_ov2640_fb_return(camera_fb_t *fb);

/**
 * Convenience helpers for common sensor tweaks.
 */
esp_err_t cam_ov2640_set_vflip(int vflip);   // 0/1
esp_err_t cam_ov2640_set_hmirror(int hmirror); // 0/1

#ifdef __cplusplus
}
#endif