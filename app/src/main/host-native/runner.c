#define _GNU_SOURCE
#include "wire.h"

#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>

struct buffer {
    unsigned char data[HX_HEADER + HX_CHUNK];
    size_t used;
    size_t offset;
};

struct upload {
    int dir;
    int fd;
    char *name;
    char temp[64];
};

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}

static void frame(struct buffer *out, unsigned char type, size_t len) {
    out->data[0] = type;
    put_u32(out->data + 1, len);
    out->used = HX_HEADER + len;
    out->offset = 0;
}

static void flush(int fd, struct buffer *b) {
    ssize_t n = write(fd, b->data + b->offset, b->used - b->offset);
    if (n > 0) b->offset += n;
    else if (n < 0 && errno != EAGAIN && errno != EINTR) b->offset = b->used;
}

static int nonblocking(int fd) {
    return fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
}

/* Each component is opened relative to its verified directory FD. The client never
 * supplies symlinks, relative paths, device nodes, pipes or procfs paths to this helper. */
static int parent_dir(char *path, char **name) {
    if (*path != '/') { errno = EINVAL; return -1; }
    int dir = open("/", O_PATH | O_CLOEXEC | O_DIRECTORY);
    char *cursor = path + 1;
    char *part;
    while (dir >= 0 && (part = strsep(&cursor, "/"))) {
        if (!*part || !strcmp(part, ".") || !strcmp(part, "..")) {
            close(dir);
            errno = EINVAL;
            return -1;
        }
        if (!cursor) { *name = part; return dir; }
        int next = openat(dir, part, O_PATH | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW);
        close(dir);
        dir = next;
    }
    return -1;
}

static int prepare_upload(struct upload *up, char *path) {
    up->dir = parent_dir(path, &up->name);
    if (up->dir < 0) return -1;
    struct stat target;
    if (!fstatat(up->dir, up->name, &target, AT_SYMLINK_NOFOLLOW)) {
        if (!S_ISREG(target.st_mode)) { errno = EINVAL; return -1; }
    } else if (errno != ENOENT) return -1;
    unsigned char entropy[16];
    int rng = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (rng < 0) return -1;
    ssize_t count = read(rng, entropy, sizeof(entropy));
    close(rng);
    if (count != sizeof(entropy)) { errno = EIO; return -1; }
    strcpy(up->temp, ".haniyax-upload-");
    for (size_t i = 0; i < sizeof(entropy); ++i) sprintf(up->temp + 16 + i * 2, "%02x", entropy[i]);
    up->fd = openat(up->dir, up->temp, O_WRONLY | O_CLOEXEC | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    return up->fd;
}

static int transfer(int src, int dst, long long limit, bool exact) {
    unsigned char b[HX_CHUNK];
    for (;;) {
        ssize_t n = read(src, b, sizeof(b));
        if (!n) {
            if (!exact || !limit) return 0;
            fputs("Transfer ended before the declared file size\n", stderr);
            return 1;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) { perror("read file"); return 1; }
        if (n > limit) { fputs("Transfer size limit exceeded\n", stderr); return 1; }
        limit -= n;
        ssize_t written = 0;
        while (written < n) {
            ssize_t m = write(dst, b + written, n - written);
            if (m < 0 && errno == EINTR) continue;
            if (m <= 0) { perror("write file"); return 1; }
            written += m;
        }
    }
}

static int download(char *path) {
    char *name;
    int dir = parent_dir(path, &name);
    if (dir < 0) { perror("open file directory"); return 1; }
    int fd = openat(dir, name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    close(dir);
    if (fd < 0) { perror("open file"); return 1; }
    struct stat st;
    int result = 1;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size > HX_MAX_FILE)
        fputs("Expected a regular host file of at most 1 GiB\n", stderr);
    else result = transfer(fd, STDOUT_FILENO, HX_MAX_FILE, false);
    close(fd);
    return result;
}

static void kill_adopted_children(const char *children_file) {
    FILE *children = fopen(children_file, "r");
    if (children) {
        long descendant;
        while (fscanf(children, "%ld", &descendant) == 1)
            if (descendant > 1) kill((pid_t)descendant, SIGKILL);
        fclose(children);
        return;
    }

    // The children procfs entry is optional in Linux kernel builds.
    DIR *proc = opendir("/proc");
    if (!proc) return;
    struct dirent *entry;
    while ((entry = readdir(proc))) {
        char *end;
        long pid = strtol(entry->d_name, &end, 10);
        if (*end || pid < 2 || pid == getpid()) continue;
        char status_path[64];
        snprintf(status_path, sizeof(status_path), "/proc/%ld/status", pid);
        FILE *status = fopen(status_path, "r");
        if (!status) continue;
        char line[160];
        while (fgets(line, sizeof(line), status)) {
            long parent;
            if (sscanf(line, "PPid: %ld", &parent) == 1) {
                if (parent == getpid()) kill((pid_t)pid, SIGKILL);
                break;
            }
        }
        fclose(status);
    }
    closedir(proc);
}

/* The runner is a subreaper so daemonized descendants are adopted even if they
 * changed their process group. Hold the original PID until the group is signaled. */
static int reap(pid_t leader) {
    kill(-leader, SIGKILL);
    kill(leader, SIGKILL);
    int status = 0;
    while (waitpid(leader, &status, 0) < 0 && errno == EINTR) {}
    char path[80];
    snprintf(path, sizeof(path), "/proc/self/task/%ld/children", (long)getpid());
    int64_t deadline = now_ms() + 2000;
    while (now_ms() < deadline) {
        int unused;
        pid_t next;
        do { next = waitpid(-1, &unused, WNOHANG); } while (next > 0 || (next < 0 && errno == EINTR));
        if (next < 0) break;
        kill_adopted_children(path);
        usleep(10000);
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

static void close_input(bool tty, int *input, struct buffer *queued) {
    if (*input < 0) return;
    if (tty) {
        queued->offset = 0;
        queued->used = 2;
        queued->data[0] = queued->data[1] = 4; /* Canonical VEOF, including a partial line. */
    } else {
        close(*input);
        *input = -1;
    }
}

static bool receive_input(int fd, struct buffer *in, struct buffer *queued, int *input, int master, bool *eof) {
    size_t target = in->used < HX_HEADER ? HX_HEADER : HX_HEADER + get_u32(in->data + 1);
    if (target > sizeof(in->data)) return false;
    ssize_t n = read(fd, in->data + in->used, target - in->used);
    if (n <= 0) return n < 0 && (errno == EAGAIN || errno == EINTR);
    in->used += n;
    if (in->used < HX_HEADER) return true;
    size_t len = get_u32(in->data + 1);
    if (len > HX_CHUNK) return false;
    if (in->used != HX_HEADER + len) return true;
    switch (in->data[0]) {
        case HX_DATA:
            if (!len || *eof) return false;
            if (*input >= 0) {
                memcpy(queued->data, in->data + HX_HEADER, len);
                queued->used = len;
                queued->offset = 0;
            }
            break;
        case HX_EOF:
            if (len || *eof) return false;
            *eof = true;
            close_input(master >= 0, input, queued);
            break;
        case HX_RESIZE: {
            if (len != 4 || master < 0) return false;
            uint32_t size = get_u32(in->data + HX_HEADER);
            struct winsize ws = { .ws_row = size >> 16, .ws_col = size & 65535 };
            if (ws.ws_row && ws.ws_col) ioctl(master, TIOCSWINSZ, &ws);
            break;
        }
        default: return false;
    }
    in->used = 0;
    return true;
}

static void read_output(int *fd, struct buffer *out, unsigned char type) {
    ssize_t n = read(*fd, out->data + HX_HEADER, HX_CHUNK);
    if (n > 0) frame(out, type, n);
    else if (!n || (errno != EAGAIN && errno != EINTR)) {
        close(*fd);
        *fd = -1;
    }
}

static int supervise(int sock, pid_t child, int master, int input, int output, int error,
                     int64_t deadline, struct upload *up) {
    struct buffer incoming = {0}, queued = {0}, outgoing = {0};
    frame(&outgoing, HX_READY, 0);
    bool eof = false, exited = false, sent_exit = false, aborted = false;
    int code = 1;
    while (now_ms() < deadline && (!sent_exit || outgoing.used != outgoing.offset)) {
        if (!exited) {
            siginfo_t info = {0};
            if (!waitid(P_PID, child, &info, WEXITED | WNOHANG | WNOWAIT) && info.si_pid) {
                code = reap(child);
                exited = true;
                if (input >= 0 && master < 0) close(input);
                input = -1;
                queued.offset = queued.used;
            }
        }
        bool out_empty = outgoing.used == outgoing.offset;
        if (out_empty && exited && output < 0 && error < 0 && !sent_exit) {
            struct pollfd peer = { .fd = sock, .events = POLLRDHUP };
            poll(&peer, 1, 0);
            if (peer.revents & (POLLERR | POLLHUP | POLLRDHUP)) { aborted = true; break; }
            if (code == 0 && up->fd >= 0 && renameat(up->dir, up->temp, up->dir, up->name)) {
                int n = snprintf((char *)outgoing.data + HX_HEADER, HX_CHUNK, "replace host file: %s\n", strerror(errno));
                frame(&outgoing, HX_ERR, n);
                code = 1;
                continue;
            }
            frame(&outgoing, HX_EXIT, 4);
            put_u32(outgoing.data + HX_HEADER, code);
            sent_exit = true;
            out_empty = false;
        }
        struct pollfd fds[] = {
            { .fd = sock, .events = POLLRDHUP | (queued.used == queued.offset ? POLLIN : 0) | (!out_empty ? POLLOUT : 0) },
            { .fd = queued.used != queued.offset ? input : -1, .events = POLLOUT },
            { .fd = out_empty ? output : -1, .events = POLLIN },
            { .fd = out_empty ? error : -1, .events = POLLIN },
        };
        int polled = poll(fds, 4, 100);
        if (polled < 0 && errno == EINTR) continue;
        if (polled < 0 || (fds[0].revents & (POLLERR | POLLHUP | POLLRDHUP))) { aborted = true; break; }
        if (fds[0].revents & POLLOUT) flush(sock, &outgoing);
        if (fds[1].revents & (POLLERR | POLLHUP)) queued.offset = queued.used;
        else if (fds[1].revents & POLLOUT) flush(input, &queued);
        if ((fds[0].revents & POLLIN) && !receive_input(sock, &incoming, &queued, &input, master, &eof)) {
            aborted = true;
            break;
        }
        if (outgoing.used == outgoing.offset) {
            /* Alternate when both streams are ready so flood output cannot starve stderr. */
            if (fds[3].revents && (!fds[2].revents || outgoing.data[0] == HX_OUT)) read_output(&error, &outgoing, HX_ERR);
            else if (fds[2].revents) read_output(&output, &outgoing, HX_OUT);
        }
    }
    if (!exited) reap(child);
    if (aborted) return 1;
    return sent_exit && outgoing.offset == outgoing.used ? code : 124;
}

int main(int argc, char **argv) {
    if (argc < 8) return 2;
    uid_t expected = (uid_t)strtoul(argv[2], NULL, 10);
    if (getuid() != expected || geteuid() != expected) {
        fputs("The runner did not get the requested Android UID\n", stderr);
        return 126;
    }
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_DFL);
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) < 0) { perror("prctl"); return 126; }
    int sock = hx_connect(argv[1]);
    if (sock < 0) { perror("connect Android host broker"); return 126; }
    unsigned char start[HX_HEADER];
    if (recv(sock, start, sizeof(start), MSG_WAITALL) != sizeof(start) || start[0] != HX_START || get_u32(start + 1))
        return 126;
    if (nonblocking(sock)) return 126;

    bool tty = !strcmp(argv[3], "tty");
    uint32_t size = (uint32_t)strtoul(argv[4], NULL, 10);
    int64_t deadline = now_ms() + strtoll(argv[5], NULL, 10);
    bool push = !strcmp(argv[6], "push");
    bool pull = !strcmp(argv[6], "pull");
    if (push && argc != 9) return 2;
    long long length = push ? strtoll(argv[8], NULL, 10) : 0;
    if (length < 0 || length > HX_MAX_FILE) return 2;
    struct upload up = { .dir = -1, .fd = -1 };
    int master = -1, slave = -1, in[2], out[2], err[2];
    if (tty) {
        master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
        char dev[128];
        if (master < 0 || grantpt(master) || unlockpt(master) || ptsname_r(master, dev, sizeof(dev))
            || (slave = open(dev, O_RDWR | O_NOCTTY | O_CLOEXEC)) < 0 || nonblocking(master)) {
            perror("open Android PTY");
            return 126;
        }
        struct winsize ws = { .ws_row = size >> 16, .ws_col = size & 65535 };
        if (ws.ws_row && ws.ws_col) ioctl(master, TIOCSWINSZ, &ws);
    } else if (pipe2(in, O_CLOEXEC) || pipe2(out, O_CLOEXEC) || pipe2(err, O_CLOEXEC)
               || nonblocking(in[1]) || nonblocking(out[0]) || nonblocking(err[0])) {
        perror("open Android command pipes");
        return 126;
    }
    int upload_error = push && prepare_upload(&up, argv[7]) < 0 ? errno : 0;
    pid_t child = fork();
    if (child < 0) {
        if (up.fd >= 0) unlinkat(up.dir, up.temp, 0);
        perror("fork Android command");
        return 126;
    }
    if (!child) {
        signal(SIGPIPE, SIG_DFL);
        close(sock);
        if (setsid() < 0) _exit(126);
        if (tty && ioctl(slave, TIOCSCTTY, 0)) _exit(126);
        if (dup2(tty ? slave : in[0], 0) < 0 || dup2(tty ? slave : out[1], 1) < 0
            || dup2(tty ? slave : err[1], 2) < 0) _exit(126);
        if (tty) { close(master); close(slave); }
        else { close(in[0]); close(in[1]); close(out[0]); close(out[1]); close(err[0]); close(err[1]); }
        if (chdir("/")) _exit(126);
        if (push) {
            if (upload_error) { errno = upload_error; perror("create host file"); exit(1); }
            int result = transfer(STDIN_FILENO, up.fd, length, true);
            if (!result && fsync(up.fd)) { perror("sync host file"); result = 1; }
            exit(result);
        }
        if (pull) exit(download(argv[7]));
        char *env[] = { "PATH=/system/bin:/system/xbin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
                        "TERM=xterm-256color", "HOME=/", "TMPDIR=/data/local/tmp", NULL };
        execve(argv[7], argv + 7, env);
        perror("exec Android command");
        _exit(127);
    }
    if (tty) close(slave);
    else { close(in[0]); close(out[1]); close(err[1]); }
    int result = supervise(sock, child, master, tty ? master : in[1], tty ? master : out[0], tty ? -1 : err[0], deadline, &up);
    if (up.fd >= 0) unlinkat(up.dir, up.temp, 0);
    return result;
}
