package com.tomovwgti.mqtt.push;

import android.os.Environment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ConnectionLog {
    private boolean mFlag = false; // ログフラグ
    private String mPath;
    private Writer mWriter;

    private static final SimpleDateFormat TIMESTAMP_FMT =
            new SimpleDateFormat("[HH:mm:ss] ");

    private boolean checkFlag() {
        return mFlag;
    }

    public ConnectionLog() throws IOException
    {
        if (!checkFlag()) return;
        File sdcard = Environment.getExternalStorageDirectory();
        File logDir = new File(sdcard, "tokudu/log/");
        if (!logDir.exists()) {
            logDir.mkdirs();
            // do not allow media scan
            new File(logDir, ".nomedia").createNewFile();
        }

        open(logDir.getAbsolutePath() + "/push.log");
    }

    public ConnectionLog(String basePath)
            throws IOException
    {
        if (!checkFlag()) return;
        open(basePath);
    }

    protected void open(String basePath)
            throws IOException
    {
        if (!checkFlag()) return;
        File f = new File(basePath + "-" + getTodayString());
        mPath = f.getAbsolutePath();
        mWriter = new BufferedWriter(new FileWriter(mPath), 2048);

        println("Opened log.");
    }

    private static String getTodayString()
    {
        SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd-hhmmss");
        return df.format(new Date());
    }

    public String getPath()
    {
        return mPath;
    }

    public void println(String message) throws IOException
    {
        if (!checkFlag()) return;
        mWriter.write(TIMESTAMP_FMT.format(new Date()));
        mWriter.write(message);
        mWriter.write('\n');
        mWriter.flush();
    }

    public void close()
            throws IOException
    {
        if (!checkFlag()) return;
        mWriter.close();
    }
}
