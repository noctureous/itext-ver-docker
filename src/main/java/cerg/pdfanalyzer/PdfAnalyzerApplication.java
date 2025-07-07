package cerg.pdfanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAspectJAutoProxy
public class PdfAnalyzerApplication {
    
    static {
        // Set the logging manager before any logging occurs
        System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
    }
    
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PdfAnalyzerApplication.class);
        
        // Disable Spring Boot's banner to reduce startup noise
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        
        // Disable logging auto-configuration
        app.setLogStartupInfo(false);
        
        app.run(args);
    }
}
