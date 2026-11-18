package ru.mcrealworld.mcrwreturn;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github._4drian3d.authmevelocity.api.velocity.event.ProxyLoginEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

@Plugin(
        id = "mcrw-return",
        name = "MCRW-Return",
        version = "1.0.1",
        description = "Возврат игроков MC Real World на последний игровой сервер после авторизации",
        authors = {"novrom1982"},
        dependencies = {
                @Dependency(id = "authmevelocity")
        }
)
public class MCRWReturn {

    private static final String AUTH_SERVER_NAME = "auth";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataFile;
    private final Properties lastServers = new Properties();

    @Inject
    public MCRWReturn(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataFile = dataDirectory.resolve("players.properties");
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadData();
        logger.info("MCRW-Return v1.0.1 успешно запущен (режим API AuthMeVelocity). Загружено записей: {}", lastServers.size());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        if (serverName.equalsIgnoreCase(AUTH_SERVER_NAME)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        String previous = lastServers.getProperty(uuid.toString());
        if (!serverName.equals(previous)) {
            lastServers.setProperty(uuid.toString(), serverName);
            saveData();
        }
    }

    @Subscribe
    public void onProxyLogin(ProxyLoginEvent event) {
        Player player = event.player();
        String targetServerName = lastServers.getProperty(player.getUniqueId().toString());

        if (targetServerName == null || targetServerName.isBlank() || targetServerName.equalsIgnoreCase(AUTH_SERVER_NAME)) {
            return;
        }

        Optional<RegisteredServer> targetServer = server.getServer(targetServerName);
        if (targetServer.isEmpty()) {
            logger.warn("Сервер '{}' для игрока {} не найден в velocity.toml", targetServerName, player.getUsername());
            return;
        }

        server.getScheduler().buildTask(this, () -> {
            if (!player.isActive()) {
                return;
            }
            player.getCurrentServer().ifPresent(current -> {
                if (current.getServerInfo().getName().equalsIgnoreCase(AUTH_SERVER_NAME)) {
                    logger.info("Возвращаем игрока {} на последний сервер: {}", player.getUsername(), targetServerName);
                    player.createConnectionRequest(targetServer.get()).fireAndForget();
                }
            });
        }).delay(Duration.ofMillis(250)).schedule();
    }

    private synchronized void loadData() {
        try {
            if (Files.notExists(dataFile.getParent())) {
                Files.createDirectories(dataFile.getParent());
            }
            if (Files.exists(dataFile)) {
                try (InputStream in = Files.newInputStream(dataFile)) {
                    lastServers.load(in);
                }
            }
        } catch (IOException e) {
            logger.error("Не удалось загрузить players.properties", e);
        }
    }

    private synchronized void saveData() {
        try {
            if (Files.notExists(dataFile.getParent())) {
                Files.createDirectories(dataFile.getParent());
            }
            try (OutputStream out = Files.newOutputStream(dataFile)) {
                lastServers.store(out, "MC Real World - Last player servers");
            }
        } catch (IOException e) {
            logger.error("Не удалось сохранить players.properties", e);
        }
    }
}
