package meridian.markers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import meridian.api.module.ModuleContext;
import meridian.core.api.Marker;
import meridian.core.api.MarkerArchive;
import meridian.core.api.MarkerSource;
import org.slf4j.Logger;

/**
 * The markers, kept between sessions - and offered to anything that draws a map.
 *
 * <p>Core remembers markers while the proxy runs and knows how to read its own memory; this holds
 * that memory on disk. The two halves are deliberately apart: what a marker <em>is</em> changes
 * with the game and belongs to core, while a file that has to survive an update belongs with the
 * module a person installs to manage their markers.
 *
 * <p>It is also where {@link MarkerSource} comes from - every marker for every world, and the
 * pictures they are drawn with. meridian-world-map draws its map with these; without this module
 * installed it falls back to the live markers and its own shapes.
 */
final class MarkerKeeper implements MarkerSource {

    /** Often enough that a crash costs little, rarely enough that a busy world is not rewritten. */
    private static final Duration SAVE = Duration.ofSeconds(30);

    private final MarkerArchive archive;
    private final Path file;
    private final Logger log;

    private MarkerKeeper(MarkerArchive archive, Path file, Logger log) {
        this.archive = archive;
        this.file = file;
        this.log = log;
    }

    /**
     * Reads what was kept, gives it back to core, and takes over the writing from here on.
     *
     * <p>The first run after this module is installed also looks where core used to write, so a
     * player's markers carry over rather than starting again. Core leaves that file alone.
     */
    static MarkerKeeper start(ModuleContext ctx, MarkerArchive archive) {
        Logger log = ctx.getLogger();
        Path file = ctx.getDataDir().resolve("markers.json");
        MarkerKeeper keeper = new MarkerKeeper(archive, file, log);
        keeper.restore(file);
        if (archive.isEmpty()) {
            keeper.restore(coreFile(ctx));
        }
        ctx.scheduler().scheduleAtFixedRate(
                () -> ctx.offloadExecutor().execute(keeper::saveIfChanged), SAVE, SAVE);
        ctx.onShutdown(keeper::saveIfChanged);
        return keeper;
    }

    // ------------------------------------------------------------------
    // MarkerSource - what a map draws
    // ------------------------------------------------------------------

    @Override
    public List<Marker> markers(UUID world) {
        return archive.markers(world);
    }

    @Override
    public List<UUID> worlds() {
        return archive.worlds();
    }

    @Override
    public Optional<byte[]> icon(String icon) {
        return MarkerIcons.bytes(icon);
    }

    // ------------------------------------------------------------------

    private void restore(Path from) {
        if (from == null || !Files.isRegularFile(from)) {
            return;
        }
        try {
            archive.restore(Files.readString(from));
            log.info("meridian-markers: markers restored from {}", from);
        } catch (IOException | RuntimeException e) {
            // Markers rebuild themselves from the server within a minute of play, so a file we
            // cannot read costs a little memory of the past and nothing else.
            log.warn("meridian-markers: could not read {}: {}", from, e.toString());
        }
    }

    private void saveIfChanged() {
        if (!archive.hasChanges()) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, archive.export());
            // Into place in one step: a crash mid-write leaves the previous file whole rather
            // than half a new one.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            log.warn("meridian-markers: could not write {}: {}", file, e.toString());
        }
    }

    /**
     * Where core used to keep the markers: its own folder, beside this module's.
     *
     * <p>A guess at another module's data directory, and the only place it is made - the proxy
     * lays every module's folder out the same way, and being wrong here costs one skipped import.
     */
    private static Path coreFile(ModuleContext ctx) {
        Path modules = ctx.getDataDir().getParent() == null ? null
                : ctx.getDataDir().getParent().getParent();
        return modules == null ? null
                : modules.resolve("meridian-core").resolve("data").resolve("markers.json");
    }
}
