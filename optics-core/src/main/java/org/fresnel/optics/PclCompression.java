package org.fresnel.optics;

/** Bounded PCL raster compression modes supported by the native exporter. */
public enum PclCompression {
    NONE(0),
    TIFF(2);

    private final int commandValue;

    PclCompression(int commandValue) {
        this.commandValue = commandValue;
    }

    public int commandValue() {
        return commandValue;
    }
}
