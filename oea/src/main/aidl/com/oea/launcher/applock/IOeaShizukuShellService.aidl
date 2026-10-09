package com.oea.launcher.applock;

interface IOeaShizukuShellService {
    String setSuspended(String packageName, boolean suspended);
    int getSuspended(String packageName);
    void destroy() = 16777114;
}
