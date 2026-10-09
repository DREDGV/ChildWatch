"""Exec one transcription subprocess, dying when its exact Node parent disappears.

Linux only. No shell, daemon, PID file or unrelated process cleanup.
The parent identity is checked both before and after PR_SET_PDEATHSIG to close
the race where the parent dies before the kernel death signal is installed.
"""
import ctypes
import errno
import json
import os
import signal
import sys


def main():
    if sys.platform != "linux" or len(sys.argv) < 3 or sys.argv[1] != "--parent-pid":
        raise RuntimeError("Linux and an explicit parent PID are required")
    parent_pid = int(sys.argv[2])
    if parent_pid <= 1 or os.getppid() != parent_pid:
        raise RuntimeError("Transcription parent disappeared before guard startup")
    libc = ctypes.CDLL(None, use_errno=True)
    libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]
    libc.prctl.restype = ctypes.c_int
    if libc.prctl(1, signal.SIGKILL, 0, 0, 0) != 0:  # PR_SET_PDEATHSIG
        raise OSError(ctypes.get_errno(), "Cannot install transcription parent-death signal")
    if os.getppid() != parent_pid:
        raise RuntimeError("Transcription parent disappeared during guard startup")
    if sys.argv[3:] == ["--probe"]:
        print(json.dumps({"guardVersion": 1, "parentPid": parent_pid, "pdeathsig": int(signal.SIGKILL)}))
        return
    if len(sys.argv) < 6 or sys.argv[3] != "--" or not os.path.isabs(sys.argv[4]):
        raise RuntimeError("An absolute executable path and argument list are required")
    # A privileged/setuid exec can reset PDEATHSIG; such binaries are forbidden.
    executable = sys.argv[4]
    mode = os.stat(executable).st_mode
    if mode & 0o6000:
        raise RuntimeError("Privileged transcription executables are forbidden")
    try:
        if os.getxattr(executable, "security.capability"):
            raise RuntimeError("Capability-enabled transcription executables are forbidden")
    except OSError as error:
        if error.errno not in (errno.ENODATA, errno.ENOTSUP):
            raise
    os.execv(executable, sys.argv[4:])


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(125)
