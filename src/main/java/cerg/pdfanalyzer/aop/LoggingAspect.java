package cerg.pdfanalyzer.aop;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LoggingAspect {
    private static final Logger logger = LoggerFactory.getLogger(LoggingAspect.class);

    @Pointcut("within(cerg.pdfanalyzer.service..*) && !within(cerg.pdfanalyzer.controller..*)")
    public void servicePackagePointcut() {}

    @Pointcut("execution(public * cerg.pdfanalyzer.controller.PdfAnalyzerController.analyzePdf(..))")
    public void analysisEndpointPointcut() {}

    @Before("servicePackagePointcut() && execution(* analyzePdf(..))")
    public void logServiceMethodEntry(JoinPoint joinPoint) {
        logger.debug("Starting PDF analysis: {}.{}()",
            joinPoint.getSignature().getDeclaringTypeName(),
            joinPoint.getSignature().getName());
    }

    @AfterReturning(pointcut = "servicePackagePointcut() && execution(* analyzePdf(..))", returning = "result")
    public void logServiceMethodExit(JoinPoint joinPoint, Object result) {
        logger.debug("Completed PDF analysis: {}.{}()",
            joinPoint.getSignature().getDeclaringTypeName(),
            joinPoint.getSignature().getName());
    }

    @Before("analysisEndpointPointcut()")
    public void logAnalysisEndpoint(JoinPoint joinPoint) {
        logger.info("PDF analysis request received");
    }

    @AfterThrowing(pointcut = "servicePackagePointcut()", throwing = "e")
    public void logMethodException(JoinPoint joinPoint, Throwable e) {
        logger.error("Exception in {}.{}(): {}",
            joinPoint.getSignature().getDeclaringTypeName(),
            joinPoint.getSignature().getName(),
            e.getMessage(), e);
    }
}
