#pragma once
#include "esp_err.h"
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const char *server_ip;     // PC IP, e.g. "192.168.8.10"
    uint16_t server_port;      // e.g. 9000
    uint32_t device_id;        // <= 0: auto from MAC
    int send_timeout_ms;       // e.g. 5000
    int reconnect_delay_ms;    // base delay, e.g. 500
    int max_backoff_ms;        // e.g. 5000
} tcp_stream_cfg_t;

/**
 * Start TCP client streaming task (connect -> HELLO -> send frames).
 */
esp_err_t tcp_client_stream_start(const tcp_stream_cfg_t *cfg);

#ifdef __cplusplus
}
#endif