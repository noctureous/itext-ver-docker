package cerg.pdfanalyzer.config;

import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import jakarta.servlet.MultipartConfigElement;

@Configuration
public class MultipartConfig {

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        // Match the configuration in application.properties
        factory.setMaxFileSize(DataSize.ofGigabytes(50));
        factory.setMaxRequestSize(DataSize.ofGigabytes(50));
        return factory.createMultipartConfig();
    }
}