package com.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;

// DataSourceAutoConfiguration excluded — DataSourceConfig provides a manual
// primary+replica routing DataSource wrapped in LazyConnectionDataSourceProxy.
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableCaching
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
