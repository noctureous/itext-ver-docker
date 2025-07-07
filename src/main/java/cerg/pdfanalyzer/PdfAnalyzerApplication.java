package cerg.pdfanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAspectJAutoProxy
public class PdfAnalyzerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PdfAnalyzerApplication.class, args);
    }
}
