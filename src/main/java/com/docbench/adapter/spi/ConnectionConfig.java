package com.docbench.adapter.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration for database connections.
 * Supports both URI-style and parameter-style configuration.
 */
public final class ConnectionConfig {

    private final String uri;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final Map<String, Object> options;

    private ConnectionConfig(Builder builder) {
        this.uri = builder.uri;
        this.host = builder.host;
        this.port = builder.port;
        this.database = builder.database;
        this.username = builder.username;
        this.password = builder.password;
        this.options = Map.copyOf(builder.options);
    }

    /**
     * Creates a configuration from a connection URI.
     *
     * @param uri the connection URI
     * @return the connection config
     */
    public static ConnectionConfig fromUri(String uri) {
        return new Builder().uri(uri).build();
    }

    /**
     * Creates a configuration from a connection URI and database name.
     *
     * @param uri      the connection URI
     * @param database the database name
     * @return the connection config
     */
    public static ConnectionConfig fromUri(String uri, String database) {
        return new Builder().uri(uri).database(database).build();
    }

    /**
     * Returns a new builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the connection URI, if set.
     */
    public Optional<String> uri() {
        return Optional.ofNullable(uri);
    }

    /**
     * Returns the host.
     */
    public String host() {
        return host != null ? host : "localhost";
    }

    /**
     * Returns the port.
     */
    public int port() {
        return port;
    }

    /**
     * Returns the database name.
     */
    public String database() {
        return database != null ? database : "docbench";
    }

    /**
     * Returns the username, if set.
     */
    public Optional<String> username() {
        return Optional.ofNullable(username);
    }

    /**
     * Returns the password, if set.
     */
    public Optional<String> password() {
        return Optional.ofNullable(password);
    }

    /**
     * Returns all options.
     */
    public Map<String, Object> options() {
        return options;
    }

    /**
     * Returns a specific option value.
     *
     * @param key the option key
     * @param <T> the value type
     * @return the option value, or empty if not set
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getOption(String key) {
        return Optional.ofNullable((T) options.get(key));
    }

    /**
     * Returns a specific option value with a default.
     *
     * @param key          the option key
     * @param defaultValue the default value
     * @param <T>          the value type
     * @return the option value, or the default
     */
    @SuppressWarnings("unchecked")
    public <T> T getOption(String key, T defaultValue) {
        Object value = options.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * Returns an integer option.
     */
    public int getIntOption(String key, int defaultValue) {
        Object value = options.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            return Integer.parseInt(s);
        }
        return defaultValue;
    }

    /**
     * Returns a boolean option.
     */
    public boolean getBooleanOption(String key, boolean defaultValue) {
        Object value = options.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    @Override
    public String toString() {
        return "ConnectionConfig{" +
                "uri='" + (uri != null ? "[set]" : "[unset]") + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", database='" + database + '\'' +
                ", username='" + (username != null ? "[set]" : "[unset]") + '\'' +
                ", options=" + options.keySet() +
                '}';
    }

    /**
     * Builder for ConnectionConfig.
     */
    public static final class Builder {
        private String uri;
        private String host = "localhost";
        private int port = 0;
        private String database = "docbench";
        private String username;
        private String password;
        private final Map<String, Object> options = new java.util.HashMap<>();

        private Builder() {
        }

        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public Builder host(String host) {
            this.host = Objects.requireNonNull(host);
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder database(String database) {
            this.database = Objects.requireNonNull(database);
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder option(String key, Object value) {
            this.options.put(Objects.requireNonNull(key), value);
            return this;
        }

        public Builder options(Map<String, Object> options) {
            this.options.putAll(Objects.requireNonNull(options));
            return this;
        }

        public ConnectionConfig build() {
            return new ConnectionConfig(this);
        }
    }
}
