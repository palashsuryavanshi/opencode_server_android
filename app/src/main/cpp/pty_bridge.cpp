// Spec 33 - a real pseudo terminal for the Android terminal.
//
// `forkpty` (Bionic's <pty.h>, which wraps openpty + login_tty + fork) is the only way to give a
// child process a genuine controlling terminal on Android. That is what makes interactive shells,
// colour, job control and full-screen programs such as `vim`, `less` and `top` behave correctly.
//
// Everything crossing the JNI boundary is deliberately primitive: handles are opaque pointers, and
// all allocation and threading happens on the native side so the Kotlin layer never owns memory.

#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "OpenCodePty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr size_t kBufferSize = 8192;
constexpr int kJoinAttempts = 100;
constexpr int kReapAttempts = 50;
constexpr int kPollIntervalMicros = 10 * 1000;

JavaVM *g_vm = nullptr;

// One open PTY: the master descriptor, the child pid, and the state the reader needs.
struct PtySession {
    int master = -1;
    pid_t pid = -1;
    std::atomic<bool> closed{false};
    std::atomic<int> exit_code{-1};
    std::thread reader;
    // Global ref to the Kotlin sink. Held for the session's lifetime so a collection cannot pull it
    // out from under the reader thread.
    jobject callback = nullptr;
};

std::string last_error_message() { return std::string(std::strerror(errno)); }

// Build a NULL-terminated char*[] for execve from a Java string array.
//
// The vector owns the strings, so execve sees pointers that stay valid for the whole call.
std::vector<char *> to_char_ptrs(JNIEnv *env, jobjectArray array, bool null_terminate) {
    std::vector<std::string> storage;
    if (array == nullptr) {
        storage.emplace_back("/system/bin/sh");
    } else {
        const jsize count = env->GetArrayLength(array);
        storage.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            jstring element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
            const char *chars = element != nullptr ? env->GetStringUTFChars(element, nullptr) : nullptr;
            storage.emplace_back(chars != nullptr ? chars : "");
            if (chars != nullptr) env->ReleaseStringUTFChars(element, chars);
            if (element != nullptr) env->DeleteLocalRef(element);
        }
    }

    std::vector<char *> pointers;
    pointers.reserve(storage.size() + 1);
    for (auto &value : storage) pointers.push_back(const_cast<char *>(value.c_str()));
    if (null_terminate) pointers.push_back(nullptr);
    return pointers;
}

// Reap the child so it does not linger as a zombie.
//
// WNOHANG in a short loop rather than a blocking wait, so this never deadlocks against the reader
// thread that may still be draining the master descriptor.
void reap_child(PtySession *session) {
    int status = 0;
    for (int attempt = 0; attempt < kReapAttempts; ++attempt) {
        const pid_t result = waitpid(session->pid, &status, WNOHANG);
        if (result == session->pid) {
            session->exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            return;
        }
        if (result < 0 && errno != EINTR) return;
        usleep(kPollIntervalMicros);
    }
}

// Read the master descriptor until EOF or close, forwarding each chunk to the Kotlin sink.
//
// The reader runs on its own thread, so it attaches to the JVM itself; a JNIEnv from the creating
// thread is not valid here.
void read_loop(PtySession *session) {
    JNIEnv *env = nullptr;
    bool attached = false;

    if (g_vm != nullptr &&
        g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
    }

    if (env != nullptr && session->callback != nullptr) {
        jclass callback_class = env->GetObjectClass(session->callback);
        jmethodID on_data = env->GetMethodID(callback_class, "onNativeData", "([B)V");
        jmethodID on_exit = env->GetMethodID(callback_class, "onNativeExit", "(I)V");

        if (on_data != nullptr && on_exit != nullptr) {
            std::vector<char> buffer(kBufferSize);

            while (!session->closed.load()) {
                const ssize_t count = ::read(session->master, buffer.data(), buffer.size());

                if (count > 0) {
                    jbyteArray chunk = env->NewByteArray(static_cast<jsize>(count));
                    if (chunk != nullptr) {
                        env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(count),
                                                reinterpret_cast<const jbyte *>(buffer.data()));
                        // stderr is not distinguishable at the PTY layer: both streams are merged
                        // into the terminal, which is what a real terminal does.
                        env->CallVoidMethod(session->callback, on_data, chunk);
                        env->DeleteLocalRef(chunk);
                    }
                    // An exception in the sink must not silently kill the reader thread.
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    continue;
                }

                if (count < 0 && (errno == EINTR || errno == EAGAIN)) {
                    usleep(kPollIntervalMicros);
                    continue;
                }

                // count == 0: the child closed the terminal. A normal end of session.
                break;
            }

            reap_child(session);

            env->CallVoidMethod(session->callback, on_exit,
                                static_cast<jint>(session->exit_code.load()));
            if (env->ExceptionCheck()) env->ExceptionClear();
        } else {
            LOGE("Native PTY sink is missing onNativeData/onNativeExit");
        }
    }

    if (attached) g_vm->DetachCurrentThread();
}

// Give the reader a bounded window to notice `closed`, then detach so close() never blocks.
void detach_reader_bounded(PtySession *session) {
    for (int attempt = 0; attempt < kJoinAttempts; ++attempt) {
        if (!session->reader.joinable()) return;
        usleep(kPollIntervalMicros);
    }
    session->reader.detach();
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/*
 * Open a PTY and start the child.
 *
 * Returns a pointer to the session, or 0 on failure. On failure the reason is retrievable with
 * nLastError so the Kotlin layer can report something actionable rather than just "failed".
 */
JNIEXPORT jlong JNICALL
Java_com_palash_opencodeserver_backend_runtime_pty_NativePty_nOpen(JNIEnv *env, jclass, jstring working_directory,
                                                    jobjectArray argv, jobjectArray envp,
                                                    jint columns, jint rows, jobject callback) {
    errno = 0;

    std::vector<char *> arguments = to_char_ptrs(env, argv, false);
    std::vector<char *> environment = to_char_ptrs(env, envp, true);

    if (arguments.empty()) {
        LOGE("nOpen called with an empty argv");
        return 0;
    }

    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);
    size.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);

    auto session = std::make_unique<PtySession>();
    const char *directory = working_directory != nullptr
                                ? env->GetStringUTFChars(working_directory, nullptr)
                                : nullptr;

    // forkpty wires the child to a controlling terminal, establishes the session and controlling
    // tty, and dups the slave onto 0/1/2 in one step. Doing this by hand is where PTY bugs come
    // from, so it is used directly.
    const pid_t pid = ::forkpty(&session->master, nullptr, nullptr, &size);

    if (directory != nullptr) {
        env->ReleaseStringUTFChars(working_directory, directory);
    }

    if (pid < 0) {
        LOGE("forkpty failed: %s", last_error_message().c_str());
        return 0;
    }

    if (pid == 0) {
        // ---- child ----
        // Nothing here can report through JNI, so a failure ends the process rather than leaving a
        // half-started child attached to a PTY nobody will read.
        if (directory != nullptr && ::chdir(directory) != 0) _exit(127);
        ::execve(arguments[0], arguments.data(), environment.data());
        // execve returns only on failure; 127 is the conventional "command not found" status, so a
        // caller inspecting the exit code sees something meaningful.
        _exit(127);
    }

    // ---- parent ----
    session->pid = pid;
    session->callback = callback != nullptr ? env->NewGlobalRef(callback) : nullptr;

    // Non-blocking reads let the reader notice `closed` promptly instead of blocking until the
    // child exits.
    const int flags = ::fcntl(session->master, F_GETFL, 0);
    if (flags >= 0) ::fcntl(session->master, F_SETFL, flags | O_NONBLOCK);

    session->reader = std::thread(read_loop, session.get());

    LOGI("forkpty ok: master=%d pid=%d cols=%d rows=%d", session->master, static_cast<int>(pid),
         static_cast<int>(size.ws_col), static_cast<int>(size.ws_row));

    return reinterpret_cast<jlong>(session.release());
}

JNIEXPORT jint JNICALL
Java_com_palash_opencodeserver_backend_runtime_pty_NativePty_nOpenPid(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<PtySession *>(handle);
    return session != nullptr ? static_cast<jint>(session->pid) : -1;
}

JNIEXPORT jstring JNICALL
Java_com_palash_opencodeserver_backend_runtime_pty_NativePty_nLastError(JNIEnv *env, jclass) {
    const std::string message = last_error_message();
    return env->NewStringUTF(message.c_str());
}

/*
 * Write to the PTY master.
 *
 * EAGAIN means the child is not reading yet; the write is retried briefly, and anything still
 * unsent is dropped with a log rather than blocking the UI thread on a wedged child.
 */
JNIEXPORT jint JNICALL
Java_com_palash_opencodeserver_backend_runtime_pty_NativePty_nWrite(JNIEnv *env, jclass, jlong handle,
                                                      jbyteArray data, jint length) {
    auto *session = reinterpret_cast<PtySession *>(handle);
    if (session == nullptr || data == nullptr || length <= 0) return -1;

    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return -1;

    const char *cursor = reinterpret_cast<const char *>(bytes);
    jint remaining = length;
    jint written = 0;
    int idle_attempts = 0;

    while (remaining > 0 && idle_attempts < 100) {
        const ssize_t result = ::write(session->master, cursor, static_cast<size_t>(remaining));

        if (result > 0) {
            cursor += result;
            remaining -= static_cast<jint>(result);
            written += static_cast<jint>(result);
            idle_attempts = 0;
            continue;
        }
        if (result < 0 && errno == EINTR) continue;
        if (result < 0 && errno == EAGAIN) {
            usleep(2 * 1000);
            idle_attempts++;
            continue;
        }
        break;
    }

    if (remaining > 0) LOGE("PTY write dropped %d bytes", remaining);

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return written;
}

/*
 * Resize in place.
 *
 * TIOCSWINSZ also delivers SIGWINCH to the foreground process group, which is what makes a
 * full-screen program redraw instead of keeping the old layout.
 */
JNIEXPORT jint JNICALL
Java_com_palash_opencodeserver_backend_runtime_pty_NativePty_nResize(JNIEnv *, jclass, jlong handle, jint columns,
                                                       jint rows) {
    auto *session = reinterpret_cast<PtySession *>(handle);
    if (session == nullptr) return -1;

    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_col = static_cast<unsigned short>(columns);
    size.ws_row = static_cast<unsigned short>(rows);

    if (::ioctl(session->master, TIOCSWINSZ, &size) != 0) {
        LOGE("TIOCSWINSZ failed: %s", last_error_message().c_str());
        return -1;
    }
    return 0;
}

/* Liveness is kill(pid, 0): success means the process exists, ESRCH means it is gone. */
JNIEXPORT jboolean JNICALL
Java_com_palash_opencodeserver_backend_runtime_pty_NativePty_nIsAlive(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<PtySession *>(handle);
    if (session == nullptr || session->closed.load()) return JNI_FALSE;
    if (session->exit_code.load() >= 0) return JNI_FALSE;
    return ::kill(session->pid, 0) == 0 ? JNI_TRUE : JNI_FALSE;
}

/*
 * Close the session.
 *
 * Order matters: signal the child, let the reader stop touching the descriptor, then release it.
 * Closing the master first would leave the reader looping on EBADF.
 */
JNIEXPORT void JNICALL
Java_com_palash_opencodeserver_backend_runtime_pty_NativePty_nClose(JNIEnv *env, jclass, jlong handle) {
    auto *session = reinterpret_cast<PtySession *>(handle);
    if (session == nullptr) return;
    if (session->closed.exchange(true)) return;

    if (session->pid > 0) {
        // SIGHUP is what a real terminal sends when the terminal itself goes away, so it is the
        // right signal here; SIGINT is reserved for Ctrl+C.
        ::kill(session->pid, SIGHUP);
    }

    detach_reader_bounded(session);

    if (session->master >= 0) {
        ::close(session->master);
        session->master = -1;
    }

    if (session->callback != nullptr) {
        env->DeleteGlobalRef(session->callback);
        session->callback = nullptr;
    }

    delete session;
}

}  // extern "C"