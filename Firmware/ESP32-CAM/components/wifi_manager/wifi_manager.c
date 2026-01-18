#include "wifi_manager.h"

#include <string.h>

#include "freertos/event_groups.h"
#include "esp_log.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "lwip/inet.h"

static const char *TAG = "wifi_manager";

static EventGroupHandle_t s_evt;
static int s_retry = 0;
static int s_max_retry = -1;

static esp_netif_t *s_netif = NULL;

#define WIFI_CONNECTED_BIT BIT0

static void on_wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        s_retry = 0;
        esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        xEventGroupClearBits(s_evt, WIFI_CONNECTED_BIT);

        if (s_max_retry < 0 || s_retry < s_max_retry) {
            s_retry++;
            ESP_LOGW(TAG, "disconnected, retry %d/%d", s_retry, s_max_retry);
            esp_wifi_connect();
        } else {
            ESP_LOGE(TAG, "disconnected, reached max_retry=%d", s_max_retry);
        }
    }
}

static void on_ip_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *e = (ip_event_got_ip_t *)data;
        ESP_LOGI(TAG, "got ip: " IPSTR, IP2STR(&e->ip_info.ip));
        xEventGroupSetBits(s_evt, WIFI_CONNECTED_BIT);
    }
}

esp_err_t wifi_manager_start_sta(const wifi_manager_sta_cfg_t *cfg)
{
    if (!cfg || !cfg->ssid) return ESP_ERR_INVALID_ARG;

    if (!s_evt) s_evt = xEventGroupCreate();

    s_max_retry = cfg->max_retry;

    // 允许重复调用：若已初始化过 event loop / netif，这些调用会返回 ESP_OK 或特定错误
    esp_err_t err;

    err = esp_netif_init();
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) return err;

    err = esp_event_loop_create_default();
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) return err;

    if (!s_netif) s_netif = esp_netif_create_default_wifi_sta();

    wifi_init_config_t wcfg = WIFI_INIT_CONFIG_DEFAULT();
    err = esp_wifi_init(&wcfg);
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) return err;

    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, &on_wifi_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, &on_ip_event, NULL));

    wifi_config_t sta = { 0 };
    strncpy((char *)sta.sta.ssid, cfg->ssid, sizeof(sta.sta.ssid));
    if (cfg->password) strncpy((char *)sta.sta.password, cfg->password, sizeof(sta.sta.password));

    // 可按需调整：如果你要兼容开放网络，去掉 threshold 这行
    sta.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
    sta.sta.pmf_cfg.capable = true;
    sta.sta.pmf_cfg.required = false;

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &sta));
    ESP_ERROR_CHECK(esp_wifi_start());

    ESP_LOGI(TAG, "wifi sta start, ssid=%s", cfg->ssid);

    if (cfg->wait_for_ip) {
        return wifi_manager_wait_connected(cfg->timeout);
    }
    return ESP_OK;
}

esp_err_t wifi_manager_wait_connected(TickType_t timeout)
{
    EventBits_t bits = xEventGroupWaitBits(
        s_evt, WIFI_CONNECTED_BIT, pdFALSE, pdTRUE, timeout
    );
    return (bits & WIFI_CONNECTED_BIT) ? ESP_OK : ESP_ERR_TIMEOUT;
}

bool wifi_manager_is_connected(void)
{
    if (!s_evt) return false;
    return (xEventGroupGetBits(s_evt) & WIFI_CONNECTED_BIT) != 0;
}

bool wifi_manager_get_ip_str(char out[16])
{
    if (!out) return false;

    esp_netif_ip_info_t ip;
    if (!s_netif) return false;
    if (esp_netif_get_ip_info(s_netif, &ip) != ESP_OK) return false;
    if (ip.ip.addr == 0) return false;

    // inet_ntoa 返回静态缓冲，这里复制出来
    const char *s = inet_ntoa(ip.ip);
    if (!s) return false;
    strncpy(out, s, 16);
    out[15] = '\0';
    return true;
}

esp_err_t wifi_manager_stop(void)
{
    xEventGroupClearBits(s_evt, WIFI_CONNECTED_BIT);
    return esp_wifi_stop();
}