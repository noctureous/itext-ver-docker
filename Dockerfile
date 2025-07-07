# Multi-stage Dockerfile for PDF Analyzer on WildFly 27

# Stage 1: Build frontend
FROM node:18-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci --only=production
COPY frontend/ ./
RUN npm run build

# Stage 2: Build backend
FROM maven:3.9.6-eclipse-temurin-17 AS backend-builder
WORKDIR /app

# Copy POM file first for better layer caching
COPY pom.xml .

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY src/ ./src/
COPY --from=frontend-builder /app/frontend/dist ./src/main/resources/static/

# Build the WAR file
RUN mvn clean package -DskipTests

# Stage 3: Runtime with WildFly 27
FROM quay.io/wildfly/wildfly:27.0.1.Final-jdk17

# Set environment variables
ENV WILDFLY_HOME=/opt/jboss/wildfly
ENV JBOSS_HOME=/opt/jboss/wildfly
ENV DEPLOYMENT_DIR=${WILDFLY_HOME}/standalone/deployments

# Set logging manager early to prevent conflicts
ENV JAVA_OPTS="-Djava.util.logging.manager=org.jboss.logmanager.LogManager -XX:+EnableDynamicAgentLoading"

# Create application user
USER root

# Try to install tesseract using microdnf (if available)
RUN microdnf install -y tesseract fontconfig curl || \
    echo "microdnf not available, continuing without package installation"

# Set tessdata environment variable to our custom location
ENV TESSDATA_PREFIX=/opt/tessdata

# Create tessdata directory and copy all local tessdata files
RUN mkdir -p $TESSDATA_PREFIX
COPY tessdata/ $TESSDATA_PREFIX/

# Create directories for uploads and logs
RUN mkdir -p /opt/pdf-analyzer/uploads \
             /opt/pdf-analyzer/logs \
             /opt/pdf-analyzer/temp && \
    chown -R jboss:jboss /opt/pdf-analyzer

# Switch back to jboss user
USER jboss

# Copy MySQL driver and module configuration
COPY --chown=jboss:jboss docker/mysql-connector-j-8.3.0.jar ${WILDFLY_HOME}/modules/com/mysql/main/
COPY --chown=jboss:jboss docker/modules/com/mysql/main/module.xml ${WILDFLY_HOME}/modules/com/mysql/main/

# Copy WildFly configuration
COPY --chown=jboss:jboss docker/standalone.xml ${WILDFLY_HOME}/standalone/configuration/

# Copy application WAR
COPY --from=backend-builder --chown=jboss:jboss /app/target/pdf-analyzer.war ${DEPLOYMENT_DIR}/

# Copy startup script
COPY --chown=jboss:jboss docker/startup.sh /opt/jboss/
RUN chmod +x /opt/jboss/startup.sh

# Expose ports
EXPOSE 8080 9990

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/pdf-analyzer/ || exit 1

# Set working directory
WORKDIR ${WILDFLY_HOME}

# Start WildFly
CMD ["/opt/jboss/startup.sh"]
