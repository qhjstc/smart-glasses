#pragma once

#include "esp_err.h"
#include "freertos/FreeRTOS.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const char *ssid;
    const char *password;

    int  max_retry;        // 断线最大重连次数；-1 表示无限重试
    bool wait_for_ip;      // start时是否阻塞等待拿到IP
    TickType_t timeout;    // wait_for_ip=true时的等待超时
} wifi_manager_sta_cfg_t;

/**
 * @brief 启动 WiFi STA 并连接（内部包含 esp_netif / event_loop 初始化与事件注册）
 */
esp_err_t wifi_manager_start_sta(const wifi_manager_sta_cfg_t *cfg);

/**
 * @brief 阻塞等待连接成功并拿到 IP
 * @return ESP_OK / ESP_ERR_TIMEOUT
 */
esp_err_t wifi_manager_wait_connected(TickType_t timeout);

/**
 * @brief 是否已连接并拿到 IP
 */
bool wifi_manager_is_connected(void);

/**
 * @brief 获取当前 IP 字符串（如 "192.168.1.123"）
 * @return true=成功，false=当前无IP
 */
bool wifi_manager_get_ip_str(char out[16]);

/**
 * @brief 停止 WiFi（可选）
 */
esp_err_t wifi_manager_stop(void);

#ifdef __cplusplus
}
#endif