package com.github.shin_ideal.screencapture.client;

public enum WorldScreenFacing {
    NORTH(0.0F, 0.0F, -0.502F, 1.0F, 0.0F),
    SOUTH(0.0F, 0.0F, 0.502F, -1.0F, 0.0F),
    EAST(0.502F, 0.0F, 0.0F, 0.0F, 1.0F),
    WEST(-0.502F, 0.0F, 0.0F, 0.0F, -1.0F);

    public final float offsetX;
    public final float offsetY;
    public final float offsetZ;
    public final float rightX;
    public final float rightZ;

    WorldScreenFacing(float offsetX, float offsetY, float offsetZ, float rightX, float rightZ) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.rightX = rightX;
        this.rightZ = rightZ;
    }
}
