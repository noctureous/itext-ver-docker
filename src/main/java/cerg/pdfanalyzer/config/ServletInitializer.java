package cerg.pdfanalyzer.config;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import cerg.pdfanalyzer.PdfAnalyzerApplication;

/**
 * Servlet initializer for WildFly deployment
 */
public class ServletInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(PdfAnalyzerApplication.class);
    }
}
