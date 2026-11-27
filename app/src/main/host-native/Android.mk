LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := haniyax_host_runner
LOCAL_SRC_FILES := runner.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE := haniyax_host_cli
LOCAL_SRC_FILES := cli.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
LOCAL_LDFLAGS := -static
include $(BUILD_EXECUTABLE)
