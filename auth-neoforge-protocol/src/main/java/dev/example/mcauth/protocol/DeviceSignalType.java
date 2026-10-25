package dev.example.mcauth.protocol;

public enum DeviceSignalType {
    SYSTEM_UUID(true),
    MACHINE_ID(true),
    MOTHERBOARD_UUID(true),
    DISK_ID(true),
    GPU(false),
    MAC_ADDRESS(false);

    private final boolean strong;

    DeviceSignalType(boolean strong) {
        this.strong = strong;
    }

    public boolean strong() {
        return strong;
    }
}
