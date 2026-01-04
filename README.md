# AI Visibility Tracker – Spring Boot Backend

This repository contains the **Spring Boot backend** for the **AI Visibility Tracker** engineering challenge.

The application tracks **brand visibility in AI-generated answers** (e.g., ChatGPT-like responses) by querying AI models, analyzing mentions, and storing results for dashboards and comparison.

---

## 🚀 Project Overview

When users ask AI models questions like *"What’s the best CRM software for startups?"*, only a few brands get mentioned.

This backend:

* Sends prompts to AI models (Gemini & Groq)
* Analyzes responses for brand mentions and context
* Calculates AI visibility & citation share
* Persists data in PostgreSQL
* Exposes APIs for a React-based frontend dashboard

---

## 🧱 Tech Stack

* **Java 17**
* **Spring Boot**
* **PostgreSQL**
* **Hibernate / JPA**
* **HikariCP** (connection pooling)
* **AI Models**

  * Google Gemini (free tier)
  * Groq LLM (free tier)

---

## 📁 Repository Structure (High Level)

```
backend/
 ├── src/main/java
 │   ├── controller
 │   ├── service
 │   ├── repository
 │   ├── model
 │   └── AiVisibilityTrackerApplication.java
 ├── src/main/resources
 │   └── application.properties  [need to replace with file provided below]
 └── pom.xml
```

---

## ⚙️ Prerequisites

Make sure you have the following installed locally:

* **Java 17+**
* **Maven 3.8+**
* **PostgreSQL 13+**
* ** React + Node.js 18+** (for frontend)

---

## 🗄️ Database Setup (PostgreSQL)

1. Start PostgreSQL
2. Create a database:

```sql
CREATE DATABASE ai_visibility_tracker;
```

3. Ensure your PostgreSQL credentials match the values in `application.properties`

---

## 🔑 AI API Keys Setup

This project uses **Gemini** and **Groq** because they provide **publicly available free-tier API keys**, making local testing easy.

Add your API keys to:

```
src/main/resources/application.properties
```

Example:

```properties
google.api.key=google_XXXXX
groq.api.key=gsk_XXXXXX
```

⚠️ **Do not commit real API keys to GitHub**

---

## 🛠️ application.properties

```properties
# ===============================
# Server Configuration
# ===============================
server.port=8080
spring.application.name=ai-visibility-tracker

# ===============================
# AI API Configuration
# ===============================
# API Keys
google.api.key=google_XXXXX
groq.api.key=gsk_XXXXXX

# API URLs
google.api.url=https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent
groq.api.url=https://api.groq.com/openai/v1/chat/completions

# ===============================
# PostgreSQL Database Configuration
# ===============================
spring.datasource.url=jdbc:postgresql://localhost:5432/ai_visibility_tracker
spring.datasource.username=postgres
spring.datasource.password=sodtgarfield
spring.datasource.driver-class-name=org.postgresql.Driver

# ===============================
# JPA / Hibernate Configuration
# ===============================
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=false

# ===============================
# Connection Pool
# ===============================
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.max-lifetime=600000
spring.datasource.hikari.leak-detection-threshold=60000
spring.datasource.hikari.pool-name=PostgresHikariPool

# ===============================
# Logging
# ===============================
logging.pattern.console=%-5level [%logger{36}] - %msg%n
```

---

## ▶️ Running the Backend Locally

```bash
mvn clean install
mvn spring-boot:run
```

The backend will start on:

```
http://localhost:8080
```

---

## 🎨 Frontend Setup (React)

The frontend is maintained in a separate repository:

🔗 Frontend Repository:
https://github.com/innov8r-jug/AI-Visibility-Tracker-Frontend

To start the frontend locally:

```bash
npm install
npm run dev
```

Make sure the frontend is configured to point to:

```
http://localhost:5173  or http://localhost:8080 [backend and frontend on same port]
```

---

## 📊 Features Implemented

* AI prompt execution using Gemini & Groq
* Brand mention detection
* AI visibility & citation share calculation
* Prompt tracking & history
* Leaderboard-ready APIs
* Competitor impersonation support

---

## 🧠 Design Decisions

* **Multiple AI providers** for redundancy and comparison
* **PostgreSQL** for relational analytics & aggregation
* **Stateless APIs** for frontend flexibility
* **Free-tier models** to enable easy local evaluation

---

## 🚧 Future Improvements

* Prompt versioning & scheduling
* Background job processing
* Caching AI responses
* Rate limiting & retries
* Auth & multi-tenant support
* Advanced NLP for context classification

---

## 📹 Submission Notes

This project was built as part of the **Writesonic Fullstack Engineer Challenge**.

All assumptions, trade-offs, and implementation choices are documented here as required.

---

## 👤 Author

**Pranchal Gupta**
