#ifndef HX_HOST_WIRE_H
#define HX_HOST_WIRE_H

#include <errno.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

/* Big-endian length framing. HostWire.kt implements the same protocol. */
enum { HX_DATA = 1, HX_EOF, HX_RESIZE, HX_OUT, HX_ERR, HX_EXIT, HX_READY, HX_NOTICE, HX_START };
enum { HX_CHUNK = 32768, HX_HEADER = 5, HX_TOKEN = 64 };
#define HX_MAGIC "HXHB1"
#define HX_MAX_FILE (1024LL * 1024 * 1024)

static inline uint32_t get_u32(const unsigned char *b) {
    return (uint32_t)b[0] << 24 | (uint32_t)b[1] << 16 | (uint32_t)b[2] << 8 | b[3];
}

static inline void put_u32(unsigned char *b, uint32_t n) {
    b[0] = n >> 24;
    b[1] = n >> 16;
    b[2] = n >> 8;
    b[3] = n;
}

static inline int hx_connect(const char *name) {
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    size_t length = strlen(name);
    if (!length || length >= sizeof(addr.sun_path)) {
        errno = EINVAL;
        return -1;
    }
    memcpy(addr.sun_path + 1, name, length);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    if (connect(fd, (struct sockaddr *)&addr, offsetof(struct sockaddr_un, sun_path) + 1 + length) < 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}
#endif
