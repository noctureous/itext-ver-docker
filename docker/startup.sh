#!/bin/bash

# WildFly startup script for PDF Analyzer

set -e

echo "Starting PDF Analyzer on WildFly 27..."

# Wait for database to be ready
echo "Waiting for MySQL to be ready..."
while ! mysqladmin ping -h"$DB_HOST" -P"$DB_PORT" --silent 2>/dev/null; do
    echo 'Waiting for MySQL to be ready...'
    sleep 2
done

echo "MySQL is ready. Starting WildFly..."

# Set Java options
export JAVA_OPTS="${JAVA_OPTS} -Djboss.bind.address=0.0.0.0"
export JAVA_OPTS="${JAVA_OPTS} -Djboss.bind.address.management=0.0.0.0"
export JAVA_OPTS="${JAVA_OPTS} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-docker}"

# Set application properties
export JAVA_OPTS="${JAVA_OPTS} -Dspring.datasource.url=jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&serverTimezone=UTC"
export JAVA_OPTS="${JAVA_OPTS} -Dspring.datasource.username=${DB_USER}"
export JAVA_OPTS="${JAVA_OPTS} -Dspring.datasource.password=${DB_PASSWORD}"

# OCR configuration
export JAVA_OPTS="${JAVA_OPTS} -Docr.tesseract.datapath=/usr/share/tesseract/tessdata"

# File upload directory
export JAVA_OPTS="${JAVA_OPTS} -Dpdf.input-directory=/opt/pdf-analyzer/uploads"

# Logging
export JAVA_OPTS="${JAVA_OPTS} -Dlogging.file.path=/opt/pdf-analyzer/logs"

# Disable Java util logging to prevent ConsoleHandler warnings
export JAVA_OPTS="${JAVA_OPTS} -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
export JAVA_OPTS="${JAVA_OPTS} -Djava.util.logging.config.file="
export JAVA_OPTS="${JAVA_OPTS} -Dlogging.configuration=file:\${jboss.server.config.dir}/logging.properties"

# Start WildFly in standalone mode
exec ${WILDFLY_HOME}/bin/standalone.sh -c standalone.xml
