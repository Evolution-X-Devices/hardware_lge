#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Overlays
PRODUCT_PACKAGES += \
    FrameworkResOverlayLgeUdfps \
    SystemUIOverlayLgeUdfps

# Fingerprint
PRODUCT_PACKAGES += \
    android.hardware.biometrics.fingerprint-service.lge \
    sensors.lge

$(call soong_config_set,surfaceflinger,udfps_lib,//hardware/lge:libudfps_extension.lge)
