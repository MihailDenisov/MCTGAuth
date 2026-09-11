package dev.example.mcauth.api;

/**
 * Marker for authentication persistence boundaries. Concrete storage belongs to
 * an infrastructure module; shared code must not expose JDBC or SQLite types.
 */
public interface AuthDatabase {
}
