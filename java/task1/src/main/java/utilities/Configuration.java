package utilities;

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Loads and provides typed access to configuration values.
 */
public final class Configuration {

    /** Parsed configuration payload. */
    private JSONObject config;

    /** Backing file name for configuration. */
    private final String fileName;

    /**
     * Creates a configuration from a file on the classpath.
     *
     * @param newFileName the config file to read
     */
    public Configuration(final String newFileName) {
        this.fileName = newFileName;

        // Resolve the resource from the classpath.
        final ClassLoader cl = this.getClass().getClassLoader();
        final URL resourceUrl = cl.getResource(this.fileName);
        final File configFile =
                new File(Objects.requireNonNull(resourceUrl).getFile());

        try {
            final String raw =
                FileUtils.readFileToString(
                    configFile,
                    StandardCharsets.UTF_8
                );
            this.config = new JSONObject(raw);
        } catch (final IOException e) {
            // In a real app, prefer a logger and rethrow as unchecked.
            e.printStackTrace();
            this.config = new JSONObject();
        }
    }

    /**
     * Returns the string value for the given key.
     *
     * @param key configuration key
     * @return the string value or null if missing
     */
    public String getString(final String key) {
        return this.config.getString(key);
    }

    /**
     * Returns the integer value for the given key.
     *
     * @param key configuration key
     * @return integer value
     * @throws NumberFormatException if not parseable
     */
    public Integer getInt(final String key) {
        return this.config.getInt(key);
    }

    /**
     * Returns the double value for the given key.
     *
     * @param key configuration key
     * @return double value
     * @throws NumberFormatException if not parseable
     */
    public Double getDouble(final String key) {
        return this.config.getDouble(key);
    }
}
