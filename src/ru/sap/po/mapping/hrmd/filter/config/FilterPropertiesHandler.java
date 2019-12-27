package ru.sap.po.mapping.hrmd.filter.config;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import java.io.IOException;
import java.io.InputStream;

public class FilterPropertiesHandler {

    private static final String PROPERTIES_FILENAME = "filter.properties";

    private static FilterPropertiesHandler instance;
    private Properties properties;

    private FilterPropertiesHandler() {
        properties = loadPropertiesFromClasspath();
    }

    public static synchronized FilterPropertiesHandler getInstance() {
        if (instance == null) {
            instance = new FilterPropertiesHandler();
        }
        return instance;
    }

    private Properties loadPropertiesFromClasspath() {
        try (InputStream input = this.getClass().getClassLoader().getResourceAsStream(PROPERTIES_FILENAME)) {
            Properties prop = new Properties();
            prop.load(input);
            return prop;
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    public String getPropertyValue(String key) {
        if (properties != null) {
            return properties.getProperty(key);
        }
        return null;
    }

    public List<String> getListPropertyValue(String key) {
        String propertyValue = this.getPropertyValue(key);
        if (propertyValue != null) {
            return Arrays.asList(propertyValue.split(","));
        }
        return null;
    }

}
