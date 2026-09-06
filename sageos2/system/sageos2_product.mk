# SageOS 2 product integration fragment.
# Include from the device/product makefile once the VASOUN Android tree is mounted.

PRODUCT_PACKAGES += \
    sage-rootd

BOARD_SEPOLICY_DIRS += \
    $(LOCAL_PATH)/sepolicy/private

# The Sage APK itself must be installed as a platform-signed privileged app with
# package com.pineapple.sagecommander.stable so seapp_contexts maps it to sage_app.
