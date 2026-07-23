package org.fresnel.optics;

/** PCL logical page orientation and its deterministic command value. */
public enum PclPageOrientation {
    PORTRAIT(0),
    LANDSCAPE(1);

    private final int commandValue;

    PclPageOrientation(int commandValue) {
        this.commandValue = commandValue;
    }

    public int commandValue() {
        return commandValue;
    }
}
