package dev.example.mcauth.client;

import dev.example.mcauth.protocol.DeviceSignalType;

import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Fixed-command, best-effort OS provider. It never logs collected values. */
public final class PlatformDeviceSignalProvider implements DeviceSignalCollector {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    @Override
    public Optional<String> collect(DeviceSignalType type) {
        try {
            return switch (type) {
                case SYSTEM_UUID -> systemUuid();
                case MACHINE_ID -> machineId();
                case MOTHERBOARD_UUID -> motherboardUuid();
                case DISK_ID -> diskId();
                case GPU -> gpu();
                case MAC_ADDRESS -> macAddress();
            };
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> systemUuid() {
        if (windows()) return command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_ComputerSystemProduct).UUID");
        if (mac()) return command("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                .flatMap(value -> extractQuotedProperty(value, "IOPlatformUUID"));
        return firstFile("/sys/class/dmi/id/product_uuid");
    }

    private Optional<String> machineId() {
        if (windows()) return command("reg.exe", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                .map(value -> value.replaceFirst("(?is).*MachineGuid\\s+REG_\\w+\\s+", "").strip());
        if (mac()) return systemUuid();
        return firstFile("/etc/machine-id", "/var/lib/dbus/machine-id");
    }

    private Optional<String> motherboardUuid() {
        if (windows()) return command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_BaseBoard).SerialNumber");
        if (mac()) return command("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                .flatMap(value -> extractQuotedProperty(value, "IOPlatformSerialNumber"));
        return firstFile("/sys/class/dmi/id/board_serial", "/sys/class/dmi/id/board_name");
    }

    private Optional<String> diskId() {
        if (windows()) return command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$d=Get-CimInstance Win32_LogicalDisk -Filter \"DeviceID='$env:SystemDrive'\"; $d.VolumeSerialNumber");
        if (mac()) return command("diskutil", "info", "/").map(value -> property(value, "Volume UUID"));
        return command("findmnt", "-no", "UUID", "/");
    }

    private Optional<String> gpu() {
        if (windows()) return command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_VideoController | Sort-Object PNPDeviceID | ForEach-Object {$_.PNPDeviceID}");
        if (mac()) return command("system_profiler", "SPDisplaysDataType");
        return command("sh", "-c", "command -v lspci >/dev/null && lspci -nn | sed -n '/VGA\\|3D controller/p'");
    }

    private Optional<String> macAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return Optional.empty();
            List<String> values = new ArrayList<>();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                String name = network.getName().toLowerCase(Locale.ROOT);
                if (!network.isUp() || network.isLoopback() || network.isVirtual()
                        || name.contains("docker") || name.contains("veth") || name.contains("vmnet")
                        || name.contains("virtual") || name.contains("vpn") || name.contains("tun")) continue;
                byte[] address = network.getHardwareAddress();
                if (address == null || address.length != 6 || (address[0] & 0x02) != 0) continue;
                StringBuilder encoded = new StringBuilder(12);
                for (byte value : address) encoded.append(String.format(Locale.ROOT, "%02x", value));
                values.add(encoded.toString());
            }
            values.sort(Comparator.naturalOrder());
            return values.isEmpty() ? Optional.empty() : Optional.of(String.join(",", values));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> firstFile(String... candidates) {
        for (String candidate : candidates) {
            try {
                String value = Files.readString(Path.of(candidate), StandardCharsets.UTF_8).strip();
                if (!value.isEmpty()) return Optional.of(value);
            } catch (IOException | SecurityException ignored) { }
        }
        return Optional.empty();
    }

    private Optional<String> command(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            byte[] bytes = process.getInputStream().readNBytes(16_385);
            if (bytes.length == 0 || bytes.length > 16_384 || process.exitValue() != 0) return Optional.empty();
            String value = new String(bytes, StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (IOException | InterruptedException | SecurityException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static Optional<String> extractQuotedProperty(String input, String name) {
        String marker = "\"" + name + "\" = \"";
        int start = input.indexOf(marker);
        if (start < 0) return Optional.empty();
        start += marker.length();
        int end = input.indexOf('"', start);
        return end > start ? Optional.of(input.substring(start, end)) : Optional.empty();
    }

    private static String property(String input, String name) {
        for (String line : input.lines().toList()) {
            int separator = line.indexOf(':');
            if (separator > 0 && line.substring(0, separator).strip().equalsIgnoreCase(name)) {
                return line.substring(separator + 1).strip();
            }
        }
        return "";
    }

    private boolean windows() { return os.contains("win"); }
    private boolean mac() { return os.contains("mac"); }
}
