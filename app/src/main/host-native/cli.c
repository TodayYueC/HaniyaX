#define _GNU_SOURCE
#include "wire.h"

#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <termios.h>

#ifndef HX_DESCRIPTOR
#define HX_DESCRIPTOR "/opt/haniyax/.host-bridge"
#endif

static const char usage[] =
    "Usage: haniyax host [--timeout SECONDS] [--root] COMMAND\n"
    "\n"
    "  status | info                Show live Android host access (JSON)\n"
    "  shell [SCRIPT]               Run as the Android HaniyaX app UID\n"
    "  root [SCRIPT]                Run as Android UID 0 when Root access is enabled\n"
    "  id | getprop [NAME] | apps   Query Android identity, properties or packages\n"
    "  logcat                       Stream Android logs until canceled\n"
    "  push LOCAL HOST              Copy a Linux file to Android, replacing the destination\n"
    "  pull HOST LOCAL              Copy an Android file to Linux, replacing the destination\n"
    "  install APK                  Install using Android Root access; requires confirmation\n"
    "  settings list NAMESPACE\n"
    "  settings get NAMESPACE KEY\n"
    "  settings put NAMESPACE KEY VALUE\n"
    "  settings delete NAMESPACE KEY\n"
    "\n"
    "Namespaces: system, secure, global. Use --root before a structured command to request UID 0.\n"
    "With no SCRIPT, shell/root open an interactive Android terminal. Ctrl-C cancels piped calls.\n"
    "Host paths for push/pull: /sdcard or /data/local/tmp (subject to Android permissions).\n"
    "Approve requests in HaniyaX Settings > Android host access. See /opt/haniyax/docs/android-host.md.\n";

static volatile sig_atomic_t interrupted;
static volatile sig_atomic_t resized;

static void on_signal(int signal_number) {
    if (signal_number == SIGWINCH) resized = 1;
    else interrupted = signal_number;
}

static void watch_signals(void) {
    struct sigaction sa = { .sa_handler = on_signal };
    sigemptyset(&sa.sa_mask);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sigaction(SIGQUIT, &sa, NULL);
    sigaction(SIGWINCH, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);
}

static bool write_all(int fd, const void *data, size_t length) {
    const unsigned char *buf = data;
    while (length && !interrupted) {
        ssize_t n = write(fd, buf, length);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        length -= n;
        buf += n;
    }
    return !length;
}

static uint32_t terminal_size(void) {
    struct winsize ws;
    if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) || !ws.ws_row || !ws.ws_col) return (24U << 16) | 80;
    return ((uint32_t)ws.ws_row << 16) | ws.ws_col;
}

static int bad_usage(const char *message) {
    fprintf(stderr, "haniyax host: %s\n%s", message, usage);
    return 2;
}

struct options {
    const char *args[8];
    int count;
    uint32_t timeout;
    bool root;
    bool tty;
    const char *upload;
    const char *download;
    char length[32];
};

static bool parse(int argc, char **argv, struct options *o) {
    int i = 2;
    for (; i < argc; ++i) {
        if (!strcmp(argv[i], "--root") && !o->root) o->root = true;
        else if (!strcmp(argv[i], "--timeout") && !o->timeout && i + 1 < argc) {
            char *end;
            errno = 0;
            unsigned long secs = strtoul(argv[++i], &end, 10);
            if (errno || *end || !secs || secs > 3600) return false;
            o->timeout = secs;
        } else break;
    }
    if (i == argc || argc - i > 5) return false;
    const char *command = argv[i];
    o->count = argc - i;
    for (int n = 0; n < o->count; ++n) o->args[n] = argv[i + n];
    if (!strcmp(command, "shell") || !strcmp(command, "root")) {
        if (o->root || o->count > 2) return false;
        o->tty = o->count == 1;
    } else if (!strcmp(command, "push")) {
        if (o->count != 3) return false;
        o->upload = o->args[1];
        o->args[1] = o->args[2];
        o->args[2] = o->length;
    } else if (!strcmp(command, "install")) {
        if (o->count != 2) return false;
        o->upload = o->args[1];
        const char *slash = strrchr(o->upload, '/');
        o->args[1] = o->length;
        o->args[2] = slash ? slash + 1 : o->upload;
        o->count = 3;
    } else if (!strcmp(command, "pull")) {
        if (o->count != 3) return false;
        o->download = o->args[2];
        o->count = 2;
    } else if (!strcmp(command, "getprop")) {
        if (o->count > 2) return false;
    } else if (!strcmp(command, "settings")) {
        if (o->count < 3) return false;
    } else if (!strcmp(command, "status") || !strcmp(command, "info") || !strcmp(command, "logcat")
               || !strcmp(command, "id") || !strcmp(command, "apps")) {
        if (o->count != 1) return false;
    } else return false;
    for (int n = 0; n < o->count; ++n)
        if (strlen(o->args[n]) > HX_CHUNK) return false;
    return true;
}

static bool send_request(int fd, const char *token, struct options *o) {
    unsigned char h[16];
    put_u32(h, o->timeout);
    put_u32(h + 4, o->root);
    put_u32(h + 8, o->tty ? terminal_size() : 0);
    put_u32(h + 12, o->count);
    if (!write_all(fd, HX_MAGIC, sizeof(HX_MAGIC) - 1) || !write_all(fd, token, HX_TOKEN) ||
        !write_all(fd, h, sizeof(h))) return false;
    for (int k = 0; k < o->count; ++k) {
        size_t len = strlen(o->args[k]);
        put_u32(h, len);
        if (!write_all(fd, h, 4) || !write_all(fd, o->args[k], len)) return false;
    }
    return true;
}

struct buffer {
    unsigned char bytes[HX_HEADER + HX_CHUNK];
    size_t size;
    size_t offset;
};

static void prepare(struct buffer *b, unsigned char type, size_t length) {
    b->bytes[0] = type;
    put_u32(b->bytes + 1, length);
    b->size = HX_HEADER + length;
    b->offset = 0;
}

/* Read exactly one frame without blocking the stdin side of the connection. */
static int read_frame(int sock, struct buffer *b) {
    size_t size = b->size < HX_HEADER ? HX_HEADER : HX_HEADER + get_u32(b->bytes + 1);
    if (size > sizeof(b->bytes)) return -1;
    ssize_t n = read(sock, b->bytes + b->size, size - b->size);
    if (n <= 0) return n < 0 && (errno == EINTR || errno == EAGAIN) ? 0 : -1;
    b->size += n;
    if (b->size < HX_HEADER) return 0;
    size = HX_HEADER + get_u32(b->bytes + 1);
    if (size > sizeof(b->bytes)) return -1;
    return b->size == size;
}

static int relay(int sock, int input, int output, bool tty, bool receive_only) {
    struct buffer incoming = {0}, outgoing = {0};
    bool ready = false, eof = receive_only, raw = false;
    int result = 125;
    struct termios saved;
    if (fcntl(sock, F_SETFL, fcntl(sock, F_GETFL) | O_NONBLOCK)) return 125;
    while (!interrupted) {
        bool tx_empty = outgoing.size == outgoing.offset;
        if (ready && tx_empty && tty && resized) {
            resized = 0;
            prepare(&outgoing, HX_RESIZE, 4);
            put_u32(outgoing.bytes + HX_HEADER, terminal_size());
            tx_empty = false;
        }
        struct pollfd fds[] = {
            { .fd = sock, .events = POLLIN | (tx_empty ? 0 : POLLOUT) },
            { .fd = ready && tx_empty && !eof ? input : -1, .events = POLLIN },
        };
        if (poll(fds, 2, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            int got = read_frame(sock, &incoming);
            if (got < 0) break;
            if (got) {
                unsigned char type = incoming.bytes[0];
                size_t len = incoming.size - HX_HEADER;
                unsigned char *payload = incoming.bytes + HX_HEADER;
                incoming.size = 0;
                if (type == HX_EXIT && len == 4 && get_u32(payload) <= 255) { result = get_u32(payload); break; }
                if (type == HX_READY && !len && !ready) {
                    ready = true;
                    if (tty && tcgetattr(input, &saved) == 0) {
                        struct termios mode = saved;
                        cfmakeraw(&mode);
                        raw = tcsetattr(input, TCSANOW, &mode) == 0;
                        resized = 1;
                    }
                } else if (type == HX_OUT || type == HX_ERR || type == HX_NOTICE) {
                    if (!write_all(type == HX_OUT ? output : STDERR_FILENO, payload, len)) break;
                } else break;
            }
        }
        if (fds[0].revents & POLLOUT) {
            ssize_t n = write(sock, outgoing.bytes + outgoing.offset, outgoing.size - outgoing.offset);
            if (n > 0) outgoing.offset += n;
            else if (n < 0 && errno != EINTR && errno != EAGAIN) break;
        }
        if (fds[1].revents) {
            ssize_t n = read(input, outgoing.bytes + HX_HEADER, HX_CHUNK);
            if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            if (n < 0) { perror("read Linux input"); break; }
            if (!n) { eof = true; prepare(&outgoing, HX_EOF, 0); }
            else prepare(&outgoing, HX_DATA, n);
        }
    }
    if (raw) tcsetattr(input, TCSANOW, &saved);
    if (interrupted) return 128 + interrupted;
    if (result == 125) fputs("haniyax host: bridge disconnected or returned an invalid response\n", stderr);
    return result;
}

static bool connection_details(char *name, size_t capacity, char token[HX_TOKEN + 2]) {
    int fd = open(HX_DESCRIPTOR, O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st;
    FILE *descriptor = NULL;
    if (!fstat(fd, &st) && S_ISREG(st.st_mode)) descriptor = fdopen(fd, "r");
    if (!descriptor) { close(fd); return false; }
    bool valid = fgets(name, capacity, descriptor) && fgets(token, HX_TOKEN + 2, descriptor);
    fclose(descriptor);
    if (!valid) return false;
    size_t size = strlen(name);
    if (size < 2 || name[size - 1] != '\n' || strlen(token) != HX_TOKEN + 1 || token[HX_TOKEN] != '\n')
        return false;
    name[size - 1] = token[HX_TOKEN] = '\0';
    return true;
}

int main(int argc, char **argv) {
    if (argc < 2) return bad_usage("expected 'host'");
    if (!strcmp(argv[1], "--help") || !strcmp(argv[1], "-h") ||
        (!strcmp(argv[1], "host") && (argc == 2 || (argc == 3 && (!strcmp(argv[2], "--help") || !strcmp(argv[2], "-h")))))) {
        fputs(usage, stdout);
        return 0;
    }
    struct options options = {0};
    if (strcmp(argv[1], "host") || !parse(argc, argv, &options)) return bad_usage("invalid command or arguments");
    char endpoint[sizeof(((struct sockaddr_un *)0)->sun_path)];
    char token[HX_TOKEN + 2];
    bool status = !strcmp(options.args[0], "status") || !strcmp(options.args[0], "info");
    watch_signals();
    int sock = -1;
    if (connection_details(endpoint, sizeof(endpoint), token)) sock = hx_connect(endpoint);
    if (sock < 0) {
        if (status) puts("{\"available\":false,\"mode\":\"unavailable\",\"environment\":\"linux-workspace\"}");
        else fputs("haniyax host: Android host bridge unavailable. Open HaniyaX and check Android host access.\n", stderr);
        return 3;
    }

    int in = STDIN_FILENO, out = STDOUT_FILENO;
    int result = 1;
    char *temp = NULL;
    if (options.upload) {
        in = open(options.upload, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        struct stat st;
        if (in < 0) { perror("open Linux file"); goto finish; }
        if (fstat(in, &st) || !S_ISREG(st.st_mode) || st.st_size > HX_MAX_FILE) {
            fputs("haniyax host: expected a regular Linux file of at most 1 GiB\n", stderr);
            goto finish;
        }
        snprintf(options.length, sizeof(options.length), "%lld", (long long)st.st_size);
    }
    if (options.download) {
        size_t capacity = strlen(options.download) + sizeof(".haniyax-XXXXXX");
        temp = malloc(capacity);
        if (!temp) goto finish;
        snprintf(temp, capacity, "%s.haniyax-XXXXXX", options.download);
        out = mkstemp(temp);
        if (out < 0) { perror("create Linux file"); goto finish; }
    }
    if (!send_request(sock, token, &options)) {
        fputs("haniyax host: could not send request to Android\n", stderr);
        goto finish;
    }
    bool input_needed = options.upload || !strcmp(options.args[0], "shell") || !strcmp(options.args[0], "root");
    result = relay(sock, in, out, options.tty, !input_needed);
    if (temp && result == 0 && (fsync(out) || rename(temp, options.download))) {
        perror("replace Linux file");
        result = 1;
    }
finish:
    close(sock);
    if (options.upload && in >= 0) close(in);
    if (temp) {
        if (out >= 0) { close(out); unlink(temp); }
        free(temp);
    }
    return interrupted ? 128 + interrupted : result;
}
