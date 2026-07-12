package com.github.shin_ideal.screencapture.client;

public final class WorldScreenPlacement {

    public final int blockX;
    public final int blockY;
    public final int blockZ;
    public final float widthBlocks;
    public final WorldScreenFacing facing;

    public WorldScreenPlacement(int blockX, int blockY, int blockZ, float widthBlocks, WorldScreenFacing facing) {
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.widthBlocks = widthBlocks;
        this.facing = facing;
    }

    public boolean matchesBlock(int x, int y, int z) {
        return blockX == x && blockY == y && blockZ == z;
    }

    public String describe() {
        return blockX + " " + blockY + " " + blockZ + " (" + facing.name().toLowerCase() + ", w="
            + String.format("%.1f", widthBlocks) + ")";
    }
}
