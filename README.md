# Backend Setup Guide

## Environment Variables Setup

### Windows (PowerShell)
```powershell
$env:OPENAI_API_KEY="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"
$env:ANTHROPIC_API_KEY="your_anthropic_key"
$env:GOOGLE_API_KEY="your_google_key"
$env:PERPLEXITY_API_KEY="your_perplexity_key"
```

### Windows (Command Prompt)
```cmd
set OPENAI_API_KEY=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
set ANTHROPIC_API_KEY=your_anthropic_key
set GOOGLE_API_KEY=your_google_key
set PERPLEXITY_API_KEY=your_perplexity_key
```

### Linux/Mac
```bash
export OPENAI_API_KEY="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"
export ANTHROPIC_API_KEY="your_anthropic_key"
export GOOGLE_API_KEY="your_google_key"
export PERPLEXITY_API_KEY="your_perplexity_key"
```

## Running the Application

After setting environment variables, run:

```bash
mvn spring-boot:run
```

Or build and run:

```bash
mvn clean install
java -jar target/ai-visibility-tracker-1.0.0.jar
```

## Note

The application.properties file is already configured to read from environment variables. You don't need to modify it directly.

