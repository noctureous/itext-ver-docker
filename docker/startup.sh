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

# Logging manager configuration - MUST be set before any logging occurs
export JAVA_OPTS="${JAVA_OPTS} -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

# Disable Java agent warnings for ByteBuddy
export JAVA_OPTS="${JAVA_OPTS} -XX:+EnableDynamicAgentLoading"

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

# Disable Java util logging configuration to use JBoss logging exclusively
export JAVA_OPTS="${JAVA_OPTS} -Djava.util.logging.config.file="

# SLF4J configuration
export JAVA_OPTS="${JAVA_OPTS} -Dorg.slf4j.provider=org.jboss.slf4j.JBossSlf4jServiceProvider"

# Start WildFly in standalone mode
exec ${WILDFLY_HOME}/bin/standalone.sh -c standalone.xml
