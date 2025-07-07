# Multi-stage Dockerfile for PDF Analyzer on WildFly 27

# Stage 1: Build frontend
FROM node:18-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci --only=production
COPY frontend/ ./
RUN npm run build

# Stage 2: Build backend
FROM maven:3.9.6-openjdk-17-slim AS backend-builder
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

# Create application user
USER root

# Install required packages for OCR and fonts
RUN microdnf update -y && \
    microdnf install -y \
        tesseract \
        tesseract-langpack-eng \
        tesseract-langpack-chi-sim \
        tesseract-langpack-chi-tra \
        fontconfig \
        dejavu-fonts-common \
        dejavu-sans-fonts \
        dejavu-serif-fonts \
        dejavu-sans-mono-fonts \
        curl \
        mysql && \
    microdnf clean all

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
