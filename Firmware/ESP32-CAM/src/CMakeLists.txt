# src/CMakeLists.txt

idf_component_register(
    SRCS "main.c" "web_server.c" "tcp_client_stream.c"
    INCLUDE_DIRS "."
    REQUIRES wifi_manager cam_ov2640 esp_http_server nvs_flash esp_timer driver icm42688
)