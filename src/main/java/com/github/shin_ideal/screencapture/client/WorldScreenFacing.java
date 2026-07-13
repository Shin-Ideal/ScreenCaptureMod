package com.github.shin_ideal.screencapture.client;

public enum WorldScreenFacing {
    NORTH(0.0F, 0.0F, -1.0F, 1.0F, 0.0F),
    SOUTH(0.0F, 0.0F, 1.0F, -1.0F, 0.0F),
    EAST(1.0F, 0.0F, 0.0F, 0.0F, 1.0F),
    WEST(-1.0F, 0.0F, 0.0F, 0.0F, -1.0F);

    /**
     * Unit normal pointing out of the visible face.
     */
    public final float normalX;
    public final float normalY;
    public final float normalZ;
    /**
     * Horizontal right vector when viewing the face.
     */
    public final float rightX;
    public final float rightZ;

    WorldScreenFacing(float normalX, float normalY, float normalZ, float rightX, float rightZ) {
        this.normalX = normalX;
        this.normalY = normalY;
        this.normalZ = normalZ;
        this.rightX = rightX;
        this.rightZ = rightZ;
    }

    public static WorldScreenFacing fromBlockSide(int sideHit) {
        switch (sideHit) {
            case 2:
                return NORTH;
            case 3:
                return SOUTH;
            case 4:
                return WEST;
            case 5:
                return EAST;
            default:
                return null;
        }
    }

    /**
     * Facing of a screen placed in front of the player so the visible side faces the player.
     */
    public static WorldScreenFacing facingPlayer(float rotationYaw) {
        float yaw = rotationYaw % 360.0F;
        if (yaw < 0.0F) {
            yaw += 360.0F;
        }
        // Player look: 0=south, 90=west, 180=north, 270=east.
        // Screen faces back toward the player.
        if (yaw >= 315.0F || yaw < 45.0F) {
            return NORTH;
        }
        if (yaw < 135.0F) {
            return EAST;
        }
        if (yaw < 225.0F) {
            return SOUTH;
        }
        return WEST;
    }

    public static WorldScreenFacing fromName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String key = name.toLowerCase();
        switch (key) {
            case "north":
            case "n":
                return NORTH;
            case "south":
            case "s":
                return SOUTH;
            case "east":
            case "e":
                return EAST;
            case "west":
            case "w":
                return WEST;
            default:
                return null;
        }
    }

    public static String[] names() {
        return new String[]{"north", "south", "east", "west"};
    }
}
