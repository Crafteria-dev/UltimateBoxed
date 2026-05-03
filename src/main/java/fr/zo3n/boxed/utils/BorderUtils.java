package fr.zo3n.boxed.utils;

import org.bukkit.Chunk;
import org.bukkit.Location;

/**
 * Utility class providing helper methods for world border size calculations.
 *
 * <p>All methods are pure functions (no side-effects) and are safe to call from
 * any thread.</p>
 */
public final class BorderUtils {

    /** Side length of a Minecraft chunk in blocks. */
    public static final int CHUNK_SIZE = 16;

    private BorderUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Converts a chunk count to a {@link org.bukkit.WorldBorder} side-length in blocks.
     *
     * <p>{@code WorldBorder.setSize()} expects the total diameter (full side length),
     * not the radius. A zone of 1 chunk is exactly 16×16 blocks, so the diameter is 16.</p>
     *
     * <pre>
     *   chunks=1  → 16.0  (16×16 block zone)
     *   chunks=2  → 32.0  (32×32 block zone)
     *   chunks=n  → n × 16.0
     * </pre>
     *
     * @param chunks zone size in chunks (must be &gt;= 1)
     * @return border diameter in blocks
     */
    public static double chunksToBlockSize(int chunks) {
        return chunks * (double) CHUNK_SIZE;
    }

    /**
     * Returns the centre of the chunk that contains the given location.
     *
     * <p>The Y coordinate is preserved from the input location.</p>
     *
     * @param location any location inside a chunk
     * @return the centre (block-aligned) of that chunk at the same Y
     */
    public static Location getChunkCenter(Location location) {
        Chunk chunk = location.getChunk();
        double x = (chunk.getX() * CHUNK_SIZE) + (CHUNK_SIZE / 2.0);
        double z = (chunk.getZ() * CHUNK_SIZE) + (CHUNK_SIZE / 2.0);
        return new Location(location.getWorld(), x, location.getY(), z);
    }

    /**
     * Returns the chunk-aligned border centre for the given world-space coordinates.
     *
     * <p>Snaps the provided (x, z) pair to the nearest chunk-centre grid point.</p>
     *
     * @param worldX world X coordinate
     * @param worldZ world Z coordinate
     * @return double array {@code [snappedX, snappedZ]}
     */
    public static double[] snapToChunkCenter(double worldX, double worldZ) {
        int chunkX = (int) Math.floor(worldX / CHUNK_SIZE);
        int chunkZ = (int) Math.floor(worldZ / CHUNK_SIZE);
        return new double[]{
                (chunkX * CHUNK_SIZE) + (CHUNK_SIZE / 2.0),
                (chunkZ * CHUNK_SIZE) + (CHUNK_SIZE / 2.0)
        };
    }

    /**
     * Checks whether the given location is inside the described rectangular border.
     *
     * @param location  location to test
     * @param centerX   X of the border centre
     * @param centerZ   Z of the border centre
     * @param halfSize  half of the border's side length (radius)
     * @return {@code true} if the location is within the border
     */
    public static boolean isInsideBorder(Location location,
                                          double centerX, double centerZ, double halfSize) {
        return Math.abs(location.getX() - centerX) <= halfSize
                && Math.abs(location.getZ() - centerZ) <= halfSize;
    }
}
