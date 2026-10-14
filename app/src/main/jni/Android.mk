LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := BeariteCore
LOCAL_SRC_FILES := main.cpp
LOCAL_LDLIBS    := -llog -landroid
LOCAL_CFLAGS    := -std=c++11 -fvisibility=hidden
include $(BUILD_SHARED_LIBRARY)
