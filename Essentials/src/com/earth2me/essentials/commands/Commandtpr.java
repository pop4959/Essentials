package com.earth2me.essentials.commands;

import com.earth2me.essentials.RandomTeleport;
import com.earth2me.essentials.Trade;
import com.earth2me.essentials.User;
import com.earth2me.essentials.utils.VersionUtil;
import io.papermc.lib.PaperLib;
import net.ess3.api.events.UserRandomTeleportEvent;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static com.earth2me.essentials.I18n.tl;


public class Commandtpr extends EssentialsCommand {
    private static final Random RANDOM = new Random();
    private static final int HIGHEST_BLOCK_Y_OFFSET = VersionUtil.getServerBukkitVersion().isHigherThanOrEqualTo(VersionUtil.v1_15_R01) ? 1 : 0;

    public Commandtpr() {
        super("tpr");
    }

    @Override
    protected void run(Server server, User user, String commandLabel, String[] args) throws Exception {
        final Trade charge = new Trade(this.getName(), ess);
        charge.isAffordableFor(user);
        RandomTeleport randomTeleport = ess.getRandomTeleport();
        UserRandomTeleportEvent event = new UserRandomTeleportEvent(user, randomTeleport.getCenter(), randomTeleport.getMinRange(), randomTeleport.getMaxRange());
        server.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        Location center = event.getCenter();
        double minRange = event.getMinRange();
        double maxRange = event.getMaxRange();
        getRandomLocation(randomTeleport, center, minRange, maxRange).thenAccept(location -> {
            CompletableFuture<Boolean> future = getNewExceptionFuture(user.getSource(), commandLabel);
            user.getAsyncTeleport().teleport(location, charge, PlayerTeleportEvent.TeleportCause.COMMAND, future);
            future.thenAccept(success -> {
                if (success) {
                    user.sendMessage(tl("tprSuccess"));
                }
            });
        });
    }

    // Get a random location; cached if possible. Otherwise on demand.
    private CompletableFuture<Location> getRandomLocation(RandomTeleport randomTeleport, Location center, double minRange, double maxRange) {
        int findAttempts = randomTeleport.getFindAttempts();
        Queue<Location> cachedLocations = randomTeleport.getCachedLocations();
        // Try to build up the cache if it is below the threshold
        if (cachedLocations.size() < randomTeleport.getCacheThreshold()) {
            ess.getServer().getScheduler().scheduleSyncDelayedTask(ess, () -> {
                for (int i = 0; i < findAttempts; ++i) {
                    calculateRandomLocation(center, minRange, maxRange).thenAccept(location -> {
                        if (isValidRandomLocation(randomTeleport, location)) {
                            randomTeleport.getCachedLocations().add(location);
                        }
                    });
                }
            });
        }
        CompletableFuture<Location> future = new CompletableFuture<>();
        // Return a random location immediately if one is available, otherwise try to find one now
        if (cachedLocations.isEmpty()) {
            if (PaperLib.isPaper()) {
                attemptRandomLocationPaper(findAttempts, randomTeleport, center, minRange, maxRange).thenAccept(future::complete);
            } else {
                attemptRandomLocationSpigot(findAttempts, randomTeleport, center, minRange, maxRange).thenAccept(future::complete);
            }
        } else {
            future.complete(cachedLocations.poll());
        }
        return future;
    }

    // Asynchronously attempt to find a random location, caching any extras, or returning the center if none is found.
    // Ideal for server implementations like Paper, since attempts can be made in parallel rather than sequentially.
    private CompletableFuture<Location> attemptRandomLocationPaper(int attempts, RandomTeleport randomTeleport, Location center, double minRange, double maxRange) {
        CompletableFuture<Location> future = new CompletableFuture<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; ++i) {
            final int n = i;
            futures.add(calculateRandomLocation(center, minRange, maxRange).thenAccept(location -> {
                if (isValidRandomLocation(randomTeleport, location)) {
                    if (future.isDone()) {
                        randomTeleport.getCachedLocations().add(location);
                    } else {
                        future.complete(location);
                    }
                }
                futures.get(n).complete(null);
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenAccept(ignored -> {
            if (!future.isDone()) {
                future.complete(center);
            }
        });
        return future;
    }

    // Recursively attempt to find a random location. After a maximum number of attempts, the center is returned.
    // Ideal for server implementations like Spigot, since it must be done sequentially on the main thread.
    private CompletableFuture<Location> attemptRandomLocationSpigot(int attempts, RandomTeleport randomTeleport, Location center, double minRange, double maxRange) {
        CompletableFuture<Location> future = new CompletableFuture<>();
        if (attempts > 0) {
            calculateRandomLocation(center, minRange, maxRange).thenAccept(location -> {
                if (isValidRandomLocation(randomTeleport, location)) {
                    future.complete(location);
                } else {
                    attemptRandomLocationSpigot(attempts - 1, randomTeleport, center, minRange, maxRange).thenAccept(future::complete);
                }
            });
        } else {
            future.complete(center);
        }
        return future;
    }

    // Calculates a random location asynchronously.
    private CompletableFuture<Location> calculateRandomLocation(Location center, double minRange, double maxRange) {
        CompletableFuture<Location> future = new CompletableFuture<>();
        final int dx = RANDOM.nextBoolean() ? 1 : -1, dz = RANDOM.nextBoolean() ? 1 : -1;
        Location location = new Location(
                center.getWorld(),
                center.getX() + dx * (minRange + RANDOM.nextDouble() * (maxRange - minRange)),
                center.getWorld().getMaxHeight(),
                center.getZ() + dz * (minRange + RANDOM.nextDouble() * (maxRange - minRange)),
                360 * RANDOM.nextFloat() - 180,
                0
        );
        PaperLib.getChunkAtAsync(location).thenAccept(ignored -> {
            location.setY(center.getWorld().getHighestBlockYAt(location) + HIGHEST_BLOCK_Y_OFFSET);
            future.complete(location);
        });
        return future;
    }

    private boolean isValidRandomLocation(RandomTeleport randomTeleport, Location location) {
        return !randomTeleport.getExcludedBiomes().contains(location.getBlock().getBiome());
    }

    @Override
    protected List<String> getTabCompleteOptions(Server server, User user, String commandLabel, String[] args) {
        return Collections.emptyList();
    }
}
