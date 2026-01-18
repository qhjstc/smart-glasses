#include "ics41351.h"

#if USE_ICS41351

#include "driver/i2s_std.h"
#include "esp_check.h"

static const char *TAG = "ics41351";

esp_err_t ics41351_init(ics41351_t *m)
{
    ESP_RETURN_ON_FALSE(m, ESP_ERR_INVALID_ARG, TAG, "null handle");
    ESP_RETURN_ON_FALSE(m->bclk_gpio >= 0 && m->ws_gpio >= 0 && m->din_gpio >= 0, ESP_ERR_INVALID_ARG, TAG, "bad gpio");

    // 创建 RX 通道
    i2s_chan_handle_t rx_chan = NULL;
    i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    ESP_RETURN_ON_ERROR(i2s_new_channel(&chan_cfg, NULL, &rx_chan), TAG, "i2s_new_channel");
    m->i2s_chan = (void *)rx_chan;

    // 标准 I2S 接口配置（很多 ICS-41351 模块也以 I2S 形式输出）
    // 如果你确定是 PDM 输出，需要改为 PDM RX 驱动配置（我可以按你的接线/模块给你改）
    i2s_std_config_t std_cfg = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(m->sample_rate),
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(
            (m->bits == 16) ? I2S_DATA_BIT_WIDTH_16BIT : I2S_DATA_BIT_WIDTH_32BIT,
            (m->channels == 2) ? I2S_SLOT_MODE_STEREO : I2S_SLOT_MODE_MONO
        ),
        .gpio_cfg = {
            .mclk = I2S_GPIO_UNUSED,
            .bclk = m->bclk_gpio,
            .ws   = m->ws_gpio,
            .dout = I2S_GPIO_UNUSED,
            .din  = m->din_gpio,
            .invert_flags = {
                .mclk_inv = false,
                .bclk_inv = false,
                .ws_inv   = false,
            },
        },
    };

    ESP_RETURN_ON_ERROR(i2s_channel_init_std_mode(rx_chan, &std_cfg), TAG, "init std mode");
    return ESP_OK;
}

esp_err_t ics41351_start(ics41351_t *m)
{
    ESP_RETURN_ON_FALSE(m && m->i2s_chan, ESP_ERR_INVALID_ARG, TAG, "not inited");
    return i2s_channel_enable((i2s_chan_handle_t)m->i2s_chan);
}

esp_err_t ics41351_stop(ics41351_t *m)
{
    ESP_RETURN_ON_FALSE(m && m->i2s_chan, ESP_ERR_INVALID_ARG, TAG, "not inited");
    return i2s_channel_disable((i2s_chan_handle_t)m->i2s_chan);
}

esp_err_t ics41351_deinit(ics41351_t *m)
{
    ESP_RETURN_ON_FALSE(m && m->i2s_chan, ESP_ERR_INVALID_ARG, TAG, "not inited");
    esp_err_t err = i2s_del_channel((i2s_chan_handle_t)m->i2s_chan);
    m->i2s_chan = NULL;
    return err;
}

esp_err_t ics41351_read(ics41351_t *m, void *dst, size_t dst_bytes, size_t *out_bytes, uint32_t timeout_ms)
{
    ESP_RETURN_ON_FALSE(m && m->i2s_chan && dst, ESP_ERR_INVALID_ARG, TAG, "bad args");
    size_t br = 0;
    esp_err_t err = i2s_channel_read((i2s_chan_handle_t)m->i2s_chan, dst, dst_bytes, &br, pdMS_TO_TICKS(timeout_ms));
    if (out_bytes) *out_bytes = br;
    return err;
}

#else
// 若禁用麦克风，提供空实现，避免链接报错
esp_err_t ics41351_init(ics41351_t *m){ (void)m; return ESP_ERR_NOT_SUPPORTED; }
esp_err_t ics41351_start(ics41351_t *m){ (void)m; return ESP_ERR_NOT_SUPPORTED; }
esp_err_t ics41351_stop(ics41351_t *m){ (void)m; return ESP_ERR_NOT_SUPPORTED; }
esp_err_t ics41351_deinit(ics41351_t *m){ (void)m; return ESP_ERR_NOT_SUPPORTED; }
esp_err_t ics41351_read(ics41351_t *m, void *dst, size_t dst_bytes, size_t *out_bytes, uint32_t timeout_ms)
{ (void)m; (void)dst; (void)dst_bytes; if(out_bytes) *out_bytes=0; (void)timeout_ms; return ESP_ERR_NOT_SUPPORTED; }
#endif