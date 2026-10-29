package org.reportservice.configuration;

import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Makes every {@link DataSource} wait for {@link DatabaseSettingsCheck}, so a missing setting is reported first. */
@Component
public class DataSourceDependsOnDatabaseProperties extends AbstractDependsOnBeanFactoryPostProcessor {

    public DataSourceDependsOnDatabaseProperties() {
        super(DataSource.class, DatabaseSettingsCheck.class);
    }
}
