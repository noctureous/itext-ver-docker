-- MySQL initialization script for PDF Analyzer
CREATE DATABASE IF NOT EXISTS cergprod CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE cergprod;

-- Create user if not exists
CREATE USER IF NOT EXISTS 'cergprod'@'%' IDENTIFIED BY 'cergprod';
GRANT ALL PRIVILEGES ON cergprod.* TO 'cergprod'@'%';
FLUSH PRIVILEGES;

-- Create application tables (will be created by Spring Boot on first run)
-- This is just a placeholder - Spring Boot will handle table creation with Hibernate
