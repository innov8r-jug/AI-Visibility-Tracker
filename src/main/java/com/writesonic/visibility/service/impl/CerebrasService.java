package com.writesonic.visibility.service.impl;

import com.google.gson.JsonObject;
import com.writesonic.visibility.config.AIConfig;
import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.service.util.OpenAiCompatibleChatClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class CerebrasService extends AbstractAIService {

    public CerebrasService(AIConfig aiConfig, OkHttpClient httpClient) {
        super(aiConfig, httpClient);
    }

    @Override
    protected Request buildRequest(String prompt) {
        return OpenAiCompatibleChatClient.buildRequest(
                aiConfig.getCerebrasApiUrl(), aiConfig.getCerebrasApiKey(), aiConfig.getCerebrasApiModel(), prompt, gson);
    }

    @Override
    protected String parseResponseText(String rawJson) throws IOException {
        JsonObject jsonResponse = gson.fromJson(rawJson, JsonObject.class);
        return OpenAiCompatibleChatClient.extractContent(jsonResponse, "Cerebras");
    }

    @Override
    protected String getApiKey() {
        return aiConfig.getCerebrasApiKey();
    }

    @Override
    public String getModelName() {
        return "Cerebras AI";
    }

    @Override
    public AIModel getModelType() {
        return AIModel.CEREBRAS;
    }
}
