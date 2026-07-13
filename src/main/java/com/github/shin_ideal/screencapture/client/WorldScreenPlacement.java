package com.github.shin_ideal.screencapture.client;

public final class WorldScreenPlacement {

    public final double centerX;
    public final double centerY;
    public final double centerZ;
    public final float widthBlocks;
    public final WorldScreenFacing facing;

    public WorldScreenPlacement(double centerX, double centerY, double centerZ, float widthBlocks,
                                WorldScreenFacing facing) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.widthBlocks = widthBlocks;
        this.facing = facing;
    }

    public String describe() {
        return String.format(
            "%.1f %.1f %.1f (%s, w=%.1f)",
            centerX,
            centerY,
            centerZ,
            facing.name()
                .toLowerCase(),
            widthBlocks);
    }
}
