#include "icm42688.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// --------- Registers (按 ICM42688/ICM42688P 常见映射) ----------
#define ICM_REG_DEVICE_CONFIG     0x11
#define ICM_REG_PWR_MGMT0         0x4E
#define ICM_REG_GYRO_CONFIG0      0x4F
#define ICM_REG_ACCEL_CONFIG0     0x50
#define ICM_REG_WHO_AM_I          0x75

#define ICM_REG_TEMP_DATA1        0x1D // TEMP(2) + ACC(6) + GYRO(6) = 14 bytes

// --------- Bus abstraction ----------
static esp_err_t reg_write(icm42688_t *imu, uint8_t reg, uint8_t val);
static esp_err_t reg_read(icm42688_t *imu, uint8_t reg, uint8_t *data, size_t len);

#if ICM42688_USE_I2C

esp_err_t icm42688_init_i2c(icm42688_t *imu, i2c_master_bus_handle_t bus, uint8_t addr)
{
    if (!imu || !bus) return ESP_ERR_INVALID_ARG;
    imu->bus = bus;
    imu->i2c_addr = addr;

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = addr,
        .scl_speed_hz = 400000,
    };
    return i2c_master_bus_add_device(bus, &dev_cfg, &imu->dev);
}

esp_err_t icm42688_deinit(icm42688_t *imu)
{
    if (!imu || !imu->dev) return ESP_ERR_INVALID_ARG;
    esp_err_t err = i2c_master_bus_rm_device(imu->dev);
    imu->dev = NULL;
    return err;
}

static esp_err_t reg_write(icm42688_t *imu, uint8_t reg, uint8_t val)
{
    uint8_t buf[2] = {reg, val};
    return i2c_master_transmit(imu->dev, buf, sizeof(buf), -1);
}

static esp_err_t reg_read(icm42688_t *imu, uint8_t reg, uint8_t *data, size_t len)
{
    return i2c_master_transmit_receive(imu->dev, &reg, 1, data, len, -1);
}

#elif ICM42688_USE_SPI

// SPI 读写：MSB=1 表示读，MSB=0 表示写（InvenSense 常见协议）
// 若你板子/手册要求不同，我再按你的芯片版本修正。
#define SPI_READ_BIT  0x80
#define SPI_WRITE_BIT 0x00

esp_err_t icm42688_init_spi(icm42688_t *imu, spi_host_device_t host, int cs_gpio, int clock_hz)
{
    if (!imu) return ESP_ERR_INVALID_ARG;
    imu->host = host;
    imu->cs_gpio = cs_gpio;

    spi_device_interface_config_t devcfg = {
        .clock_speed_hz = clock_hz,
        .mode = 0,
        .spics_io_num = cs_gpio,
        .queue_size = 1,
    };
    return spi_bus_add_device(host, &devcfg, &imu->dev);
}

esp_err_t icm42688_deinit(icm42688_t *imu)
{
    if (!imu || !imu->dev) return ESP_ERR_INVALID_ARG;
    esp_err_t err = spi_bus_remove_device(imu->dev);
    imu->dev = NULL;
    return err;
}

static esp_err_t reg_write(icm42688_t *imu, uint8_t reg, uint8_t val)
{
    uint8_t tx[2] = {(uint8_t)(reg | SPI_WRITE_BIT), val};
    spi_transaction_t t = {
        .length = 8 * sizeof(tx),
        .tx_buffer = tx,
    };
    return spi_device_transmit(imu->dev, &t);
}

static esp_err_t reg_read(icm42688_t *imu, uint8_t reg, uint8_t *data, size_t len)
{
    // 发送 [reg|READ] + dummy，然后读回 len 字节
    uint8_t cmd = (uint8_t)(reg | SPI_READ_BIT);

    spi_transaction_t t = {0};
    t.length = 8;                // 发 1 byte 命令
    t.tx_buffer = &cmd;
    esp_err_t err = spi_device_transmit(imu->dev, &t);
    if (err != ESP_OK) return err;

    spi_transaction_t tr = {0};
    tr.length = 8 * len;
    tr.rxlength = 8 * len;
    tr.rx_buffer = data;
    return spi_device_transmit(imu->dev, &tr);
}

#endif

// --------- Common API ----------
static inline int16_t be16(const uint8_t *p)
{
    return (int16_t)((p[0] << 8) | p[1]);
}

esp_err_t icm42688_whoami(icm42688_t *imu, uint8_t *whoami)
{
    if (!imu || !whoami) return ESP_ERR_INVALID_ARG;
    return reg_read(imu, ICM_REG_WHO_AM_I, whoami, 1);
}

esp_err_t icm42688_config_default(icm42688_t *imu)
{
    if (!imu) return ESP_ERR_INVALID_ARG;

    // 软复位
    ESP_ERROR_CHECK(reg_write(imu, ICM_REG_DEVICE_CONFIG, 0x01));
    vTaskDelay(pdMS_TO_TICKS(10));

    // 打开 accel+gyro（示例值，若 WHO_AM_I/版本不同可再按表精调）
    ESP_ERROR_CHECK(reg_write(imu, ICM_REG_PWR_MGMT0, 0x0F));
    vTaskDelay(pdMS_TO_TICKS(5));

    // 示例：ODR/FS（占位的常用配置；要精确请按你的目标量程/ODR改位）
    ESP_ERROR_CHECK(reg_write(imu, ICM_REG_GYRO_CONFIG0,  0x06));
    ESP_ERROR_CHECK(reg_write(imu, ICM_REG_ACCEL_CONFIG0, 0x06));

    return ESP_OK;
}

esp_err_t icm42688_read_raw(icm42688_t *imu, icm42688_raw_t *out)
{
    if (!imu || !out) return ESP_ERR_INVALID_ARG;

    uint8_t buf[14];
    esp_err_t err = reg_read(imu, ICM_REG_TEMP_DATA1, buf, sizeof(buf));
    if (err != ESP_OK) return err;

    out->temp = be16(&buf[0]);
    out->ax   = be16(&buf[2]);
    out->ay   = be16(&buf[4]);
    out->az   = be16(&buf[6]);
    out->gx   = be16(&buf[8]);
    out->gy   = be16(&buf[10]);
    out->gz   = be16(&buf[12]);
    return ESP_OK;
}