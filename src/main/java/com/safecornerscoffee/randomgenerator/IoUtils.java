package com.safecornerscoffee.randomgenerator;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;

public class IoUtils {

    public static void appendLineWithLocking(String file, String line) throws IOException {
        FileOutputStream out = new FileOutputStream(file, true);
        try {
            FileLock lock = out.getChannel().lock();
            try {
                out.write(line.getBytes());
            } finally {
                lock.release();
            }
        } finally {
            out.close();
        }
    }
}
