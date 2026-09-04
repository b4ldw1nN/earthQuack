#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/prctl.h>

int main(int argc, char *argv[]) {
    // Set Linux process comm name to 'clipsyncd' for ps/top/htop
    prctl(PR_SET_NAME, "clipsyncd", 0, 0, 0);

    const char *home = getenv("HOME");
    if (!home) {
        fprintf(stderr, "HOME not set, cannot locate app.py\n");
        return 1;
    }
    char app_path[1024];
    snprintf(app_path, sizeof(app_path), "%s/clipboard-sync/daemon/app.py", home);

    // Keep argv[0] as /usr/bin/python3 so sys.executable is valid in Python
    char *python_exec = "/usr/bin/python3";
    char *args[] = { python_exec, app_path, NULL };

    execv(python_exec, args);

    perror("execv failed");
    return 1;
}
