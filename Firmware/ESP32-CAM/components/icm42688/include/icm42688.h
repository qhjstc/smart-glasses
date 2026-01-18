#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

#ifndef ICM42688_USE_I2C
#define ICM42688_USE_I2C 1
#endif
#ifndef ICM42688_USE_SPI
#define ICM42688_USE_SPI 0
#endif

#if (ICM42688_USE_I2C + ICM42688_USE_SPI) != 1
#error "Define exactly one: ICM42688_USE_I2C=1 or ICM42688_USE_SPI=1"
#endif

#if ICM42688_USE_I2C
#include "driver/i2c_master.h"
#elif ICM42688_USE_SPI
#include "driver/spi_master.h"
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
#if ICM42688_USE_I2C
    i2c_master_bus_handle_t bus;
    uint8_t i2c_addr;                    // 常用 0x68 或 0x69
    i2c_master_dev_handle_t dev;
#elif ICM42688_USE_SPI
    spi_host_device_t host;
    spi_device_handle_t dev;
    int cs_gpio;
#endif
} icm42688_t;

typedef struct {
    int16_t ax, ay, az;                  // raw
    int16_t gx, gy, gz;                  // raw
    int16_t temp;                        // raw
} icm42688_raw_t;

#if ICM42688_USE_I2C
esp_err_t icm42688_init_i2c(icm42688_t *imu, i2c_master_bus_handle_t bus, uint8_t addr);
#elif ICM42688_USE_SPI
esp_err_t icm42688_init_spi(icm42688_t *imu, spi_host_device_t host, int cs_gpio, int clock_hz);
#endif

esp_err_t icm42688_deinit(icm42688_t *imu);

esp_err_t icm42688_whoami(icm42688_t *imu, uint8_t *whoami);
esp_err_t icm42688_config_default(icm42688_t *imu);
esp_err_t icm42688_read_raw(icm42688_t *imu, icm42688_raw_t *out);

#ifdef __cplusplus
}
#endif