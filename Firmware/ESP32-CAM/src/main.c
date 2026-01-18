#include "nvs_flash.h"
#include "esp_log.h"

#include "wifi_manager.h"
#include "cam_ov2640.h"

#include "web_server.h"
#include "tcp_client_stream.h"

#include "icm42688.h"
#if ICM42688_USE_I2C  
#include "driver/i2c_master.h"  
#elif ICM42688_USE_SPI  
#include "driver/spi_master.h"  
#endif  

#if ICM42688_USE_I2C

static i2c_master_bus_handle_t i2c_bus_init(void)
{
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = 21,   // TODO: 改成你的 SDA
        .scl_io_num = 22,   // TODO: 改成你的 SCL
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };

    i2c_master_bus_handle_t bus = NULL;
    ESP_ERROR_CHECK(i2c_new_master_bus(&bus_cfg, &bus));
    return bus;
}

#elif ICM42688_USE_SPI

static void spi_bus_init_icm(void)
{
    spi_bus_config_t buscfg = {
        .mosi_io_num = 23,  // TODO: 改成你的 MOSI
        .miso_io_num = 19,  // TODO: 改成你的 MISO
        .sclk_io_num = 18,  // TODO: 改成你的 SCLK
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 64,
    };
    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &buscfg, SPI_DMA_CH_AUTO));
}

#endif


// #define WIFI_QHJ

#ifndef WIFI_QHJ
    #define WIFI_SSID "3Broadband_1003"
    #define WIFI_PASS "DL3hYn5ngBD"
#else
    #define WIFI_SSID "qhjstc"
    #define WIFI_PASS "qhj12345678"
#endif

void app_main(void)
{

    icm42688_t imu = {0};

#if ICM42688_USE_I2C
    i2c_master_bus_handle_t bus = i2c_bus_init();
    ESP_ERROR_CHECK(icm42688_init_i2c(&imu, bus, 0x68)); // 0x68/0x69 视硬件而定

#elif ICM42688_USE_SPI
    spi_bus_init_icm();
    ESP_ERROR_CHECK(icm42688_init_spi(&imu, SPI2_HOST, 5 /*CS GPIO*/, 10 * 1000 * 1000 /*10MHz*/));
#endif

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ESP_ERROR_CHECK(nvs_flash_init());
    }

    cam_ov2640_cfg_t cam_cfg = {
        .framesize = FRAMESIZE_QVGA,
        .jpeg_quality = 12,
        .fb_count = 2,
        .grab_latest = true,
    };
    ESP_ERROR_CHECK(cam_ov2640_init(&cam_cfg));

    wifi_manager_sta_cfg_t wcfg = {
        .ssid = WIFI_SSID,
        .password = WIFI_PASS,
        .max_retry = -1,
        .wait_for_ip = true,
        .timeout = portMAX_DELAY,
    };
    ESP_ERROR_CHECK(wifi_manager_start_sta(&wcfg));

    char ip[16];
    if (wifi_manager_get_ip_str(ip)) {
        ESP_LOGI("app", "got ip: %s", ip);
    }

    // start_webserver();

    // 启动 TCP client 推流到 PC
    tcp_stream_cfg_t scfg = {
#ifndef WIFI_QHJ
        .server_ip = "192.168.8.40", // 改成你的 PC IP
#else
        .server_ip = "192.168.43.158", 
#endif
        .server_port = 9000,
        .device_id = 0,              // 0=自动从 MAC 派生；也可以手动填 1..10
        .send_timeout_ms = 5000,
        .reconnect_delay_ms = 500,
        .max_backoff_ms = 5000,
    };
    ESP_ERROR_CHECK(tcp_client_stream_start(&scfg));

    while (1) vTaskDelay(pdMS_TO_TICKS(1000));
}


