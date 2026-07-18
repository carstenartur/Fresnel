package org.fresnel.optics;

/**
 * Declares how much of a plugin editor can be generated from schema metadata.
 *
 * <p>The value is descriptive metadata, not a class-loading instruction. Frontend
 * implementations may select only trusted, compile-time components for extension
 * points and custom editors.</p>
 */
public enum PluginEditorMode {

    /** All ordinary parameter controls can be rendered by the common schema form. */
    SCHEMA,

    /** Standard parameters use the schema form and trusted advanced panels extend it. */
    SCHEMA_WITH_EXTENSIONS,

    /** The plugin currently needs a fully custom trusted editor implementation. */
    CUSTOM
}
