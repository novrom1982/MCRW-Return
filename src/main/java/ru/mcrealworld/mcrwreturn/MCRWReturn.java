package ru.mcrealworld.mcrwreturn;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Plugin(
        id = "mcrw-return",
        name = "MCRW-Return",
        version = "1.0.1",
        description = "Возврат игроков MC Real World на последний игровой сервер после авторизации",
        authors = {"novrom1982"},
        dependencies = {
                @Dependency(id = "authmevelocity", optional = true)
        }
)
public class MCRWReturn {

    private static final String AUTH_SERVER_NAME = "auth";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataFile;
    private final Properties lastServers = new Properties();
    private final Set<UUID> handledSessions = ConcurrentHashMap.newKeySet();

    private Object authStore;
    private Method isLoggedMethod;

    @Inject
    public MCRWReturn(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataFile = dataDirectory.resolve("players.properties");
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadData();
        hookAuthMeVelocity();

        CommandManager commandManager = server.getCommandManager();
        commandManager.register(
                commandManager.metaBuilder("mcrwreturn").plugin(this).build(),
                new ReturnCommand()
        );
        logger.info("MCRW-Return v1.0.1 успешно запущен. Загружено записей: {}", lastServers.size());
    }

    private void hookAuthMeVelocity() {
        Optional<PluginContainer> pluginOpt = server.getPluginManager().getPlugin("authmevelocity");
        if (pluginOpt.isEmpty() || pluginOpt.get().getInstance().isEmpty()) {
            logger.warn("Плагин authmevelocity не найден! Автоматический перехват логина недоступен.");
            return;
        }

        Object pluginInstance = pluginOpt.get().getInstance().get();
        try {
            authStore = findAuthStore(pluginInstance, 2);
            if (authStore == null) {
                logger.warn("Не удалось найти VelocityAuthenticationStore.");
                return;
            }

            for (Method method : authStore.getClass().getDeclaredMethods()) {
                if (method.getReturnType() == boolean.class
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == String.class) {
                    String name = method.getName().toLowerCase(Locale.ROOT);
                    if (name.contains("logged") || name.contains("auth")) {
                        method.setAccessible(true);
                        isLoggedMethod = method;
                        break;
                    }
                }
            }

            if (isLoggedMethod != null) {
                logger.info("Успешно подключён перехват авторизации AuthMeVelocity (метод: {})", isLoggedMethod.getName());
            } else {
                logger.warn("Найден VelocityAuthenticationStore, но не найден метод проверки.");
            }
        } catch (Exception e) {
            logger.error("Ошибка при подключении к AuthMeVelocity", e);
        }
    }
    private Object findAuthStore(Object target, int depth) {
        if (target == null || depth < 0) {
            return null;
        }
        if (target.getClass().getName().endsWith("VelocityAuthenticationStore")) {
            return target;
        }
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null && value.getClass().getName().startsWith("fr.xephi.authme")) {
                        Object found = findAuthStore(value, depth - 1);
                        if (found != null) {
                            return found;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private boolean isPlayerAuthenticated(String username) {
        if (authStore == null || isLoggedMethod == null) {
            return false;
        }
        try {
            Object res = isLoggedMethod.invoke(authStore, username.toLowerCase(Locale.ROOT));
            return Boolean.TRUE.equals(res);
        } catch (Exception e) {
            return false;
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String serverName = event.getServer().getServerInfo().getName();

        if (serverName.equalsIgnoreCase(AUTH_SERVER_NAME)) {
            if (event.getPreviousServer().isPresent()) {
                handledSessions.add(uuid);
                return;
            }
            if (handledSessions.add(uuid)) {
                startAuthWatcher(player);
            }
            return;
        }

        handledSessions.add(uuid);
        String previous = lastServers.getProperty(uuid.toString());
        if (!serverName.equals(previous)) {
            lastServers.setProperty(uuid.toString(), serverName);
            saveData();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        handledSessions.remove(event.getPlayer().getUniqueId());
    }

    private void startAuthWatcher(Player player) {
        String targetServerName = lastServers.getProperty(player.getUniqueId().toString());
        if (targetServerName == null || targetServerName.isBlank() || targetServerName.equalsIgnoreCase(AUTH_SERVER_NAME)) {
            return;
        }

        AtomicInteger attempts = new AtomicInteger(0);
        server.getScheduler().buildTask(this, task -> {
            if (!player.isActive() || attempts.incrementAndGet() > 240) {
                task.cancel();
                return;
            }

            Optional<String> currentServer = player.getCurrentServer().map(s -> s.getServerInfo().getName());
            if (currentServer.isEmpty() || !currentServer.get().equalsIgnoreCase(AUTH_SERVER_NAME)) {
                task.cancel();
                return;
            }

            if (isPlayerAuthenticated(player.getUsername())) {
                task.cancel();
                sendToLastServer(player);
            }
        }).delay(Duration.ofMillis(300)).repeat(Duration.ofMillis(250)).schedule();
    }

    private void sendToLastServer(Player player) {
        String targetServerName = lastServers.getProperty(player.getUniqueId().toString());
        if (targetServerName == null || targetServerName.isBlank() || targetServerName.equalsIgnoreCase(AUTH_SERVER_NAME)) {
            return;
        }

        Optional<RegisteredServer> targetServer = server.getServer(targetServerName);
        if (targetServer.isEmpty()) {
            logger.warn("Сервер '{}' для игрока {} не найден в velocity.toml", targetServerName, player.getUsername());
            return;
        }

        player.getCurrentServer().ifPresent(current -> {
            if (current.getServerInfo().getName().equalsIgnoreCase(AUTH_SERVER_NAME)) {
                logger.info("Возвращаем игрока {} на последний сервер: {}", player.getUsername(), targetServerName);
                player.createConnectionRequest(targetServer.get()).fireAndForget();
            }
        });
    }

    private class ReturnCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (invocation.source() instanceof Player player) {
                sendToLastServer(player);
            }
        }
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
