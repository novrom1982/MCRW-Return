package ru.mcrealworld.mcrwreturn;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "mcrw-return",
        name = "MCRW-Return",
        version = "1.0.0",
        authors = {"novrom1982"},
        dependencies = {@Dependency(id = "authmevelocity")}
)
public class MCRWReturn {

    private static final MinecraftChannelIdentifier AUTHME_CHANNEL =
            MinecraftChannelIdentifier.from("authmevelocity:main");

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataFile;
    private final Properties storage = new Properties();
    private final Set<UUID> pendingReturn = ConcurrentHashMap.newKeySet();

    @Inject
    public MCRWReturn(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataFile = dataDirectory.resolve("players.properties");
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(AUTHME_CHANNEL);
        loadStorage();
        logger.info("MCRW-Return 1.0.0 загружен. Сохранено игроков: {}", storage.size());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        saveStorage();
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        if (serverName.equalsIgnoreCase("auth")) {
            pendingReturn.add(player.getUniqueId());
            return;
        }

        storage.setProperty(player.getUniqueId().toString(), serverName);
        saveStorage();
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(AUTHME_CHANNEL)) {
            return;
        }
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return;
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        try {
            String action = in.readUTF();
            if (!action.equalsIgnoreCase("LOGIN")) {
                return;
            }
            String playerName = in.readUTF();
            server.getPlayer(playerName).ifPresent(this::handleAuthenticated);
        } catch (Exception ignored) {
        }
    }

    private void handleAuthenticated(Player player) {
        if (!pendingReturn.remove(player.getUniqueId())) {
            return;
        }

        String lastServer = storage.getProperty(player.getUniqueId().toString());
        if (lastServer == null || lastServer.isEmpty() || lastServer.equalsIgnoreCase("auth")) {
            return;
        }

        Optional<RegisteredServer> target = server.getServer(lastServer);
        if (target.isEmpty()) {
            return;
        }

        server.getScheduler().buildTask(this, () -> {
            if (player.isActive()) {
                player.createConnectionRequest(target.get()).fireAndForget();
            }
        }).delay(250, TimeUnit.MILLISECONDS).schedule();
    }

    private synchronized void loadStorage() {
        try {
            Files.createDirectories(dataFile.getParent());
            if (Files.exists(dataFile)) {
                try (InputStream is = Files.newInputStream(dataFile)) {
                    storage.load(is);
                }
            }
        } catch (IOException e) {
            logger.error("Ошибка загрузки players.properties", e);
        }
    }

    private synchronized void saveStorage() {
        try {
            Files.createDirectories(dataFile.getParent());
            try (OutputStream os = Files.newOutputStream(dataFile)) {
                storage.store(os, "MCRW-Return - Last Server Storage");
            }
        } catch (IOException e) {
            logger.error("Ошибка сохранения players.properties", e);
        }
    }
}
