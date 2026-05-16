package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class BedrockDebugLog {
    private static final String ENABLED_PROPERTY = "litematica_printer.bedrockDebugLog";
    private static final String LEGACY_ENABLED_PROPERTY = "printer.bedrockDebugLog";
    private static final String MODE_PROPERTY = "litematica_printer.bedrockDebugLogMode";
    private static final String LEGACY_MODE_PROPERTY = "printer.bedrockDebugLogMode";
    private static final String PATH_PROPERTY = "litematica_printer.bedrockDebugLogPath";
    private static final String LEGACY_PATH_PROPERTY = "printer.bedrockDebugLogPath";
    private static final String DEFAULT_MODE = "events"; // 日志模式
    private static final DateTimeFormatter SESSION_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path LOG_PATH = resolveLogPath();
    private static final boolean ENABLED = resolveEnabled();
    private static final String MODE = resolveMode();
    private static boolean initialized;

    private BedrockDebugLog() {
    }

    public static synchronized void write(String message) {
        if (!ENABLED || !shouldWrite(message)) {
            return;
        }
        try {
            initializeLogFile();
            Files.writeString(
                    LOG_PATH,
                    message + System.lineSeparator(),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
        }
    }

    public static Path getLogPath() {
        return LOG_PATH;
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static String pos(BlockPos pos) {
        if (pos == null) {
            return "null";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static String describeState(BlockState state) {
        return state.getBlock().toString() + " " + state.toString();
    }

    public static String describePistonState(BlockState state) {
        StringBuilder builder = new StringBuilder(describeState(state));
        if (state.hasProperty(PistonBaseBlock.FACING)) {
            builder.append(" facing=").append(state.getValue(PistonBaseBlock.FACING));
        }
        if (state.hasProperty(PistonBaseBlock.EXTENDED)) {
            builder.append(" extended=").append(state.getValue(PistonBaseBlock.EXTENDED));
        }
        return builder.toString();
    }

    private static Path resolveLogPath() {
        String customPath = System.getProperty(PATH_PROPERTY, System.getProperty(LEGACY_PATH_PROPERTY, "")).trim();
        if (!customPath.isEmpty()) {
            return Paths.get(customPath);
        }
        return Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve("bedrock-printer-debug.log");
    }

    private static boolean resolveEnabled() {
        String explicit = System.getProperty(ENABLED_PROPERTY);
        if (explicit != null) {
            return Boolean.parseBoolean(explicit);
        }
        String legacy = System.getProperty(LEGACY_ENABLED_PROPERTY);
        if (legacy != null) {
            return Boolean.parseBoolean(legacy);
        }
        // Debug
        return false;
    }

    private static String resolveMode() {
        return System.getProperty(MODE_PROPERTY, System.getProperty(LEGACY_MODE_PROPERTY, DEFAULT_MODE))
                .trim()
                .toLowerCase();
    }

    private static void initializeLogFile() throws Exception {
        if (initialized) {
            return;
        }

        Path parent = LOG_PATH.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(
                LOG_PATH,
                "=== bedrock debug session start "
                        + LocalDateTime.now().format(SESSION_TIME_FORMAT)
                        + " mode=" + MODE
                        + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        initialized = true;
    }

    private static boolean shouldWrite(String message) {
        if ("full".equals(MODE)) {
            return true;
        }
        if (!"events".equals(MODE)) {
            return true;
        }
        return !isNoise(message);
    }

    private static boolean isNoise(String message) {
        return message.startsWith("controller tick ")
                || message.startsWith("controller init consumed ")
                || message.startsWith("controller execute consumed ")
                || message.startsWith("controller fastlane init ")
                || message.startsWith("controller fastlane execute ")
                || message.startsWith("controller schedule ")
                || message.startsWith("placeSimple ")
                || message.startsWith("placePiston ")
                || message.startsWith("break start ")
                || message.startsWith("break prediction suppressed ")
                || message.startsWith("target powered stall waiting ")
                || message.startsWith("target powered stall delayed ")
                || message.startsWith("target execute delayed ")
                || message.startsWith("target execute waiting sync ")
                || message.startsWith("target initialize delayed ")
                || message.startsWith("cleanup deferred ");
    }
}
