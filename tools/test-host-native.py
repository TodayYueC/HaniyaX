#!/usr/bin/env python3
"""Exercise the native protocol and runner on a Linux workstation using a fake broker."""
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import unittest

DATA, EOF, RESIZE, OUT, ERR, EXIT, READY, NOTICE, START = range(1, 10)
SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/host-native"


def receive(peer, count):
    data = bytearray()
    while len(data) < count:
        chunk = peer.recv(count - len(data))
        if not chunk:
            raise EOFError("native runner disconnected")
        data.extend(chunk)
    return bytes(data)


def send(peer, kind, data=b""):
    peer.sendall(struct.pack(">BI", kind, len(data)) + data)


def frame(peer):
    kind, size = struct.unpack(">BI", receive(peer, 5))
    return kind, receive(peer, size)


class HostNative(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.bin = Path(cls.temp.name)
        shim = cls.bin / "transport.c"
        # The real feature connects to a named Android socket. An inherited Linux
        # socketpair exercises the exact I/O without requiring a listening endpoint.
        shim.write_text("""
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>

int __wrap_socket(int family, int type, int protocol) {
    (void)family;
    (void)type;
    (void)protocol;
    const char *fd = getenv("HX_TEST_FD");
    if (!fd) return -1;
    int inherited = atoi(fd);
    int copy = fcntl(inherited, F_DUPFD_CLOEXEC, 3);
    close(inherited);
    return copy;
}
int __wrap_connect(int fd, const struct sockaddr *addr, socklen_t len) {
    (void)fd;
    (void)addr;
    (void)len;
    return 0;
}
""")
        for tool in ("cli", "runner"):
            subprocess.run(
                ["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                 "-DHX_DESCRIPTOR=" + json.dumps(str(cls.bin / "bridge")),
                 str(SOURCE / f"{tool}.c"), str(shim), "-Wl,--wrap=socket,--wrap=connect",
                 "-o", str(cls.bin / tool)],
                check=True,
            )

    def transport(self):
        peer, child = socket.socketpair()
        self.addCleanup(peer.close)
        self.addCleanup(child.close)
        peer.settimeout(5)
        return peer, child

    def runner(self, *args, tty=False, deadline=5000):
        peer, child = self.transport()
        cmd = [str(self.bin / "runner"), "fixture", str(os.getuid()), "tty" if tty else "pipe",
               str((27 << 16) | 90) if tty else "0", str(deadline), *args]
        process = subprocess.Popen(cmd, pass_fds=(child.fileno(),),
                                   env=dict(os.environ, HX_TEST_FD=str(child.fileno())))
        child.close()
        self.addCleanup(process.wait, 5)
        send(peer, START)
        self.assertEqual(frame(peer), (READY, b""))
        return process, peer

    def result(self, peer):
        stdout = bytearray()
        stderr = bytearray()
        while True:
            kind, data = frame(peer)
            if kind == EXIT:
                return bytes(stdout), bytes(stderr), struct.unpack(">I", data)[0]
            self.assertIn(kind, (OUT, ERR))
            (stdout if kind == OUT else stderr).extend(data)

    def test_cli_decodes_output_and_exit(self):
        peer, child = self.transport()
        token = "a" * 64
        (self.bin / "bridge").write_text(f"fixture\n{token}\n")
        cmd = [str(self.bin / "cli"), "host", "--timeout", "10", "shell", "fixture"]
        with subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              pass_fds=(child.fileno(),),
                              env={"HX_TEST_FD": str(child.fileno())}) as cli:
            child.close()
            with peer:
                self.assertEqual(receive(peer, 69), ("HXHB1" + token).encode())
                self.assertEqual(struct.unpack(">IIII", receive(peer, 16)), (10, 0, 0, 2))
                for arg in (b"shell", b"fixture"):
                    self.assertEqual(receive(peer, struct.unpack(">I", receive(peer, 4))[0]), arg)
                send(peer, READY)
                assert cli.stdin
                cli.stdin.write(b"payload")
                cli.stdin.close()
                cli.stdin = None
                self.assertEqual(frame(peer), (DATA, b"payload"))
                self.assertEqual(frame(peer), (EOF, b""))
                send(peer, OUT, b"stdout")
                send(peer, ERR, b"stderr")
                send(peer, EXIT, struct.pack(">I", 37))
                self.assertEqual(cli.communicate(timeout=5), (b"stdout", b"stderr"))
                self.assertEqual(cli.returncode, 37)

    def test_uses_pipes_and_propagates_exit(self):
        _, peer = self.runner("exec", "/bin/sh", "-c",
                              "read line; printf %s \"$line\"; printf 'diagnostic' >&2; exit 37")
        send(peer, DATA, b"hello\n")
        send(peer, EOF)
        self.assertEqual(self.result(peer), (b"hello", b"diagnostic", 37))

    def test_uses_real_pty_and_initial_size(self):
        _, peer = self.runner("exec", "/bin/sh", "-c", "test -t 0 && test -t 1 && /bin/stty size", tty=True)
        out, err, code = self.result(peer)
        self.assertEqual((out.strip(), err, code), (b"27 90", b"", 0))

    def test_disconnect_kills_detached_descendant(self):
        # setsid -f forks a new session and the intermediate process returns immediately.
        script = "/usr/bin/setsid -f /bin/sh -c 'echo $$; exec /bin/sleep 60'; exec /bin/sleep 60"
        process, peer = self.runner("exec", "/bin/sh", "-c", script)
        kind, data = frame(peer)
        self.assertEqual(kind, OUT)
        descendant = int(data)
        peer.close()
        process.wait(5)
        with self.assertRaises(ProcessLookupError):
            os.kill(descendant, 0)

    def test_exact_binary_transfer_and_symlink_rejection(self):
        path = self.bin / "file with spaces"
        payload = bytes(range(256)) * 256
        path.write_bytes(b"original")
        _, peer = self.runner("push", str(path), str(len(payload)))
        send(peer, DATA, payload[:32768])
        send(peer, DATA, payload[32768:])
        send(peer, EOF)
        self.assertEqual(self.result(peer), (b"", b"", 0))
        self.assertEqual(path.read_bytes(), payload)

        _, peer = self.runner("pull", str(path))
        self.assertEqual(self.result(peer), (payload, b"", 0))

        link = self.bin / "symlink"
        link.symlink_to(path)
        _, peer = self.runner("pull", str(link))
        self.assertEqual(self.result(peer)[2], 1)

        _, peer = self.runner("push", str(path), "256")
        send(peer, DATA, b"truncated")
        send(peer, EOF)
        self.assertEqual(self.result(peer)[2], 1)
        self.assertEqual(path.read_bytes(), payload)

    def test_cancelled_upload_has_no_partial_file(self):
        path = self.bin / "canceled"
        process, peer = self.runner("push", str(path), "100")
        send(peer, DATA, b"some data")
        peer.close()
        process.wait(5)
        self.assertFalse(path.exists())
        self.assertFalse(list(self.bin.glob(".haniyax-upload-*")))

    def test_wrong_uid_does_not_start_runner(self):
        output = subprocess.run(
            [str(self.bin / "runner"), "absent", str(os.getuid() + 1), "pipe", "0", "1000", "exec", "/bin/true"],
            capture_output=True, timeout=5,
        )
        self.assertEqual(output.returncode, 126)
        self.assertIn(b"requested Android UID", output.stderr)


if __name__ == "__main__":
    unittest.main()
