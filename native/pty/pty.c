#include <jni.h>
#include <fcntl.h>
#include <stdlib.h>
#include <unistd.h>
#include <pty.h>

/*
 * ADR-009: openpty(3) master fd for the terminal. Returns -1 on failure so
 * Kotlin can fall back to pipe-backed stdio.
 */
JNIEXPORT jint JNICALL
Java_com_lenix_nativebridge_NativeBridge_nativeOpenPty(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    int master = -1;
    int slave = -1;
    if (openpty(&master, &slave, NULL, NULL, NULL) != 0) {
        return -1;
    }
    close(slave);
    fcntl(master, F_SETFD, FD_CLOEXEC);
    return master;
}
