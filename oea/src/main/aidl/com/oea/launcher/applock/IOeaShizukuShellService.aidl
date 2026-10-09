package com.oea.launcher.applock;

interface IOeaShizukuShellService {
    String setSuspended(String packageName, boolean suspended) = 1;
    int getSuspended(String packageName) = 2;
    String getSuspendedPackages(String packageNamesDelimited) = 3;
    void destroy() = 16777114;
}
