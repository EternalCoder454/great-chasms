package com.zachary.greatchasms.chasm;

/**
 * A small, allocation free, fully deterministic value noise.
 * <p>
 * Minecraft's own noise types are deliberately avoided here. Those are tied to a {@code RandomState}
 * and to per-chunk seeded RNG, which makes them awkward to evaluate for a feature that has to be
 * globally coherent across thousands of blocks and many chunks. Everything in this class is a pure
 * function of (seed, coordinates), so a chasm looks identical no matter which chunk asks about it,
 * in which order chunks generate, or how many threads C2ME is generating on at once.
 * <p>
 * Every method is stateless past the final seed field, so instances are safe to share across threads
 * without synchronisation.
 */
public final class ChasmNoise {

    private final long seed;

    public ChasmNoise(long worldSeed, int salt) {
        // Mixing the salt in rather than adding it keeps the different noise layers (path, width,
        // region, walls) from being correlated, which would make chasm width track chasm position.
        this.seed = mix(worldSeed ^ (salt * 0x632BE59BD9B4E019L));
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    /** Lattice value in [-1, 1] for a 2D integer coordinate. */
    private double lattice(int x, int z) {
        long h = mix(this.seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL));
        // 53 significant bits gives an evenly spread double in [0, 1), then remap to [-1, 1)
        return (((h >>> 11) * 0x1.0p-53) * 2.0) - 1.0;
    }

    /** Lattice value in [-1, 1] for a 3D integer coordinate. */
    private double lattice(int x, int y, int z) {
        long h = mix(this.seed
                ^ (x * 0x9E3779B97F4A7C15L)
                ^ (y * 0x165667B19E3779F9L)
                ^ (z * 0xC2B2AE3D27D4EB4FL));
        return (((h >>> 11) * 0x1.0p-53) * 2.0) - 1.0;
    }

    /** Quintic smoothstep. Its first and second derivatives vanish at 0 and 1, so the gradient
     *  estimate taken in {@link ChasmField} stays continuous and chasm walls do not develop seams
     *  along lattice boundaries. */
    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    public double value(double x, double z) {
        int x0 = floor(x);
        int z0 = floor(z);
        double u = fade(x - x0);
        double v = fade(z - z0);

        double n00 = lattice(x0, z0);
        double n10 = lattice(x0 + 1, z0);
        double n01 = lattice(x0, z0 + 1);
        double n11 = lattice(x0 + 1, z0 + 1);

        return lerp(v, lerp(u, n00, n10), lerp(u, n01, n11));
    }

    public double value(double x, double y, double z) {
        int x0 = floor(x);
        int y0 = floor(y);
        int z0 = floor(z);
        double u = fade(x - x0);
        double v = fade(y - y0);
        double w = fade(z - z0);

        double n000 = lattice(x0, y0, z0);
        double n100 = lattice(x0 + 1, y0, z0);
        double n010 = lattice(x0, y0 + 1, z0);
        double n110 = lattice(x0 + 1, y0 + 1, z0);
        double n001 = lattice(x0, y0, z0 + 1);
        double n101 = lattice(x0 + 1, y0, z0 + 1);
        double n011 = lattice(x0, y0 + 1, z0 + 1);
        double n111 = lattice(x0 + 1, y0 + 1, z0 + 1);

        double x00 = lerp(u, n000, n100);
        double x10 = lerp(u, n010, n110);
        double x01 = lerp(u, n001, n101);
        double x11 = lerp(u, n011, n111);

        return lerp(w, lerp(v, x00, x10), lerp(v, x01, x11));
    }

    /** Fractal sum, normalised back into [-1, 1]. */
    public double fbm(double x, double z, int octaves) {
        double sum = 0.0;
        double amplitude = 1.0;
        double total = 0.0;
        double fx = x;
        double fz = z;
        for (int i = 0; i < octaves; i++) {
            sum += value(fx, fz) * amplitude;
            total += amplitude;
            amplitude *= 0.5;
            fx *= 2.0;
            fz *= 2.0;
        }
        return sum / total;
    }

    public double fbm(double x, double y, double z, int octaves) {
        double sum = 0.0;
        double amplitude = 1.0;
        double total = 0.0;
        double fx = x;
        double fy = y;
        double fz = z;
        for (int i = 0; i < octaves; i++) {
            sum += value(fx, fy, fz) * amplitude;
            total += amplitude;
            amplitude *= 0.5;
            fx *= 2.0;
            fy *= 2.0;
            fz *= 2.0;
        }
        return sum / total;
    }
}
