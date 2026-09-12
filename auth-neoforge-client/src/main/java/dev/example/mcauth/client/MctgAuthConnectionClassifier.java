package dev.example.mcauth.client;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

final class MctgAuthConnectionClassifier {
    private MctgAuthConnectionClassifier() {
    }

    static boolean isIntegratedServer(ClientPlayerNetworkEvent.LoggingIn event) {
        return event.getConnection().isMemoryConnection();
    }
}
