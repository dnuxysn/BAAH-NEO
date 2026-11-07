package com.baah.nativehost;
import android.os.ParcelFileDescriptor;
interface IControlService {
    String identity() = 0;
    int createDisplay() = 1;
    void releaseDisplay() = 2;
    ParcelFileDescriptor capture() = 3;
    boolean touch(int action, float x, float y) = 4;
    String command(String command) = 5;
    String stats() = 6;
    void watch(IBinder owner) = 7;
    void setScreenPower(boolean on) = 8;
    void mutePackage(String packageName, boolean muted) = 9;
    ParcelFileDescriptor captureBgr() = 10;
    void destroy() = 16777114;
}
