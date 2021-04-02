#BOARD_PLAT_PUBLIC_SEPOLICY_DIRS += \
#    $(DEVICE_PATH)/sepolicy/public

BOARD_PLAT_PRIVATE_SEPOLICY_DIR += \
    $(DEVICE_PATH)/sepolicy/private

# NXP Sepolicy
BOARD_SEPOLICY_DIRS += \
    $(DEVICE_PATH)/sepolicy/vendor/nxp

# Qcom Sepolicy
BOARD_SEPOLICY_DIRS += \
	$(DEVICE_PATH)/sepolicy/vendor/qcom

# Xiaomi Sepolicy
BOARD_SEPOLICY_DIRS += \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/audio \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/battery \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/camera \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/fingerprint \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/fod \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/last_kmsg \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/light \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/motor \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/mlipay \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/parts \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/power \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/radio \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/thermald \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/usb \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/wireless \
    $(DEVICE_PATH)/sepolicy/vendor/xiaomi/wlan
